using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using LowTube;
public static class ContinuityTests {
    static int count;
    static void Check(bool value,string label){if(!value)throw new Exception(label);count++;Console.WriteLine("PASS: "+label);}
    sealed class TestContext : SynchronizationContext {
        readonly System.Collections.Concurrent.BlockingCollection<Action> callbacks=new System.Collections.Concurrent.BlockingCollection<Action>();
        public override void Post(SendOrPostCallback callback,object state){callbacks.Add(()=>callback(state));}
        public void Pump(Task task){while(!task.IsCompleted){Action next;if(callbacks.TryTake(out next,100))next();}task.GetAwaiter().GetResult();}
    }
    public static int Main(){var previous=SynchronizationContext.Current;try{var context=new TestContext();SynchronizationContext.SetSynchronizationContext(context);context.Pump(Run());Console.WriteLine("TOTAL: "+count+" continuity checks passed");return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}finally{SynchronizationContext.SetSynchronizationContext(previous);}}
    static async Task Run(){
        L.Set("en");string url="https://youtu.be/CdEpmU9pRaE",key=ResumeRules.Key(url);
        var budget=new RecoveryBudget();Check(budget.Take() && budget.Take() && !budget.Take() && !budget.Take(),"recovery stops after two attempts");budget.Reset();Check(budget.Take(),"manual playback grants a new bounded recovery budget");
        Check(RecoveryBudget.Premature(200,600,false) && !RecoveryBudget.Premature(590,600,false),"early EOF is separated from a normal end");
        Check(!RecoveryBudget.Premature(200,600,true) && !RecoveryBudget.Premature(-1,600,false) && !RecoveryBudget.Premature(0,0,false),"live and unknown positions do not trigger early EOF recovery");
        var watched=new Dictionary<string,WatchedVideo>();WatchedRules.Record(watched,url,150,600,false);Check(WatchedRules.Fraction(watched[key])==0.25,"thumbnail fraction matches elapsed time");
        WatchedRules.Record(watched,url,600,600,false,true);Check(WatchedRules.Fraction(watched[key])==1,"completed progress remains visible");
        var saved=Json.Serializer().Deserialize<List<WatchedVideo>>(Json.Serializer().Serialize(watched.Values.ToList()));Check(saved[0].Complete && WatchedRules.Fraction(saved[0])==1,"completed state survives a restart");
        WatchedRules.Record(watched,url,Double.NaN,600,false);Check(watched[key].Complete,"invalid samples preserve completed state");
        WatchedRules.Record(watched,url,20,600,true);Check(watched[key].Complete,"live stream does not overwrite watched VOD state");
        WatchedRules.Record(watched,url,0,600,false);Check(!watched[key].Complete && WatchedRules.Fraction(watched[key])==0,"replay starts a new progress bar");
        WatchedRules.Record(watched,url,150,0,false);Check(watched[key].Duration==600,"temporary unknown duration preserves known progress scale");
        WatchedRules.Record(watched,"https://example.invalid/",20,100,false);Check(watched.Count==1,"foreign URLs are ignored");
        var local=new LocalPlaylist{Name=LocalPlaylistRules.Name("  Music  "),Items=new List<Video>{new Video{Url=url,Title="Title",Channel="Channel"},new Video{Url=url,Title="Second"}}};
        var playlists=LocalPlaylistRules.Clean(Json.Serializer().Deserialize<List<LocalPlaylist>>(Json.Serializer().Serialize(new[]{local})));
        Check(playlists.Count==1 && playlists[0].Name=="Music" && playlists[0].Items[0].Title=="Title","local playlists persist names, order and metadata");
        QueueRules.Move(playlists[0].Items,0,1);Check(playlists[0].Items[0].Title=="Second","playlist reordering is independent of watch queue");
        bool rejected=false;try{LocalPlaylistRules.Name("\r\n");}catch(ArgumentException){rejected=true;}Check(rejected,"empty playlist name is rejected");
        rejected=false;try{LocalPlaylistRules.Name(new String('a',101));}catch(ArgumentException){rejected=true;}Check(rejected,"playlist names are bounded");
        local.Items.Add(new Video{Url="https://example.invalid/private"});Check(LocalPlaylistRules.Clean(new[]{local})[0].Items.Count==2,"loaded playlists exclude foreign URLs");
        var duplicate=new LocalPlaylist{Id=local.Id,Name="Other"};playlists=LocalPlaylistRules.Clean(new[]{local,duplicate});Check(playlists[0].Id!=playlists[1].Id,"duplicate persisted IDs are repaired");
        var s=new Settings{DownloadLimitKiB=768};var args=Rules.DownloadArgs(s,"MP4");Check(args[args.IndexOf("--limit-rate")+1]=="768K","download rate uses KiB/s for yt-dlp");
        s.DownloadLimitKiB=0;Check(!Rules.DownloadArgs(s,"MP4").Contains("--limit-rate"),"zero rate is unlimited");s.DownloadLimitKiB=-1;Check(!Rules.DownloadArgs(s,"MP4").Contains("--limit-rate"),"corrupt negative rate remains safe");
        s.DownloadLimitKiB=Int32.MaxValue;args=Rules.DownloadArgs(s,"M4A");Check(args[args.IndexOf("--limit-rate")+1]=="1048576K","audio jobs share the bounded rate cap");
        var cache=new FormatCache();cache.Put("a",Json.Object("{\"value\":1}"),100);cache.Put("b",Json.Object("{\"value\":2}"),100);cache.Remove("a");Check(cache.Get("a")==null && cache.Get("b")!=null,"stream recovery invalidates only the failing URL cache");
        s.CookiesFile="PRIVATE_COOKIE_PATH";s.FirefoxProfile="PRIVATE_PROFILE";s.DownloadFolder="PRIVATE_FOLDER";s.Auth="PRIVATE_AUTH";
        var sample=Json.Object("{\"time-pos\":123,\"frame-drop-count\":7,\"hwdec-current\":\"dxva2-copy\",\"url\":\"PRIVATE_URL\",\"title\":\"PRIVATE_TITLE\",\"cookie\":\"PRIVATE_COOKIE\",\"video-codec\":\"https://host/PRIVATE_SECRET\"}");
        string report=DiagnosticReport.Build(s,sample,new[]{"12:00:00.000 recovery succeeded","https://host/PRIVATE_TOKEN"},2,3,"mpv: 0.41.0");
        Check(!report.Contains("PRIVATE_"),"export excludes account data, URLs, titles and malicious metric values");
        Check(report.Contains("frame-drop-count=7") && report.Contains("dxva2-copy") && report.Contains("recovery succeeded"),"export retains actionable metrics and recovery events");
        // A running job keeps its original rate; the next queued job reads the updated setting.
        string dir=Path.Combine(Path.GetTempPath(),"lowtube-rate-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(dir);
        try{
            var rates=new List<int>();var started=new TaskCompletionSource<bool>();var finish=new TaskCompletionSource<bool>();var current=new Settings{DownloadLimitKiB=100};
            var manager=new DownloadManager(Path.Combine(dir,"jobs.json"),()=>current,e=>{throw e;},async(settings,job,ct,progress)=>{rates.Add(settings.DownloadLimitKiB);if(rates.Count==1){started.SetResult(true);await finish.Task;}ct.ThrowIfCancellationRequested();});
            manager.Add(url,"first",dir,new DownloadSelection{Kind="M4A",FormatId="140"});await started.Task;current.DownloadLimitKiB=200;manager.Add(url,"second",dir,new DownloadSelection{Kind="M4A",FormatId="140"});finish.SetResult(true);
            for(int i=0;i<200 && (manager.Jobs[1].State!="Completed" || manager.Busy);i++)await Task.Delay(10);
            Check(rates.SequenceEqual(new[]{100,200}),"rate changes apply to the next job without silently restarting active jobs");await manager.Shutdown();
        }finally{Directory.Delete(dir,true);}
    }
}
