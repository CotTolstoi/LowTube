using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using LowTube;

public static class InnerTubeTests {
    static int checks;
    const string Id="CdEpmU9pRaE",Channel="UCabcdefghijklmnopqrstuv";
    static Dictionary<string,object> Obj(params object[] pairs){var result=new Dictionary<string,object>();for(int i=0;i<pairs.Length;i+=2)result[(string)pairs[i]]=pairs[i+1];return result;}
    static void Check(bool condition,string label){if(!condition)throw new Exception(label);checks++;Console.WriteLine("PASS: "+label);}
    static object Video(string id=Id){return Obj("videoRenderer",Obj("videoId",id,"title",Obj("runs",new[]{Obj("text","Title {escaped} & Unicode")}),"lengthText",Obj("simpleText","2:30"),"ownerText",Obj("runs",new[]{Obj("text","Channel","navigationEndpoint",Obj("browseEndpoint",Obj("browseId",Channel)))}),"publishedTimeText",Obj("simpleText","2 days ago")));}
    static object Token(string token){return Obj("continuationItemRenderer",Obj("continuationEndpoint",Obj("continuationCommand",Obj("token",token))));}
    static string Initial(params object[] rows){return Json.Serializer().Serialize(Obj("contents",Obj("twoColumnBrowseResultsRenderer",Obj("tabs",new[]{Obj("tabRenderer",Obj("selected",true,"content",Obj("richGridRenderer",Obj("contents",rows))))}))));}
    static string Search(params object[] rows){return Json.Serializer().Serialize(Obj("contents",Obj("twoColumnSearchResultsRenderer",Obj("primaryContents",Obj("sectionListRenderer",Obj("contents",rows))))));}
    static string Next(params object[] rows){return Json.Serializer().Serialize(Obj("onResponseReceivedActions",new[]{Obj("appendContinuationItemsAction",Obj("continuationItems",rows))}));}
    static string Config(bool login=false){return "<script>ytcfg.set("+Json.Serializer().Serialize(Obj("INNERTUBE_CLIENT_VERSION","2.fixture.00.00","VISITOR_DATA","VISITOR_FIXTURE","LOGGED_IN",login,"SESSION_INDEX",2,"DELEGATED_SESSION_ID","BRAND_FIXTURE"))+");</script>";}
    static string Player(){return "<script>var ytInitialPlayerResponse = "+Json.Serializer().Serialize(Obj("videoDetails",Obj("videoId",Id,"title","Title","author","Author","channelId",Channel,"lengthSeconds","150"),"microformat",Obj("playerMicroformatRenderer",Obj("publishDate","2025-04-06")),"streamingData",Obj("formats",new[]{Obj("url","PRIVATE_SIGNED_STREAM")})))+";</script>";}
    static async Task Reject(Func<Task> action,string label,string contains=""){
        bool rejected=false;try{await action();}catch(Exception e){rejected=contains.Length==0 || e.Message.Contains(contains);}
        Check(rejected,label);
    }
    public static int Main(string[] args){try{Run().GetAwaiter().GetResult();if(args.Contains("--public"))Public().GetAwaiter().GetResult();Console.WriteLine("TOTAL: "+checks+" InnerTube checks passed");return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}}
    static async Task Run(){
        L.Set("en");
        var embedded=InnerTubeJson.Embedded("ytcfg.set({\"text\":\"} \\\" {\",\"nested\":{\"x\":1}});ignored();","ytcfg.set(");
        Check(Json.Str(embedded,"text")=="} \" {" && InnerTubeJson.StringAt(embedded,"nested","x")=="1","balanced JSON extraction handles braces and escaped quotes");
        embedded=InnerTubeJson.Embedded("function example(){ytcfg.set(argument);if(value){doSomething();}}"+Config(),"ytcfg.set(");
        Check(Json.Str(embedded,"INNERTUBE_CLIENT_VERSION")=="2.fixture.00.00","configuration extraction skips JS calls with nonliteral arguments");
        var page=InnerTubeJson.Parse(Json.Object(Search(Video(),Token("NEXT"))));
        Check(page.Recognized && page.Items.Count==1 && page.Continuation=="NEXT","search renderer and continuation parsed");
        Check(page.Items[0].ChannelId==Channel && page.Items[0].Duration=="2:30" && page.Items[0].Date=="2 days ago" && page.Items[0].Published=="","relative date is displayed without fabricating an exact publication time");
        var mixed=Json.Object(Initial(Video()));
        mixed["header"]=Obj("videoRenderer",Obj("videoId","aaaaaaaaaaa"));mixed["sidebar"]=Obj("continuationItemRenderer",Obj("continuationEndpoint",Obj("continuationCommand",Obj("token","WRONG"))));
        page=InnerTubeJson.Parse(mixed);Check(page.Items.Count==1 && page.Items[0].Id==Id && page.Continuation=="","headers and sidebars cannot inject entries or continuation tokens");
        page=InnerTubeJson.Parse(Json.Object(Initial(Obj("adSlotRenderer",Video()),Video())));
        Check(page.Items.Count==1,"advertisements are excluded");
        var privateRow=Obj("playlistVideoRenderer",Obj("videoId","aaaaaaaaaaa","isPlayable",false,"title",Obj("simpleText","[Private video]")));
        page=InnerTubeJson.Parse(Json.Object(Initial(privateRow,Video(),Video())));
        Check(page.Items.Count==3 && page.Items[0]==null && page.Items[1].Id==page.Items[2].Id,"unavailable playlist slots and legitimate repeated videos keep their positions");
        var lockup=Obj("lockupViewModel",Obj("contentId",Id,"contentType","LOCKUP_CONTENT_TYPE_VIDEO","metadata",Obj("lockupMetadataViewModel",Obj("title",Obj("content","Modern title"),"metadata",Obj("contentMetadataViewModel",Obj("metadataRows",new[]{Obj("metadataParts",new[]{Obj("text",Obj("content","Modern channel","commandRuns",new[]{Obj("onTap",Obj("innertubeCommand",Obj("browseEndpoint",Obj("browseId",Channel))))})),Obj("text",Obj("content","3 days ago"))})})))),"contentImage",Obj("thumbnailViewModel",Obj("overlays",new[]{Obj("thumbnailBadgeViewModel",Obj("text","1:02:03"))}))));
        page=InnerTubeJson.Parse(Json.Object(Initial(lockup)));
        Check(page.Items.Count==1 && page.Items[0].Title=="Modern title" && page.Items[0].ChannelId==Channel && page.Items[0].Duration=="1:02:03","modern lockup metadata and duration parsed");
        var shorts=Obj("shortsLockupViewModel",Obj("onTap",Obj("innertubeCommand",Obj("reelWatchEndpoint",Obj("videoId",Id))),"overlayMetadata",Obj("primaryText",Obj("content","Short title"))));
        page=InnerTubeJson.Parse(Json.Object(Initial(shorts)));Check(page.Items.Single().Kind=="shorts" && page.Items[0].Url.Contains("/shorts/"),"modern Shorts retain their content kind");
        var live=Video();InnerTubeJson.Map(InnerTubeJson.At(live,"videoRenderer"))["badges"]=new[]{Obj("metadataBadgeRenderer",Obj("style","BADGE_STYLE_TYPE_LIVE_NOW"))};
        page=InnerTubeJson.Parse(Json.Object(Initial(live)));Check(page.Items.Single().LiveStatus=="is_live" && page.Items[0].Kind=="live","live badge is distinguished from ordinary video");
        var metadata=InnerTubeJson.Metadata(InnerTubeJson.Embedded(Player(),"ytInitialPlayerResponse ="));
        Check(LowTube.Video.From(metadata).Published=="2025-04-06T00:00:00Z" && !Json.Serializer().Serialize(metadata).Contains("PRIVATE_SIGNED_STREAM") && !metadata.ContainsKey("formats"),"metadata enrichment returns exact dates without retaining stream URLs");
        var jar=InnerTubeClient.CookieJar(".youtube.com\tTRUE\t/\tTRUE\t2000000000\tSAPISID\tfixture\n.youtube.com\tTRUE\t/\tTRUE\t1\tEXPIRED\tbad\n#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tfixture\n.evil.invalid\tTRUE\t/\tTRUE\t0\tSECRET\tbad",new DateTime(2026,1,1,0,0,0,DateTimeKind.Utc));
        Check(jar.GetCookies(new Uri("https://www.youtube.com/"))["EXPIRED"]==null && jar.GetCookies(new Uri("https://evil.invalid/")).Count==0 && jar.GetCookies(new Uri("https://www.youtube.com/"))["SID"].HttpOnly,"cookie transport filters domains, expiry and HttpOnly cookies");
        Check(InnerTubeClient.Authorization(jar,123)=="SAPISIDHASH 123_b154e7690a07400f0fd3c38d9d71ba41fe6c7f25","authorization hash uses timestamp, cookie and fixed YouTube origin");
        int apiCalls=0,boot=0;var sent=new List<string>();
        var client=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>{
            Check(request.Headers.Keys.All(x=>x!="Authorization") && request.Cookies.GetCookies(new Uri("https://www.youtube.com/")).Count==0,"guest requests contain no account credentials");
            if(request.Body==null){boot++;return Task.FromResult(Config());}
            var body=Json.Object(request.Body);sent.Add(request.Path);apiCalls++;
            Check(InnerTubeJson.StringAt(body,"context","client","clientVersion")=="2.fixture.00.00","API version comes from web configuration");
            if(apiCalls==1){Check(Json.Str(body,"query")=="hello","ytsearch target is translated into API search");return Task.FromResult(Search(Video(),Token("A")));}
            Check(Json.Str(body,"continuation")=="A" && request.Path.Contains("/search?"),"continuation uses the search endpoint and opaque token");
            return Task.FromResult(Next(Video("aaaaaaaaaaa"),Video("bbbbbbbbbbb")));
        });
        var rows=await client.Browse("ytsearch100:hello",0,2,false,CancellationToken.None,null);
        Check(rows.Count==2 && rows[1].Id=="aaaaaaaaaaa" && apiCalls==2,"UI page fills across native API page boundaries");
        rows=await client.Browse("ytsearch100:hello",1,2,false,CancellationToken.None,null);
        Check(rows.Count==1 && rows[0].Id=="bbbbbbbbbbb" && apiCalls==2 && boot==1,"overflow rows and previous configuration are reused for the next page");
        rows=await client.Browse("ytsearch100:hello",0,2,false,CancellationToken.None,null);Check(apiCalls==2 && rows.Count==2,"backward paging does not replay the continuation chain");
        rows=await client.Browse("ytsearch100:hello",Int32.MaxValue,250,false,CancellationToken.None,null);Check(rows.Count==0 && apiCalls==2,"large page offsets cannot overflow into an earlier page");
        var cancel=new CancellationTokenSource();cancel.Cancel();await Reject(()=>client.Browse("ytsearch100:hello",0,2,false,cancel.Token,null),"pre-cancelled requests do not reach transport");
        int repeats=0;
        var repeat=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>Task.FromResult(request.Body==null?Config():++repeats==1?Search(Video(),Token("LOOP")):Next(Video(),Token("LOOP"))));
        await Reject(()=>repeat.Browse("ytsearch10:loop",0,3,false,CancellationToken.None,null),"repeated tokens terminate with a visible diagnostic","repeated");
        Check(repeats==2,"repeated tokens cannot cause an unbounded loop");
        var unsupported=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>Task.FromResult(request.Body==null?Config():"{\"unexpected\":true}"));
        await Reject(()=>unsupported.Browse("ytsearch1:x",0,1,false,CancellationToken.None,null),"unknown API schema fails visibly","unsupported");
        var unknownTab=Obj("contents",Obj("twoColumnBrowseResultsRenderer",Obj("tabs",new[]{Obj("tabRenderer",Obj("selected",true,"content",Obj("unknownRenderer",Obj("items",new object[0]))))})));
        Check(!InnerTubeJson.Parse(unknownTab).Recognized,"unknown selected tab is not reported as an empty successful list");
        int capped=0;
        var emptyChain=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>Task.FromResult(request.Body==null?Config():++capped==1?Search(Token("T1")):Next(Token("T"+capped))));
        await Reject(()=>emptyChain.Browse("ytsearch1:empty-chain",0,1,false,CancellationToken.None,null),"changing empty continuations stop at the request budget","request limit");
        Check(capped==64,"continuation request budget is finite even without repeated tokens");
        int refreshed=0;
        var refresh=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>Task.FromResult(request.Body==null?Config():Search(Video(++refreshed==1?Id:"aaaaaaaaaaa"))));
        await refresh.Browse("ytsearch1:refresh",0,1,false,CancellationToken.None,null);
        rows=await refresh.Browse("ytsearch1:refresh",0,1,false,CancellationToken.None,null,true);
        Check(rows[0].Id=="aaaaaaaaaaa" && refreshed==2,"explicit head refresh bypasses continuation cache");
        int longCalls=0;
        var longList=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());longCalls++;var body=Json.Object(request.Body);
            int native=body.ContainsKey("continuation")?Int32.Parse(Json.Str(body,"continuation")):0;var entries=new List<object>();
            for(int i=0;i<100;i++){var entry=Video();InnerTubeJson.Map(InnerTubeJson.At(entry,"videoRenderer"))["title"]=Obj("simpleText","row "+(native*100+i));entries.Add(entry);}
            if(native<50)entries.Add(Token((native+1).ToString()));return Task.FromResult(native==0?Search(entries.ToArray()):Next(entries.ToArray()));
        });
        rows=await longList.Browse("ytsearch:long",20,250,false,CancellationToken.None,null);
        Check(rows.Count==100 && rows[0].Title=="row 5000","long lists continue beyond the retained 5000-row cache window");
        rows=await longList.Browse("ytsearch:long",19,250,false,CancellationToken.None,null);Check(rows[0].Title=="row 4750" && longCalls==51,"retained pages before a deep page remain available without requests");
        rows=await longList.Browse("ytsearch:long",0,250,false,CancellationToken.None,null);Check(rows[0].Title=="row 0" && longCalls==54,"evicted earlier pages rebuild from the initial endpoint");
        var consent=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>Task.FromResult("<html>consent</html>"));
        await Reject(()=>consent.Browse("ytsearch1:x",0,1,false,CancellationToken.None,null),"consent page does not become an empty successful list","configuration");
        int playlistCalls=0;
        var playlistClient=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());playlistCalls++;
            if(playlistCalls==1){Check(Json.Str(Json.Object(request.Body),"browseId")=="VLPLfixture","playlist ID maps to browse endpoint");return Task.FromResult(Initial(privateRow,Video(),Token("PLAYLIST_NEXT")));}
            return Task.FromResult(Next(Video("bbbbbbbbbbb")));
        });
        var playlist=await playlistClient.Playlist("https://www.youtube.com/playlist?list=PLfixture",0,2,false,CancellationToken.None,null);
        Check(playlist.Items.Count==1 && playlist.HasMore,"private playlist entry does not hide continuation");
        playlist=await playlistClient.Playlist("https://www.youtube.com/playlist?list=PLfixture",1,2,false,CancellationToken.None,null);
        Check(playlist.Items.Single().Id=="bbbbbbbbbbb" && !playlist.HasMore,"playlist page boundaries count unavailable slots");
        var watch=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>Task.FromResult(request.Path.StartsWith("/watch?")?Player():Config()));
        metadata=await watch.Metadata("https://youtu.be/"+Id,CancellationToken.None,null);Check(Json.Str(metadata,"channel_id")==Channel,"metadata-only lookup uses bounded watch HTML without player API");
        var handle=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());
            if(request.Path.Contains("resolve_url"))return Task.FromResult(Json.Serializer().Serialize(Obj("endpoint",Obj("browseEndpoint",Obj("browseId",Channel,"params","TAB_PARAMS")))));
            return Task.FromResult(Json.Serializer().Serialize(Obj("metadata",Obj("channelMetadataRenderer",Obj("title","Channel title")))));
        });
        var resolved=await handle.ResolveChannel("@fixture",CancellationToken.None,null);Check(resolved.Id==Channel && resolved.Title=="Channel title","channel handle resolves through navigation and browse");
        string directory=Path.Combine(Path.GetTempPath(),"lowtube-innertube-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
        try{
            string file=Path.Combine(directory,"synthetic-cookies.txt");File.WriteAllText(file,".youtube.com\tTRUE\t/\tTRUE\t2000000000\tSAPISID\tfixture\n");
            int authCalls=0;
            var signed=new InnerTubeClient(new Settings{Auth="file",CookiesFile=file},true,"en",(request,ct)=>{
                if(request.Body==null)return Task.FromResult(Config(true));authCalls++;
                Check(request.Headers.ContainsKey("Authorization") && request.Headers["X-Goog-AuthUser"]=="2" && request.Headers["X-Goog-PageId"]=="BRAND_FIXTURE","signed requests retain account and brand identity");
                if(authCalls==1)return Task.FromResult(Initial(Obj("channelRenderer",Obj("channelId",Channel,"title",Obj("simpleText","Subscribed"))),Token("CHANNELS_NEXT")));
                return Task.FromResult(Next(Obj("gridChannelRenderer",Obj("channelId","UCzyxwvutsrqponmlkjihgfe","title",Obj("simpleText","Second")))));
            });
            var channels=await signed.SessionChannels(CancellationToken.None,null);Check(channels.Count==2 && authCalls==2,"subscription channel synchronization follows continuations");
            channels[0].Title="Changed by UI";channels=await signed.SessionChannels(CancellationToken.None,null);Check(channels[0].Title=="Subscribed" && authCalls==2,"returned channels are isolated from the session cache");
            var expired=new InnerTubeClient(new Settings{Auth="file",CookiesFile=file},true,"en",(request,ct)=>Task.FromResult(Config(false)));
            await Reject(()=>expired.Browse(":ythistory",0,25,true,CancellationToken.None,null),"expired session cannot return guest history","did not accept");
            var loggedOut=new InnerTubeClient(new Settings{Auth="file",CookiesFile=file},true,"en",(request,ct)=>Task.FromResult(request.Body==null?Config(true):"{\"responseContext\":{\"mainAppWebResponseContext\":{\"loggedOut\":true}}}"));
            await Reject(()=>loggedOut.Browse(":ytsubs",0,25,true,CancellationToken.None,null),"API rejection of a previously accepted session remains visible","did not accept");
        }finally{Directory.Delete(directory,true);}
        int running=0,max=0;
        Func<InnerTubeRequest,CancellationToken,Task<string>> delayed=async(request,ct)=>{
            max=Math.Max(max,Interlocked.Increment(ref running));try{await Task.Delay(30,ct);return request.Body==null?Config():Search(Video());}finally{Interlocked.Decrement(ref running);}
        };
        await Task.WhenAll(new InnerTubeClient(new Settings(),false,"en",delayed).Browse("ytsearch1:a",0,1,false,CancellationToken.None,null),new InnerTubeClient(new Settings(),false,"en",delayed).Browse("ytsearch1:b",0,1,false,CancellationToken.None,null));
        Check(max==1,"multiple clients keep one catalogue request active at a time");
        var slow=new InnerTubeClient(new Settings(),false,"en",async(request,ct)=>{await Task.Delay(30000,ct);return Config();});
        using(var during=new CancellationTokenSource(50))await Reject(()=>slow.Browse("ytsearch1:x",0,1,false,during.Token,null),"in-flight request honors cancellation");
        rows=await client.Browse("ytsearch100:hello",0,2,false,CancellationToken.None,null);Check(rows.Count==2,"cancellation releases the catalogue operation gate");
    }
    static async Task Public(){
        // Explicit opt-in. Public YouTube only; never load user settings or sessions.
        var backend=new Backend(new Settings{Auth="none",AuthMedia=false});
        var rows=await backend.Browse("ytsearch100:NASA",0,false,CancellationToken.None,null,false,5);
        Check(rows.Count>0 && rows.All(x=>x.Url.StartsWith("https://www.youtube.com/")),"PUBLIC: search through the integrated Backend");
        var next=await backend.Browse("ytsearch100:NASA",1,false,CancellationToken.None,null,false,25);
        Check(next.Count>0,"PUBLIC: search continuation across native result pages");
        var md=await backend.VideoMetadata("https://www.youtube.com/watch?v=jfKfPfyJRdk",CancellationToken.None,null);
        Check(Json.Str(md,"id")=="jfKfPfyJRdk","PUBLIC: watch metadata without stream extraction");
        var channel=await backend.ResolveChannel("@NASA",CancellationToken.None,null);Check(LocalFeeds.ValidChannelId(channel.Id),"PUBLIC: channel handle resolution");
        rows=await backend.Browse(ContentFeeds.ChannelTarget(channel.Id,"video"),0,false,CancellationToken.None,null,true,5);Check(rows.Count>0,"PUBLIC: channel videos");
        rows=await backend.Browse(ContentFeeds.SearchTarget("NASA","playlist"),0,false,CancellationToken.None,null,false,5);
        var list=rows.FirstOrDefault(x=>x.Kind=="playlist");Check(list!=null,"PUBLIC: playlist search filter and modern playlist cards");
        var playlist=await backend.PlaylistPage(list.Url,0,5,false,CancellationToken.None,null);Check(playlist.Items.Count>0,"PUBLIC: playlist entries");
        rows=await backend.Browse(ContentFeeds.ChannelTarget(channel.Id,"shorts"),0,false,CancellationToken.None,null,true,5);Check(rows.Count>0 && rows.All(x=>x.Kind=="shorts"),"PUBLIC: channel Shorts");
        rows=await backend.Browse(ContentFeeds.ChannelTarget(channel.Id,"live"),0,false,CancellationToken.None,null,true,5);Check(rows.Count>0,"PUBLIC: channel streams");
    }
}
