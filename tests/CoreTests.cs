using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Diagnostics;
using LowTube;

public static class CoreTests {
    static int passed;
    static void Check(bool ok,string name) { if(!ok) throw new Exception("FAIL: "+name);Console.WriteLine("PASS: "+name);passed++; }
    static void Reject(Action action,string name) { try {action();}catch(ArgumentException){Check(true,name);return;}throw new Exception("Accepted invalid input: "+name); }
    public static int Main(string[] arguments) {
        try {
            L.Set("en");
            if(arguments.Length>0 && arguments[0]=="--eq-graphs"){
                var graphs=new System.Collections.Generic.Dictionary<string,string>();
                foreach(string id in AudioEqualizer.Ids){var setting=new EqualizerSettings{Enabled=true};AudioEqualizer.Preset(setting,id);graphs[id]=AudioEqualizer.Graph(setting);}
                graphs["maximum"]=AudioEqualizer.Graph(new EqualizerSettings{Enabled=true,Bands=Enumerable.Repeat(12,10).ToArray()});Console.WriteLine(Json.Serializer().Serialize(graphs));return 0;
            }
            Check(AudioEqualizer.Graph(new EqualizerSettings())=="","disabled equalizer has no audio filter overhead");
            Check(AudioEqualizer.Graph(new EqualizerSettings{Enabled=true})=="","flat unity equalizer bypasses processing");
            var bassEq=new EqualizerSettings{Enabled=true};AudioEqualizer.Preset(bassEq,"bass");
            Check(AudioEqualizer.Graph(bassEq).Contains("equalizer=f=31:t=o:w=1:g=6") && AudioEqualizer.EffectivePreamp(bassEq)==-6,"bass preset generates octave filters with input headroom");
            Check(AudioEqualizer.Graph(bassEq).StartsWith("aresample=48000,"),"high-frequency band supports low-rate sources by resampling");
            var customEq=new EqualizerSettings{CustomBands=new[]{7,-4},CustomPreamp=-3};AudioEqualizer.Preset(customEq,"custom");
            Check(customEq.Bands.Length==10 && customEq.Bands[0]==7 && customEq.Preamp==-3,"custom curve and preamp restored");
            var normalizedEq=AudioEqualizer.Copy(new EqualizerSettings{Bands=new[]{99,-99},Preamp=99});
            Check(normalizedEq.Bands[0]==12 && normalizedEq.Bands[1]==-12 && normalizedEq.Preamp==12,"saved equalizer gains are bounded");
            var foreignFilter=Json.Object("{\"name\":\"volume\",\"label\":\"other\"}");
            var ownFilter=Json.Object("{\"name\":\"lavfi\",\"label\":\"lowtube_equalizer\"}");
            var replacedEq=AudioEqualizer.MergeFilters(new object[]{foreignFilter,ownFilter},bassEq);
            Check(replacedEq.Length==2 && Object.ReferenceEquals(replacedEq[0],foreignFilter),"live equalizer update preserves unrelated audio filters");
            Check(AudioEqualizer.MergeFilters(replacedEq,new EqualizerSettings()).Length==1,"disable removes only the equalizer filter");
            Check(AudioEqualizer.MergeFilters(replacedEq,bassEq).Length==2,"repeated apply does not accumulate equalizer filters");
            var roundtripEq=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(new Settings{Equalizer=bassEq}));
            Check(roundtripEq.Equalizer.Bands[0]==6 && roundtripEq.Equalizer.Enabled,"equalizer preferences survive settings serialization");
            Check(Player.BuildArgs(new Settings{Equalizer=bassEq},new Media(),true,"test").Any(x=>x.StartsWith("--af=@lowtube_equalizer:")),"audio-only playback starts with saved equalizer");
            Check(Player.BuildArgs(new Settings{Equalizer=bassEq},new Media(),false,"test").Any(x=>x.StartsWith("--af=@lowtube_equalizer:")),"video playback starts with saved equalizer");


            Check(new Settings().PageSize==25,"existing settings retain 25-item page default");
            Check(Paging.Size(0)==1 && Paging.Size(251)==250 && Paging.Size(73)==73,"page size accepts arbitrary integers within 1 to 250");
            Check(Paging.Range(0,1)=="1:1" && Paging.Range(1,1)=="2:2","single-item pages advance without gaps");
            Check(Paging.Range(0,250)=="1:250" && Paging.Range(1,250)=="251:500","250-item pages have non-overlapping ranges");
            Check(Paging.Range(2,73)=="147:219" && Paging.Range(1,73)=="74:146","previous arbitrary-size page returns the correct range");
            Check(Paging.SearchTarget("ytsearch100:cats",1,250)=="ytsearch500:cats","search count includes the complete requested page");
            Check(Paging.SearchTarget(":ytrec",1,250)==":ytrec","account feed target is preserved");
            Check(Paging.BatchCount(1)==25 && Paging.BatchCount(73)==75 && Paging.BatchCount(250)==250,"manual channel batches align with existing cache pages");
            Check(FeedQueuePolicy.IsManual(-1) && !FeedQueuePolicy.IsManual(0),"manual lists use a separate priority from automatic channel jobs");
            Check(FeedQueuePolicy.MayQueue(false,true,true),"explicit retry can bypass a failed automatic attempt");
            Check(!FeedQueuePolicy.MayQueue(false,true,false),"automatic retry remains suppressed within the interval");
            Check(!FeedQueuePolicy.MayQueue(true,false,true),"opening an in-flight list cannot start a duplicate process");
            Check(!FeedQueuePolicy.SameBudget(-1,0) && FeedQueuePolicy.SameBudget(-1,-1),"one manual batch cannot silently include automatic jobs");
            Check(FeedQueuePolicy.FollowUpPriority(-1)==2,"manual channel catch-up returns to automatic budget");
            var budgetClock=new DateTime(2026,10,11,0,0,0,DateTimeKind.Utc);var workBudget=new FeedWorkBudget();int allowedWork=0;
            for(int i=0;i<1000;i++)if(workBudget.CanStart(budgetClock,15,false)){workBudget.Start(false);workBudget.Complete(1);allowedWork++;}
            Check(allowedWork==4,"background queue cannot start more than four requests in one interval");
            Check(!workBudget.CanStart(budgetClock.AddMinutes(14),15,false),"pending background work stays paused before interval expires");
            Check(workBudget.CanStart(budgetClock.AddMinutes(15),15,false),"background allowance renews after interval");
            var dateBudget=new FeedWorkBudget();Check(dateBudget.CanStart(budgetClock,15,true),"one date lookup allowed");dateBudget.Start(true);
            Check(!dateBudget.CanStart(budgetClock,15,true) && dateBudget.CanStart(budgetClock,15,false),"date lookup allowance cannot create a per-video request flood");
            var timeBudget=new FeedWorkBudget();timeBudget.CanStart(budgetClock,15,false);timeBudget.Start(false);timeBudget.Complete(75);
            Check(!timeBudget.CanStart(budgetClock,15,false),"slow background request consumes time allowance and prevents further launches");
            Check(FeedPreset.Get(0).DelayMs==120000 && FeedPreset.Get(1).DelayMs==60000 && FeedPreset.Get(2).DelayMs==30000,"all internet presets have real idle pauses");
            var cacheDir=Path.Combine(Path.GetTempPath(),"lowtube-feed-tests-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(cacheDir);
            try{
                string path=Path.Combine(cacheDir,"testFeedChannels.json"),testFeedChannel="UCabcdefghijklmnopqrstuv";
                Func<string,string,Video> row=(id,date)=>new Video{Id=id,Url="https://www.youtube.com/watch?v="+id,Title=id,ChannelId=testFeedChannel,Published=date,Date=date.Length>=10?date.Substring(0,10):""};
                var old=row("abcdefghijk","2026-10-01T00:00:00Z");var fresh=row("abcdefghijl","2026-10-02T00:00:00Z");var testFeedUnknown=row("abcdefghijm","");
                var testFeedMerged=FeedCache.Merge(new[]{testFeedUnknown,fresh,row(old.Id,"")},new[]{old});
                Check(testFeedMerged.Count==3 && testFeedMerged[0].Id==fresh.Id && testFeedMerged[1].Published==old.Published && testFeedMerged[2].Id==testFeedUnknown.Id,"feed merge deduplicates, preserves dates and mixes newest first");
                var testFeedCache=new FeedCache(path);testFeedCache.Load();
                Check(!testFeedCache.Add("local",testFeedChannel,"video",0,new List<Video>{old,fresh}),"first testFeedCache fill does not crawl the entire testFeedChannel");
                Check(testFeedCache.Depth("local",testFeedChannel,"video")==1 && testFeedCache.End("local",testFeedChannel,"video"),"short testFeedChannel page marks end");
                Check(testFeedCache.Fresh("local",testFeedChannel,"video",15),"recent head check avoids redundant foreground refresh");
                Check(testFeedCache.Read("different-session",new[]{testFeedChannel},"all").Count==0,"session testFeedCache isolation");
                var restored=new FeedCache(path);restored.Load();
                Check(restored.Read("local",new[]{testFeedChannel},"all").Count==2,"testFeedChannel testFeedCache survives restart");
                Check(restored.Read("local",new[]{testFeedChannel},"shorts").Count==0,"testFeedChannel testFeedCache isolates content kinds");
                Check(restored.Read("local",new[]{"UC0000000000000000000000"},"all").Count==0,"removed testFeedChannel not shown");
                var head=Enumerable.Range(0,25).Select(i=>row("new"+i.ToString("00000000"),"")).ToList();
                Check(restored.Add("local",testFeedChannel,"video",0,head),"full unseen head requests incremental catch-up");
                head[3]=fresh;
                Check(!restored.Add("local",testFeedChannel,"video",1,head),"known ID stops incremental catch-up");
                Check(restored.Depth("local",testFeedChannel,"video")==2,"contiguous page depth advances");
                restored.Enrich("new00000000",row("new00000000","2026-10-03T01:00:00Z"));
                Check(restored.Read("local",new[]{testFeedChannel},"all")[0].Id=="new00000000","background date enrichment reorders mixed results");
                var dated=Video.From(Json.Object("{\"id\":\"abcdefghijk\",\"upload_date\":\"20261002\"}"));
                Check(dated.Date=="2026-10-02" && dated.Published=="2026-10-02T00:00:00Z","flat upload date parsed without invented precision");
                Check(Video.From(Json.Object("{\"timestamp\":86400}")).Published=="1970-01-02T00:00:00Z","timestamp converted in UTC");
                Check(Video.From(Json.Object("{\"upload_date\":\"not a date\"}")).Published=="","testFeedUnknown date remains testFeedUnknown");
                var testFeedChannels=new List<LocalChannel>{new LocalChannel{Id=testFeedChannel,Title="A"},new LocalChannel{Id="UC0000000000000000000000",Title="B"}};
                string batch="{\"channel_id\":\"UC0000000000000000000000\",\"entries\":[{\"id\":\"abcdefghijk\"}]}\n{\"channel_id\":\"UCabcdefghijklmnopqrstuv\",\"entries\":[]}";
                var parsed=Backend.ParseChannelBatch(batch,testFeedChannels,"shorts");
                Check(parsed.Count==2 && parsed[testFeedChannel].Count==0 && parsed[testFeedChannels[1].Id][0].Channel=="B","batch roots matched by testFeedChannel ID, not output order");
                Check(parsed[testFeedChannels[1].Id][0].Kind=="shorts","batch output retains requested content kind");
                var partial=Backend.ParseChannelBatch(batch.Split('\n')[0],testFeedChannels,"video");
                Check(partial.Count==1 && !partial.ContainsKey(testFeedChannel),"failed testFeedChannel is not treated as a successful empty list");
                var rec=new RecommendationCache();rec.Put("page",new List<Video>{old});
                Check(rec.Get("page").Count==1 && new RecommendationCache().Get("page")==null,"recommendations cannot survive process restart");
                rec.Get("page").Clear();Check(rec.Get("page").Count==1,"recommendation reads do not expose mutable list");
                for(int i=0;i<13;i++)rec.Put("p"+i,new List<Video>{fresh});
                Check(rec.Get("page")==null && rec.Get("p12")!=null,"recommendation memory testFeedCache is bounded");
                rec.Clear();Check(rec.Get("p12")==null,"session/settings testFeedCache invalidation");
                Check(FeedPreset.Get(0).Batch<FeedPreset.Get(1).Batch && FeedPreset.Get(1).Batch<FeedPreset.Get(2).Batch && FeedPreset.Get(0).Minutes>FeedPreset.Get(2).Minutes,"presets bound batch size and refresh frequency");
                var pagingCache=new FeedCache(Path.Combine(cacheDir,"paging.json"));
                for(int n=0;n<20;n++)pagingCache.Add("local",testFeedChannel,"video",n,Enumerable.Range(n*25,25).Select(i=>row("pg"+i.ToString("000000000"),"")).ToList(),false);
                pagingCache.Flush();
                Check(pagingCache.Read("local",new[]{testFeedChannel},"video").Skip(250).Take(250).Count()==250,"second 250-item subscription page is retained instead of being truncated to the old 250-item cache cap");
                File.WriteAllText(path,"broken after crash");restored.Load();Check(restored.Read("local",new[]{testFeedChannel},"all").Count==0,"damaged disk testFeedCache recovers as empty");
                File.WriteAllText(path+".tmp","orphaned write");restored.Add("local",testFeedChannel,"video",0,new List<Video>{old});
                var recovered=new FeedCache(path);recovered.Load();Check(recovered.Read("local",new[]{testFeedChannel},"all").Count==1,"orphaned temporary file does not block atomic testFeedCache recovery");
            }finally{Directory.Delete(cacheDir,true);}

            var shortItem=Video.From(Json.Object("{\"id\":\"abcdefghijk\",\"url\":\"https://www.youtube.com/shorts/abcdefghijk\"}"));
            Check(shortItem.Kind=="shorts" && shortItem.Url.Contains("watch?v="),"Shorts kind survives canonical video URL");
            var liveItem=Video.From(Json.Object("{\"id\":\"abcdefghijl\",\"live_status\":\"is_live\"}"));
            Check(liveItem.Kind=="live","live video classified");
            Check(ContentFeeds.Kind(Json.Object("{\"was_live\":true}"))=="live","live archive classified");
            var playlistItem=Video.From(Json.Object("{\"id\":\"PLtesting\",\"url\":\"https://www.youtube.com/playlist?list=PLtesting\",\"_type\":\"url\"}"));
            Check(playlistItem.Kind=="playlist" && ContentFeeds.IsPlaylist(playlistItem.Url),"flat playlist entry opens as playlist");
            Check(!ContentFeeds.IsPlaylist("https://www.youtube.com/watch?v=abcdefghijk&list=PLtesting"),"video inside playlist remains playable as video");
            Check(ContentFeeds.Filter(new[]{shortItem,liveItem,playlistItem},"shorts","").Count()==1,"content filter isolates Shorts");
            shortItem.ChannelId="one";liveItem.ChannelId="two";Check(ContentFeeds.Filter(new[]{shortItem,liveItem},"all","one").Single()==shortItem,"channel filter isolates channel");
            Check(ContentFeeds.ChannelTarget("UCabcdefghijklmnopqrstuv","playlist").EndsWith("/playlists"),"playlist channel tab selected");
            Check(ContentFeeds.ChannelTarget("UCabcdefghijklmnopqrstuv","live").EndsWith("/streams"),"live channel tab selected");
            Check(ContentFeeds.SearchTarget("test query","playlist").Contains("sp=EgIQAw"),"playlist search uses native playlist filter");
            Check(ContentFeeds.SearchTarget("https://youtu.be/abcdefghijk","playlist")=="https://youtu.be/abcdefghijk","URL search preserves explicit target");
            var importedChannels=ContentFeeds.Channels(Json.Object("{\"entries\":[{\"channel_id\":\"UCabcdefghijklmnopqrstuv\",\"channel\":\"Test\"},{\"id\":\"UCabcdefghijklmnopqrstuv\"},{\"id\":\"invalid\"}]}"));
            Check(importedChannels.Count==1 && importedChannels[0].Title=="Test","session channels validate IDs and deduplicate");
            var tuning=new Settings{D3D11Flip=true,D3D11ZeroCopy=true,DecodeThreads=0,VideoScale="lanczos",VideoSync="display-resample",FrameDrop="no",VideoDeband=true,VideoInterpolation=true,AudioPitchCorrection=false};
            var ta=Player.BuildArgs(tuning,new Media(),false,"test");
            foreach(string option in new[]{"--d3d11-flip=yes","--d3d11va-zero-copy=yes","--vd-lavc-threads=0","--scale=lanczos","--video-sync=display-resample","--framedrop=no","--deband=yes","--interpolation=yes","--audio-pitch-correction=no","--osc=no"})Check(ta.Contains(option),"advanced option: "+option);
            Check(ta.Any(x=>x.EndsWith("lowtube_osc.lua")),"custom controller explicitly loaded");
            Check(PlayerTuning.ProcessKey(tuning)!=PlayerTuning.ProcessKey(new Settings()),"flip change recreates GPU context");
            Check(PlayerTuning.ProcessKey(new Settings{DecodeThreads=4})==PlayerTuning.ProcessKey(new Settings()),"decoder threads do not force process restart");
            var savedTune=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(tuning));Check(PlayerTuning.Signature(savedTune)==PlayerTuning.Signature(tuning),"performance settings persist through JSON clone");
            Check(PlayerTuning.Arguments(new Settings{VideoScale="bad",FrameDrop="bad",VideoSync="bad",DecodeThreads=999}).Contains("--vd-lavc-threads=16"),"thread count clamped");
            Check(PlayerTuning.Arguments(new Settings{VideoScale="bad"}).Contains("--scale=bilinear"),"invalid scaler falls back safely");
            Check(Player.BuildArgs(new Settings{PlayerBorderless=true},new Media(),false,"test").Contains("--border=no"),"borderless launch option");
            Check(Player.BuildArgs(new Settings{PlayerBorderless=false},new Media(),false,"test").Contains("--border=yes"),"normal border launch option");
            var combinedLive=Json.Object("{\"formats\":[{\"url\":\"https://example.com/live.m3u8\",\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"height\":720,\"fps\":30}]}");
            Check(Media.From(FormatPicker.Select(combinedLive,new Settings(),true),true).VideoUrl.EndsWith("live.m3u8"),"audio-only accepts combined live HLS stream without decoding video");

            foreach(string browser in BrowserSessions.Names)Check(BrowserSessions.Argument(browser,"")==browser,"browser selector: "+browser);
            Check(BrowserSessions.Argument("chrome",@" C:\Users\Name With Spaces\Profile 1 ")==@"chrome:C:\Users\Name With Spaces\Profile 1","profile preserves drive colon and spaces");
            Reject(()=>BrowserSessions.Argument("unknown",""),"reject unknown browser");
            Reject(()=>BrowserSessions.Argument("chrome","bad\nprofile"),"reject profile newline");
            Reject(()=>BrowserSessions.Argument("chrome","bad::profile"),"reject container syntax in profile");
            var legacyBrowser=new Settings{Auth="firefox",FirefoxProfile="legacy",BrowserName="edge",BrowserProfile="new"};
            Check(BrowserSessions.Browser(legacyBrowser)=="firefox" && BrowserSessions.Profile(legacyBrowser)=="legacy","legacy Firefox configuration preserved");
            var browserSettings=new Settings{Auth="browser",BrowserName="edge",BrowserProfile="new"};
            Check(BrowserSessions.Browser(browserSettings)=="edge" && BrowserSessions.Profile(browserSettings)=="new","new browser configuration");
            var common=typeof(Backend).GetMethod("ExtractorArguments",System.Reflection.BindingFlags.Static|System.Reflection.BindingFlags.NonPublic);
            var browserArgs=(List<string>)common.Invoke(null,new object[]{browserSettings,true,"fixture-ffmpeg","node:fixture-node"});
            Check(browserArgs.Contains("edge:new"),"backend uses selected browser for authenticated requests");
            Check(!((List<string>)common.Invoke(null,new object[]{browserSettings,false,"fixture-ffmpeg","node:fixture-node"})).Contains("--cookies-from-browser"),"guest requests do not read browser cookies");
            string profilesTemp=Path.Combine(Path.GetTempPath(),"lowtube-browser-tests-"+Guid.NewGuid().ToString("N"));
            try{
                string chromeProfile=Path.Combine(profilesTemp,"Google","Chrome","User Data","Profile 2"),firefoxProfile=Path.Combine(profilesTemp,"Mozilla","Firefox","Profiles","test.default"),operaProfile=Path.Combine(profilesTemp,"Opera Software","Opera GX Stable");
                Directory.CreateDirectory(Path.Combine(chromeProfile,"Network"));File.WriteAllText(Path.Combine(chromeProfile,"Network","Cookies"),"");
                Directory.CreateDirectory(firefoxProfile);File.WriteAllText(Path.Combine(firefoxProfile,"cookies.sqlite"),"");
                Directory.CreateDirectory(operaProfile);File.WriteAllText(Path.Combine(operaProfile,"Cookies"),"");
                Check(BrowserSessions.Discover("chrome",profilesTemp,profilesTemp).SequenceEqual(new[]{chromeProfile}),"discover Chromium network cookie profile");
                Check(BrowserSessions.Discover("firefox",profilesTemp,profilesTemp).SequenceEqual(new[]{firefoxProfile}),"discover Firefox profile");
                Check(BrowserSessions.Discover("opera",profilesTemp,profilesTemp).SequenceEqual(new[]{operaProfile}),"discover Opera GX root profile");
                Check(BrowserSessions.Discover("edge",profilesTemp,profilesTemp).Length==0,"missing browser has no detected profiles");
            }finally{if(Directory.Exists(profilesTemp))Directory.Delete(profilesTemp,true);}
            Check(BrowserSessions.CaptureError("Failed to decrypt with DPAPI").Contains("encryption"),"cookie encryption error classified without exposing original log");
            string autoTemp=Path.Combine(Path.GetTempPath(),"lowtube-auto-tests-"+Guid.NewGuid().ToString("N"));
            try{
                string local=Path.Combine(autoTemp,"Local"),roaming=Path.Combine(autoTemp,"Roaming");Directory.CreateDirectory(local);Directory.CreateDirectory(roaming);
                string unusual=Path.Combine(local,"Unusual Browser","User Data"),personal=Path.Combine(unusual,"Profile 3");
                Directory.CreateDirectory(Path.Combine(personal,"Network"));File.WriteAllText(Path.Combine(personal,"Network","Cookies"),"");File.WriteAllText(Path.Combine(unusual,"Local State"),"{\"profile\":{\"info_cache\":{\"Profile 3\":{\"name\":\"Personal\"}}}}");
                string external=Path.Combine(autoTemp,"external-firefox");Directory.CreateDirectory(external);File.WriteAllText(Path.Combine(external,"cookies.sqlite"),"");
                string registration=Path.Combine(roaming,"Waterfox");Directory.CreateDirectory(registration);File.WriteAllText(Path.Combine(registration,"profiles.ini"),"[Profile0]\nIsRelative=0\nPath="+external+"\n");
                string portable=Path.Combine(autoTemp,"Portable","App","Browser"),portableProfile=Path.Combine(autoTemp,"Portable","Data","profile");Directory.CreateDirectory(portable);Directory.CreateDirectory(portableProfile);File.WriteAllText(Path.Combine(portableProfile,"cookies.sqlite"),"");
                var detected=BrowserDiscovery.Scan(local,roaming,new[]{Path.Combine(portable,"firefox.exe")},CancellationToken.None);
                Check(detected.Profiles.Count==3,"automatic scan finds uncommon Chromium, registered external Firefox and portable profile");
                Check(detected.Profiles.Any(x=>x.Profile==personal && x.Browser=="chromium" && x.Label.Contains("Personal")),"unknown Chromium engine and friendly profile name detected");
                Check(detected.Profiles.Any(x=>x.Profile==external && x.Browser=="firefox" && x.Label.Contains("Waterfox")),"external Firefox fork profile classified automatically");
                Check(detected.Profiles.Any(x=>x.Profile==portableProfile && x.Browser=="firefox"),"portable profile discovered from running executable");
                Check(!detected.Limited,"small scan completes without limits");
                var cancelled=new CancellationTokenSource();cancelled.Cancel();bool stopped=false;try{BrowserDiscovery.Scan(local,roaming,new string[0],cancelled.Token);}catch(OperationCanceledException){stopped=true;}Check(stopped,"automatic scan supports cancellation");
            }finally{if(Directory.Exists(autoTemp))Directory.Delete(autoTemp,true);}
            string cookiePrefix=".youtube.com\tTRUE\t/\tTRUE\t2000000000\t";
            Check(SavedSession.HasSignInCookies(cookiePrefix+"SAPISID\tTEST"),"automatic import recognizes sign-in cookie");
            Check(!SavedSession.HasSignInCookies(cookiePrefix+"VISITOR_INFO1_LIVE\tTEST"),"automatic import does not mistake guest cookies for sign-in");
            Check(!SavedSession.HasSignInCookies(".youtube.com\tTRUE\t/\tTRUE\t1\tSID\tTEST"),"expired sign-in cookie is rejected");

            if(arguments.Length>0 && arguments[0]=="--monitor-worker"){
                var burn=Stopwatch.StartNew();while(burn.ElapsedMilliseconds<1000)Thread.SpinWait(5000);Thread.Sleep(750);return 0;
            }
            if(arguments.Length==2 && arguments[0]=="--monitor-parent"){
                Console.ReadLine();using(var child=Process.Start(new ProcessStartInfo(arguments[1],"--monitor-worker"){UseShellExecute=false,CreateNoWindow=true})){child.WaitForExit();return child.ExitCode;}
            }
            if(arguments.Length==1 && arguments[0]=="--process-monitor"){
                if(Environment.OSVersion.Platform!=PlatformID.Win32NT){Console.WriteLine("SKIP: native process monitor integration needs Windows");return 0;}
                string monitorTemp=Path.Combine(Path.GetTempPath(),"lowtube-monitor-test-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(monitorTemp);
                string exe=System.Reflection.Assembly.GetExecutingAssembly().Location,qjs=Path.Combine(monitorTemp,"qjs.exe");File.Copy(exe,qjs);
                try{
                    StartupTrace.Begin();var monitorAttempt=StartupTrace.StartAttempt("synthetic monitor test",new Settings(),false);
                    using(var parent=new Process{StartInfo=new ProcessStartInfo(exe,Rules.Arguments(new[]{"--monitor-parent",qjs})){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true}})
                    using(var job=new ProcessJob()){
                        parent.Start();job.Attach(parent);
                        using(var monitor=new StartupTrace.ProcessMonitor(job,parent.Id,monitorAttempt)){
                            parent.StandardInput.WriteLine("go");parent.StandardInput.Flush();
                            if(!parent.WaitForExit(15000)){job.Kill();throw new Exception("Synthetic process timed out");}
                        }
                    }
                    string report=StartupTrace.Report();Check(report.Contains("QuickJS observed;") && report.Contains("QuickJS exited; code=0"),"Windows job monitor detects synthetic QuickJS start and exit");
                    Check(report.Contains("CPU-total=") && report.Contains("peak-RAM-observed="),"Windows monitor returns resource metrics");
                    Check(!report.Contains(monitorTemp),"native process monitor excludes executable paths");Console.WriteLine("Native Windows monitor checks passed");return 0;
                }finally{Directory.Delete(monitorTemp,true);}
            }
            Check(PlaybackTrace.Filter("{\"time-pos\":12.5,\"path\":\"secret\"}").Contains("time-pos=12.5"),"playback numeric diagnostics retained");
            Check(!PlaybackTrace.Filter("{\"path\":\"secret\",\"video-codec\":\"https://private.example\"}").Contains("private"),"playback diagnostics discard paths and URLs");

            Check(Rules.CompatibleRenderer("dxva2","opengl")=="d3d11","direct DXVA2 uses supported D3D11 interop");
            Check(Rules.CompatibleRenderer("d3d11va","opengl")=="d3d11","direct D3D11VA selects D3D11");
            Check(Rules.CompatibleRenderer("nvdec","d3d11")=="opengl","direct NVDEC selects OpenGL");
            Check(Rules.CompatibleRenderer("dxva2-copy","opengl")=="opengl","copy mode preserves renderer choice");
            Check(LocalFeeds.DiscoveryTarget("music","uk")=="ytsearch100:music українською","discovery language affects query");
            Check(LocalFeeds.LanguageCode("en;evil")=="any","discovery language is allowlisted");
            Check(Rules.DownloadTier(Json.Object("{\"width\":3840,\"height\":1600}"))==2160,"ultrawide 4K classified without changing native dimensions");
            Check(Rules.DownloadTier(Json.Object("{\"width\":2560,\"height\":1440}"))==1440,"1440p quality tier");
            var av1=Json.Object("{\"format_id\":\"401\",\"url\":\"https://example.invalid/v\",\"vcodec\":\"av01\",\"acodec\":\"none\",\"ext\":\"mp4\",\"height\":2160,\"fps\":60}");
            Check(Rules.Downloadable(av1,"MP4",false,false) && !Rules.Downloadable(av1,"MP4",false,true),"4K MP4 AV1 allowed only when H264 restriction removed");
            Check(Rules.Downloadable(av1,"MKV",false,false),"4K native AV1 can be saved to MKV");
            Check(JavaScriptRuntime.Normalize(null)=="node22","default runtime preserved");
            Check(JavaScriptRuntime.Normalize(" NODE22 ")=="node22","Node profile normalization");
            Check(JavaScriptRuntime.Normalize("../../evil")=="node22","unknown profile cannot inject a path");
            string oldRuntime=Environment.GetEnvironmentVariable("LOWTUBE_JS_RUNTIME");
            try {
                foreach(string profile in new[]{"node22","deno","quickjs-classic"}) {
                    Environment.SetEnvironmentVariable("LOWTUBE_JS_RUNTIME",profile);
                    string expected=profile=="node22"?"node:":profile=="deno"?"deno:":"quickjs:";
                    Check(JavaScriptRuntime.Argument.StartsWith(expected),"runtime argument "+profile);
                }
            } finally {Environment.SetEnvironmentVariable("LOWTUBE_JS_RUNTIME",oldRuntime);}
            Check(StartupTrace.ProcessMonitor.Label("node",false)=="Node.js","Node monitor label");
            Check(StartupTrace.ProcessMonitor.Label("deno",false)=="Deno","Deno monitor label");
            Check(Rules.YouTubeUrl("https://youtu.be/abcdefghijk") == "https://youtu.be/abcdefghijk","short YouTube URL");
            Check(Rules.YouTubeUrl("abcdefghijk").EndsWith("watch?v=abcdefghijk"),"video ID normalization");
            Check(Rules.YouTubeUrl("http://www.youtube.com/watch?v=abcdefghijk#fragment")=="https://www.youtube.com/watch?v=abcdefghijk","HTTPS normalization");
            foreach(string bad in new[]{"file:///etc/passwd","--exec=calc","https://youtube.com.evil.test/x","https://notyoutube.com/x","https://youtube.com@evil.test/x","https://user@youtube.com/x","https://youtube.com:444/x"})Reject(delegate{Rules.YouTubeUrl(bad);},"reject "+bad);
            Check(Rules.Search("музика & whoami",100)=="ytsearch100:музика & whoami","search input remains data");
            Check(Rules.Quote("")=="\"\"","empty Windows argument");
            Check(Rules.Quote("a b")=="\"a b\"","spaced Windows argument");
            Check(Rules.Quote("C:\\folder\\")=="\"C:\\folder\\\\\"","trailing backslash");
            Check(Rules.Quote("a\"b")=="\"a\\\"b\"","embedded quote");
            var settings=new Settings();
            Check(settings.Language=="en" && settings.DownloadHeight==720,"new settings default to English and independent download quality");
            L.Set("en");Check(L.T("Download options")=="Download options","English interface");
            L.Set("ru");Check(L.T("Download options")=="Параметры скачивания" && L.Relocalize("Download")=="Скачать","Russian interface and live captions");
            L.Set("uk");Check(L.T("Download options")=="Параметри завантаження" && L.Relocalize("Скачать")=="Скачати","switch back to Ukrainian");
            foreach(string language in new[]{"uk","en","ru"})Check(L.Keys.All(k=>!String.IsNullOrEmpty(L.Get(k,language))),"complete catalog: "+language);
            L.Set("invalid");Check(L.Language=="en","invalid saved language falls back safely");
            Check(Rules.KindCode("MKV (вибраний кодек)")=="MKV" && Rules.KindCode("Opus (без перекодування)")=="Opus","old download preferences migrate by stable ID");
            Check(Rules.ThumbnailUrl("abcdefghijk")=="https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg" && Rules.ThumbnailUrl("../secret")=="","preview URL is restricted to valid YouTube IDs");
            Check(Rules.PlaybackFormat(settings,true)=="ba[ext=m4a]/ba","audio only excludes combined streams");
            foreach(var branch in Rules.PlaybackFormat(settings,false).Split('/'))Check(branch.Contains("[height<=720]") && branch.Contains("[fps<=?30]") && branch.Contains("[vcodec^=avc1]"),"all playback fallback branches preserve limits");
            settings.Height=1080;settings.Fps30=false;settings.H264=false;
            Check(!Rules.PlaybackFormat(settings,false).Contains("avc1") && !Rules.PlaybackFormat(settings,false).Contains("fps"),"unrestricted codecs and FPS toggle");
            Reject(delegate{Rules.Filter(123,true);},"invalid quality rejected");
            settings=new Settings();
            foreach(string kind in new[]{"MP4","MKV","WebM","M4A","MP3","Opus"}) {
                var a=Rules.DownloadArgs(settings,kind);
                Check(a.Contains("--no-overwrites") && a.Contains("--no-playlist") && a.Contains("--concurrent-fragments"),"safe serial single-video download: "+kind);
            }
            Check(!Rules.DownloadArgs(settings,"MP4").Contains("--recode-video"),"MP4 does not transcode video");
            foreach(string kind in new[]{"MP4","MKV","WebM"}) {
                var a=Rules.DownloadArgs(settings,kind);
                Check(!a[a.IndexOf("-f")+1].Contains("fps"),"download FPS independent of playback: "+kind);
                settings.DownloadFps30=true;a=Rules.DownloadArgs(settings,kind);
                Check(a[a.IndexOf("-f")+1].Split('/').All(x=>x.Contains("[fps<=?30]")),"explicit download FPS limit on all fallbacks: "+kind);
                settings.DownloadFps30=false;
            }
            var download=Rules.DownloadArgs(settings,"MP4");
            Check(download[download.IndexOf("--output")+1].Contains("%(format_id)s") && download.Contains("--no-simulate"),"different formats get separate files; reporting does not disable download");
            var wide=Json.Object("{\"format_id\":\"wide\",\"width\":1536,\"height\":640,\"fps\":30,\"vcodec\":\"avc1\",\"format_note\":\"720p\"}");
            Check(Rules.Dimensions(wide)=="1536×640" && Rules.SelectedFormat(wide).Contains("[720p]"),"ultrawide actual dimensions kept distinct from source label");
            var oldSettings=Json.Serializer().Deserialize<Settings>("{\"Height\":480,\"Fps30\":true}");
            Check(oldSettings.Height==480 && oldSettings.Fps30 && !oldSettings.DownloadFps30 && oldSettings.BufferSeconds==60,"old settings preserve preferences and acquire new defaults");
            Check(oldSettings.Language=="en" && oldSettings.AudioDownloadFormat=="M4A" && oldSettings.AudioBitrate==192,"old settings acquire language and download-dialog defaults");
            var nativeVideo=Json.Object("{\"format_id\":\"298\",\"url\":\"https://cdn.test/video\",\"vcodec\":\"avc1.4D4020\",\"acodec\":\"none\",\"ext\":\"mp4\",\"height\":720,\"fps\":60}");
            Check(Rules.Downloadable(nativeVideo,"MP4",false,true) && !Rules.Downloadable(nativeVideo,"MP4",true,true),"download dialog exposes 720p60 only when allowed");
            Check(!Rules.Downloadable(nativeVideo,"M4A",false,false) && !Rules.Downloadable(nativeVideo,"MP3",false,false),"audio choices exclude video streams");
            var sourceAudio=Json.Object("{\"format_id\":\"140\",\"url\":\"https://cdn.test/audio\",\"vcodec\":\"none\",\"acodec\":\"mp4a.40.2\",\"ext\":\"m4a\"}");
            Check(Rules.Downloadable(sourceAudio,"M4A",false,true) && Rules.Downloadable(sourceAudio,"MP3",false,true) && !Rules.Downloadable(sourceAudio,"Opus",false,true),"audio container compatibility");
            Check(Rules.HasDownloadAudio(new[]{sourceAudio},"MP4") && !Rules.HasDownloadAudio(new[]{sourceAudio},"WebM"),"only compatible separate audio enables a video format");
            nativeVideo["has_drm"]=true;Check(!Rules.Downloadable(nativeVideo,"MP4",false,true),"DRM formats excluded");nativeVideo["has_drm"]=false;
            var exact=new DownloadSelection{Kind="MP4",FormatId="298",Height=720};
            var exactArgs=Rules.DownloadArgs(settings,"MP4",exact);
            Check(exactArgs[exactArgs.IndexOf("-f")+1]=="298+ba[ext=m4a]","chosen native video ID does not fall back to lower quality");
            exact.Combined=true;Check(exact.Selector()=="298","combined source does not add duplicate audio");
            exact.FormatId="298/evil";Reject(delegate{exact.Selector();},"format selector injection rejected");
            var mp3=new DownloadSelection{Kind="MP3",FormatId="140",Bitrate=128};
            var lowAudio=Rules.DownloadArgs(settings,"MP3",mp3);mp3.Bitrate=320;var highAudio=Rules.DownloadArgs(settings,"MP3",mp3);
            Check(highAudio[highAudio.IndexOf("--audio-quality")+1]=="320K" && highAudio[highAudio.IndexOf("-f")+1]=="140","MP3 uses chosen audio stream and requested bitrate");
            Check(lowAudio[lowAudio.IndexOf("--output")+1]!=highAudio[highAudio.IndexOf("--output")+1],"MP3 bitrates do not collide on disk");
            var info=Json.Object("{\"id\":\"abcdefghijk\",\"title\":\"Тест & <відео>\",\"duration\":65,\"requested_formats\":[{\"url\":\"https://cdn.test/video?token=private\",\"vcodec\":\"avc1\",\"acodec\":\"none\"},{\"url\":\"https://cdn.test/audio\",\"vcodec\":\"none\",\"acodec\":\"mp4a\"}]} ");
            var media=Media.From(info,false);Check(media.VideoUrl.Contains("/video") && media.AudioUrl.EndsWith("/audio"),"separate adaptive video and audio");
            var video=Video.From(info);Check(video.Duration=="1:05" && video.Title.Contains("Тест"),"metadata and Ukrainian text");
            var audioInfo=Json.Object("{\"url\":\"https://cdn.test/audio\",\"vcodec\":\"none\",\"acodec\":\"opus\"}");
            var audio=Media.From(audioInfo,true);Check(audio.AudioUrl=="" && audio.VideoUrl.EndsWith("audio"),"audio stream mapping");
            bool rejected=false;try{Media.From(Json.Object("{\"url\":\"file:///private\",\"vcodec\":\"avc1\",\"acodec\":\"none\"}"),false);}catch(InvalidDataException){rejected=true;}Check(rejected,"non-HTTP media URL blocked");
            Check(Media.From(Json.Object("{\"url\":\"https://cdn.test/combined\",\"vcodec\":\"avc1\",\"acodec\":\"mp4a\"}"),true).VideoUrl=="https://cdn.test/combined","combined stream supports audio-only fallback");
            var args=Player.BuildArgs(settings,media,false,"pipe");Check(args.Contains("--hwdec=auto") && args.Contains("--gpu-context=d3d11"),"new installs use automatic hardware decoding and D3D11");
            Check(args.Contains("--ytdl=no") && args.Contains("--no-config") && args.Contains("--load-scripts=no"),"player ignores external config and extraction hooks");
            Check(args.Contains("--cache-secs=60") && args.Contains("--demuxer-max-bytes=64MiB") && args.Contains("--cache-pause-initial=yes"),"default buffer bounded and prefills before playback");
            settings.BufferSeconds=120;Check(Player.BuildArgs(settings,media,false,"p").Contains("--demuxer-max-bytes=128MiB"),"larger buffer remains bounded");
            settings.BufferSeconds=Int32.MaxValue;Check(Player.BuildArgs(settings,media,false,"p").Contains("--cache-secs=60"),"invalid stored buffer uses bounded default");
            settings.BufferSeconds=60;
            Check(args[args.Count-2]=="--" && args.Last()==media.VideoUrl,"media argument after separator");
            var aa=Player.BuildArgs(settings,audio,true,"pipe");Check(aa.Contains("--vid=no") && !aa.Any(x=>x.StartsWith("--audio-file=")),"audio playback downloads only the audio URL");
            Check(aa.Contains("--force-window=no") && aa.Contains("--vo=null") && aa.Contains("--audio-display=no") && !aa.Any(x=>x.StartsWith("--gpu-context=")),"compact audio uses no mpv video window or GPU renderer");
            settings.Renderer="opengl";Check(Player.BuildArgs(settings,media,false,"p").Contains("--gpu-context=win"),"OpenGL alternative");
            settings.Decoder="invalid";Reject(delegate{Player.BuildArgs(settings,media,false,"p");},"invalid decoder rejected");
            Check(!Rules.Redact("failed https://cdn.test/v?token=secret&sig=secret").Contains("secret"),"signed URLs redacted");
            Check(!Rules.Redact("Cookie: SID=secret").Contains("secret"),"cookies redacted");
            string temp=Path.Combine(Path.GetTempPath(),"lowtube-tests-"+Guid.NewGuid().ToString("N"),"settings.json");
            Json.Save(temp,new Settings());Json.Save(temp,new Settings{Height=480});Check(Json.Load(temp,new Settings()).Height==480,"atomic settings replacement");Directory.Delete(Path.GetDirectoryName(temp),true);
            Check(Runner.Run("definitely-missing",new string[0],new CancellationToken(true),10,true,null).IsCanceled,"cancellation before process start");

            Check(settings.Theme=="light" && settings.DiscoveryTopic=="science and technology","fresh theme and discovery defaults");
            var savedLanguage=Json.Serializer().Deserialize<Settings>("{\"Language\":\"uk\",\"BufferSeconds\":120,\"Decoder\":\"no\"}");
            Check(savedLanguage.Language=="uk" && savedLanguage.Theme=="light" && savedLanguage.BufferSeconds==120 && savedLanguage.Decoder=="no","upgrade preserves chosen language, decoder and buffer");
            var darkSettings=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(new Settings{Language="ru",Theme="dark",DiscoveryTopic="music"}));
            Check(darkSettings.Theme=="dark" && darkSettings.Language=="ru" && darkSettings.DiscoveryTopic=="music","theme, language and topic persist together");
            var channel=new LocalChannel{Id="UC_x5XG1OV2P6uZZ5FSM9Ttw",Title="Example & channel"};
            Check(LocalFeeds.ValidChannelId(channel.Id) && !LocalFeeds.ValidChannelId("../secret"),"channel ID validation prevents path traversal");
            Check(LocalFeeds.ChannelInput(channel.Id)=="https://www.youtube.com/channel/"+channel.Id+"/videos","channel ID input");
            Check(LocalFeeds.ChannelInput(" @GoogleDevelopers ")=="https://www.youtube.com/@GoogleDevelopers/videos","handle input normalization");
            Reject(delegate{LocalFeeds.ChannelInput("https://youtube.com.evil.test/@name");},"external channel hosts rejected");
            Check(LocalFeeds.DiscoveryTarget("  ")=="ytsearch100:science and technology" && LocalFeeds.DiscoveryTarget(":ytrec")=="ytsearch100::ytrec","guest topic always becomes a public search");
            Check(LocalFeeds.DiscoveryTarget(new string('x',300)).Length==212,"discovery topic is bounded");
            var channels=new List<LocalChannel>();
            Check(LocalFeeds.Add(channels,channel) && !LocalFeeds.Add(channels,channel) && channels.Count==1,"duplicate local subscriptions do not accumulate");
            var normalized=LocalFeeds.Normalize(new[]{channel,null,channel,new LocalChannel{Id="../bad"}});
            Check(normalized.Count==1 && normalized[0].Title==channel.Title,"saved channel normalization removes invalid and duplicate entries");
            var hundred=Enumerable.Range(0,100).Select(n=>new LocalChannel{Id="UC"+n.ToString("D22"),Title="channel"}).ToList();
            bool atLimit=false;try{LocalFeeds.Add(hundred,channel);}catch(InvalidOperationException){atLimit=true;}Check(atLimit && hundred.Count==100,"channel limit is enforced");
            string header="<feed xmlns='http://www.w3.org/2005/Atom' xmlns:yt='http://www.youtube.com/xml/schemas/2015'><yt:channelId>"+channel.Id+"</yt:channelId>";
            string entry="<entry><yt:videoId>abcdefghijk</yt:videoId><title>One &amp; two</title><published>2026-10-01T12:00:00+00:00</published></entry>";
            string newer="<entry><yt:videoId>01234567890</yt:videoId><title>Newer</title><published>2026-10-02T10:00:00+03:00</published></entry>";
            var feed=LocalFeeds.Parse("\uFEFF"+header+entry+newer+entry+"<entry><yt:videoId>bad</yt:videoId></entry></feed>",channel);
            Check(feed.Count==2 && feed[0].Id=="01234567890" && feed[1].Title=="One & two","Atom feed handles BOM, namespaces, entities, ordering and deduplication");
            Check(LocalFeeds.Parse(header.Replace(channel.Id,channel.Id.Substring(2))+entry+"</feed>",channel).Count==1,"public Atom root channel ID may omit the UC prefix");
            Check(feed.All(v=>v.ChannelId==channel.Id && v.Url=="https://www.youtube.com/watch?v="+v.Id && v.Date.Length>0),"feed rows preserve channel and canonical watch URLs");
            var merged=LocalFeeds.Merge(feed.Concat(new[]{feed[0],new Video{Id="zyxwvutsrqp",Published="2026-10-02T06:30:00Z"},new Video{Id="invalid"}}));
            Check(merged.Count==3 && merged[0].Id=="01234567890" && merged[1].Id=="zyxwvutsrqp","combined feed sorts by absolute time and removes duplicates");
            bool badFeed=false;try{LocalFeeds.Parse("<html>blocked</html>",channel);}catch(InvalidDataException){badFeed=true;}Check(badFeed,"HTML error responses are not accepted as channel feeds");
            badFeed=false;try{LocalFeeds.Parse(header.Replace(channel.Id,"UC0000000000000000000000")+entry+"</feed>",channel);}catch(InvalidDataException){badFeed=true;}Check(badFeed,"mismatched channel feed rejected");
            badFeed=false;try{LocalFeeds.Parse("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///private'>]>"+header+entry+"</feed>",channel);}catch(System.Xml.XmlException){badFeed=true;}Check(badFeed,"external XML entities and DTDs prohibited");
            string feedTemp=Path.Combine(Path.GetTempPath(),"lowtube-feed-"+Guid.NewGuid().ToString("N"),"feed.json");
            Json.Save(feedTemp,feed);Check(LocalFeeds.Merge(Json.Load(feedTemp,new List<Video>())).Count==2,"feed cache JSON roundtrip");Directory.Delete(Path.GetDirectoryName(feedTemp),true);
            bool cancelledFeed=false;try{LocalFeeds.Refresh(channels,new CancellationToken(true),delegate{}).GetAwaiter().GetResult();}catch(OperationCanceledException){cancelledFeed=true;}Check(cancelledFeed,"cancelled subscription refresh starts no requests");

            var manualBuffer=new Settings{BufferSeconds=900,BufferMiB=256,BackBufferMiB=32,BufferStartSeconds=2};
            Check(Rules.CacheSeconds(manualBuffer)==900 && Rules.CacheMiB(manualBuffer)==256 && Rules.BackCacheMiB(manualBuffer)==32 && Rules.StartCacheSeconds(manualBuffer)==2,"manual buffer duration, memory, rewind and startup threshold are independent");
            var customArgs=Player.BuildArgs(manualBuffer,media,false,"pipe",121.25,true,35,1.25);
            Check(customArgs.Contains("--cache-secs=900") && customArgs.Contains("--demuxer-max-bytes=256MiB") && customArgs.Contains("--demuxer-max-back-bytes=32MiB") && customArgs.Contains("--cache-pause-wait=2"),"manual cache values reach mpv");
            Check(customArgs.Contains("--demuxer-donate-buffer=no") && customArgs.Contains("--cache-on-disk=no"),"explicit memory caps and no HDD media cache");
            Check(customArgs.Contains("--start=121.25") && customArgs.Contains("--pause=yes") && customArgs.Contains("--volume=35") && customArgs.Contains("--speed=1.25"),"video settings restart preserves position pause volume and speed");
            Check(customArgs.Contains("--video-sync=audio") && customArgs.Contains("--initial-audio-sync=yes") && customArgs.Contains("--framedrop=vo") && customArgs.Contains("--hr-seek=yes"),"audio clock, initial sync and precise seeking are explicit");
            Check(customArgs.Any(x=>x.StartsWith("--script=") && x.EndsWith("lowtube.lua")) && customArgs.Contains("--load-scripts=no"),"only the bundled video script is explicitly enabled");
            Check(!aa.Any(x=>x.StartsWith("--script=")),"audio-only player has no video-menu script overhead");
            manualBuffer.SafeSeeking=false;Check(Player.BuildArgs(manualBuffer,media,false,"p").Contains("--hr-seek=default"),"safe seeking can be disabled in the player");
            manualBuffer.BufferSeconds=5;manualBuffer.BufferStartSeconds=30;manualBuffer.BufferMiB=2000;manualBuffer.BackBufferMiB=-1;
            Check(Rules.StartCacheSeconds(manualBuffer)==5 && Rules.CacheMiB(manualBuffer)==64 && Rules.BackCacheMiB(manualBuffer)==4,"invalid memory bounds fall back and initial fill cannot exceed duration");
            Check(Rules.CacheMiB(new Settings{BufferSeconds=120})==128 && Rules.CacheMiB(new Settings{BufferSeconds=12})==32,"old preset memory values migrate unchanged");
            var ipc=Json.Object(Json.Serializer().Serialize(new{command=new object[]{"seek",123.25,"absolute+exact"},request_id=7}));
            Check(Json.Str(ipc,"request_id")=="7" && ((object[])ipc["command"]).Length==3,"production serializer preserves IPC anonymous objects and arrays");
            var escaped=Json.Object("{\"text\":\"\\\"\\\\\\n\\t\\u0410\\ud83d\\ude00\",\"n\":-1.25e2,\"b\":true,\"null\":null,\"big\":9007199254740993}");
            var backJson=Json.Object(Json.Serializer().Serialize(escaped));
            Check(Json.Str(backJson,"text")==Json.Str(escaped,"text") && Json.Str(backJson,"n")=="-125" && Object.Equals(backJson["b"],true) && backJson["null"]==null && Json.Str(backJson,"big")=="9007199254740993","production JSON preserves escapes Unicode numbers booleans and null");
            foreach(string invalid in new[]{"{", "{\"x\":01}","[1,]","{\"x\":NaN}","{\"x\":1e9999}","{\"x\":\"\\q\"}","{} trailing",new string('[',152)+new string(']',152)})Reject(delegate{Json.Serializer().Deserialize<object>(invalid);},"malformed or excessive JSON is rejected");
            var unknown=Json.Serializer().Deserialize<Settings>("{\"UnknownFutureOption\":12,\"Height\":480,\"Language\":\"ru\"}");
            Check(unknown.Height==480 && unknown.Language=="ru" && unknown.BufferSeconds==60,"unknown settings fields are ignored while defaults survive");

            var formatRoot=Json.Object(@"{""formats"":[{""format_id"":""a"",""url"":""https://example.com/a"",""vcodec"":""none"",""acodec"":""aac"",""ext"":""m4a""},{""format_id"":""480"",""url"":""https://example.com/480"",""vcodec"":""avc1"",""acodec"":""none"",""height"":480,""fps"":30},{""format_id"":""720"",""url"":""https://example.com/720"",""vcodec"":""avc1"",""acodec"":""none"",""height"":720,""fps"":60},{""format_id"":""1080"",""url"":""https://example.com/1080"",""vcodec"":""av01"",""acodec"":""none"",""height"":1080,""fps"":30}]}");
            var pickSettings=new Settings{Height=720,H264=false,Fps30=false};
            Check(Json.Str(FormatPicker.Select(formatRoot,pickSettings,false),"format_id")=="720+a","cached formats select 720 without extracting again");
            pickSettings.Height=480;
            Check(Json.Str(FormatPicker.Select(formatRoot,pickSettings,false),"format_id")=="480+a","same metadata selects lower quality without exceeding cap");
            pickSettings.Height=720;pickSettings.Fps30=true;
            Check(Json.Str(FormatPicker.Select(formatRoot,pickSettings,false),"format_id")=="480+a","30 FPS limit survives local selection");
            Check(!formatRoot.ContainsKey("requested_formats"),"local selection does not mutate cached metadata");
            Check(Media.From(FormatPicker.Select(formatRoot,pickSettings,true),true).VideoUrl.EndsWith("/a"),"audio-only uses the shared format list");
            pickSettings.Height=240;bool localRejected=false;try{FormatPicker.Select(formatRoot,pickSettings,false);}catch(InvalidOperationException){localRejected=true;}Check(localRejected,"missing low resolution never falls back to maximum");
            var cache=new FormatCache();cache.Put("x",formatRoot,100);Check(cache.Get("x")==formatRoot,"format metadata cache hit");cache.Clear();Check(cache.Get("x")==null,"manual cache refresh removes signed URLs");
            cache.Put("large",formatRoot,5*1024*1024);Check(cache.Get("large")==null,"oversized metadata is not retained");
            var live=new Dictionary<string,object>(formatRoot);live["is_live"]=true;cache.Put("live",live,100);Check(cache.Get("live")==null,"live streams bypass metadata caching");
            string cookies=".youtube.com\tTRUE\t/\tTRUE\t2000000000\tSID\tfake\n.example.com\tTRUE\t/\tTRUE\t2000000000\tSECRET\tother\n";
            string filtered=SavedSession.Filter(cookies);Check(filtered.Contains("SID") && !filtered.Contains("SECRET"),"saved sessions retain only YouTube cookies");
            localRejected=false;try{SavedSession.Filter(".notyoutube.com\tTRUE\t/\tTRUE\t2000000000\tSID\tfake");}catch(InvalidDataException){localRejected=true;}Check(localRejected,"lookalike cookie domains are excluded");
            Check(StartupTrace.Stage("[debug] Command-line config: cookies SECRET https://private.invalid/")==null,"diagnostics discard raw arguments and cookie data");
            Check(StartupTrace.Stage("[youtube] SECRET: Downloading web player API JSON")=="yt-dlp: requesting player API","diagnostics keep stage but discard video identifiers");
            Check(StartupTrace.Stage("WARNING: Retrying https://private.invalid/ SECRET")=="yt-dlp: retry reported","diagnostics never copy retry URLs");
            Check(StartupTrace.Stage("ERROR: HTTP Error 403: SECRET")=="yt-dlp: HTTP 403","diagnostics retain HTTP status without raw error payload");
            Check(StartupTrace.Stage("[youtube] [jsc:quickjs] Solving JS challenges")=="yt-dlp: JavaScript solving started","diagnostics identify JS challenge work");
            Check(StartupTrace.ProcessMonitor.CorePercent(2,2)==100 && StartupTrace.ProcessMonitor.CorePercent(4,2)==200,"CPU percentage is per logical core, not machine-wide");
            Check(StartupTrace.ProcessMonitor.CorePercent(-1,2)==0 && StartupTrace.ProcessMonitor.CorePercent(1,0)==0,"CPU metric handles zero intervals and counter resets");
            Check(StartupTrace.ProcessMonitor.Label("qjs",false)=="QuickJS" && StartupTrace.ProcessMonitor.Label("PRIVATE_PROGRAM",false)=="extractor helper","process labels do not expose arbitrary executable names");
            Check(StartupTrace.Stage("[debug] [jsc:quickjs] Running QuickJS: SECRET_PATH SECRET_ARGUMENT")=="yt-dlp: QuickJS launch command issued","QuickJS launch is distinguished without exposing arguments");
            Check(StartupTrace.Stage("[debug] [jsc] Providers: quickjs")=="yt-dlp: JavaScript provider diagnostic","provider discovery is not labelled as actual JS solving");
            StartupTrace.Begin();var attemptOne=StartupTrace.StartAttempt("test",new Settings(),false);attemptOne.Event("format cache HIT");
            var attemptTwo=StartupTrace.StartAttempt("test",new Settings(),true);attemptTwo.Failure(new OperationCanceledException("SECRET"));
            string timingReport=StartupTrace.Report();Check(timingReport.Contains("attempt #1") && timingReport.Contains("attempt #2") && timingReport.Contains("CANCELLED") && !timingReport.Contains("SECRET"),"attempts are numbered separately and cancellation messages are not leaked");
            Check(timingReport.StartsWith("LowTube 0.11.6"),"diagnostic header matches the patch version");
            if(arguments.Length==1) {
                var selectors=new Dictionary<string,string>();var clean=new Settings();
                selectors["play_h264"]=Rules.PlaybackFormat(clean,false);selectors["audio_only"]=Rules.PlaybackFormat(clean,true);
                foreach(string kind in new[]{"MP4","MKV","WebM","M4A","MP3","Opus"}) {var a=Rules.DownloadArgs(clean,kind);selectors[kind]=a[a.IndexOf("-f")+1];}
                clean.DownloadFps30=true;var limited=Rules.DownloadArgs(clean,"MP4");selectors["MP4_30"]=limited[limited.IndexOf("-f")+1];
                clean.Fps30=false;selectors["play_60"]=Rules.PlaybackFormat(clean,false);
                var output=Rules.DownloadArgs(clean,"MP4");selectors["_output_template"]=output[output.IndexOf("--output")+1];
                foreach(var pick in new[]{new DownloadSelection{Kind="MP4",FormatId="v720_60"},new DownloadSelection{Kind="MKV",FormatId="v720_60"},new DownloadSelection{Kind="WebM",FormatId="webm720"},new DownloadSelection{Kind="M4A",FormatId="aac"},new DownloadSelection{Kind="MP3",FormatId="aac"},new DownloadSelection{Kind="Opus",FormatId="251"}}) {
                    var chosen=Rules.DownloadArgs(clean,pick.Kind,pick);selectors["_exact_"+pick.Kind]=chosen[chosen.IndexOf("-f")+1];
                }
                File.WriteAllText(arguments[0],Json.Serializer().Serialize(selectors));
            }
            Console.WriteLine("TOTAL: "+passed+" checks passed");return 0;
        }catch(Exception e){Console.Error.WriteLine(e);return 1;}
    }
}
