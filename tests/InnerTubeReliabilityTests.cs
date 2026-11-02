using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using LowTube;

public static class InnerTubeReliabilityTests {
    static int count;
    const string Id="CdEpmU9pRaE",Channel="UCabcdefghijklmnopqrstuv";
    static Dictionary<string,object> Obj(params object[] pairs){var result=new Dictionary<string,object>();for(int i=0;i<pairs.Length;i+=2)result[(string)pairs[i]]=pairs[i+1];return result;}
    static string Encode(object data){return Json.Serializer().Serialize(data);}
    static object Video(string id=Id){return Obj("videoRenderer",Obj("videoId",id,"title",Obj("simpleText","Original")));}
    static object Token(string token){return Obj("continuationItemRenderer",Obj("continuationEndpoint",Obj("continuationCommand",Obj("token",token))));}
    static string Search(params object[] rows){return Encode(Obj("contents",Obj("twoColumnSearchResultsRenderer",Obj("primaryContents",Obj("sectionListRenderer",Obj("contents",rows))))));}
    static string Next(params object[] rows){return Encode(Obj("onResponseReceivedActions",new[]{Obj("appendContinuationItemsAction",Obj("continuationItems",rows))}));}
    static string Config(){return "ytcfg.set("+Encode(Obj("INNERTUBE_CLIENT_VERSION","fixture","LOGGED_IN",false))+");";}
    static void Check(bool value,string label){if(!value)throw new Exception(label);count++;Console.WriteLine("PASS: "+label);}
    static async Task<Exception> Failure(Func<Task> action){try{await action();}catch(Exception error){return error;}throw new Exception("Expected request to fail");}
    static InnerTubeClient Client(Func<InnerTubeRequest,CancellationToken,Task<string>> send,InnerTubeBackoff backoff=null){return new InnerTubeClient(new Settings(),false,"en",send,backoff??new InnerTubeBackoff());}
    public static int Main(){try{Run().GetAwaiter().GetResult();Console.WriteLine("TOTAL: "+count+" InnerTube reliability checks passed");return 0;}catch(Exception error){Console.Error.WriteLine(error);return 1;}}
    static async Task Run(){
        L.Set("en");var now=new DateTime(2026,10,15,12,0,0,DateTimeKind.Utc);
        var pause=new InnerTubeBackoff(()=>now);pause.Observe(InnerTubeException.Http(429,"90"));
        Check(pause.RemainingSeconds==90,"429 respects numeric Retry-After");
        now=now.AddMilliseconds(500);Check(pause.RemainingSeconds==90,"displayed retry delay rounds up");
        var error=await Failure(()=>{pause.ThrowIfPaused();return Task.FromResult(0);});
        Check(error is InnerTubeException && ((InnerTubeException)error).Kind==InnerTubeFailure.Cooldown && !error.Message.Contains("Retry-After"),"cooldown provides a fixed safe diagnostic");
        pause.Observe(InnerTubeException.Http(503,"1"));Check(pause.RemainingSeconds==90,"a shorter later response cannot reduce the active pause");
        now=now.AddSeconds(91);Check(pause.RemainingSeconds==0,"expired pause requires no timer or background request");
        pause.Observe(InnerTubeException.Http(503,now.AddSeconds(45).ToString("r",CultureInfo.InvariantCulture)));Check(pause.RemainingSeconds==45,"503 respects HTTP-date Retry-After");
        now=now.AddMinutes(1);pause.Observe(InnerTubeException.Http(429,"PRIVATE_INVALID_HEADER"));
        Check(pause.RemainingSeconds==60,"invalid retry headers use a bounded default");
        now=now.AddMinutes(2);pause.Observe(InnerTubeException.Http(429,Int64.MaxValue.ToString(CultureInfo.InvariantCulture)));Check(pause.RemainingSeconds==86400,"extreme server delays cannot overflow the cooldown");
        var noPause=new InnerTubeBackoff(()=>now);noPause.Observe(InnerTubeException.Http(404,"999"));Check(noPause.RemainingSeconds==0,"an unavailable individual list does not pause the whole catalogue");
        var past=new InnerTubeBackoff(()=>now);past.Observe(InnerTubeException.Http(503,now.AddDays(-1).ToString("r",CultureInfo.InvariantCulture)));Check(past.RemainingSeconds==1,"past server dates still prevent an immediate repeated request");

        int heads=0,next=0;
        var resumable=Client((request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());var body=Json.Object(request.Body);
            if(!body.ContainsKey("continuation")){heads++;return Task.FromResult(Search(Video(),Token("NEXT")));}
            if(++next==1)throw new WebException("PRIVATE_NETWORK_DETAIL");return Task.FromResult(Next(Video("aaaaaaaaaaa")));
        });
        error=await Failure(()=>resumable.Browse("ytsearch:resume",0,2,false,CancellationToken.None,null,true));
        Check(error is InnerTubeException && ((InnerTubeException)error).CanResume && !error.ToString().Contains("PRIVATE_NETWORK_DETAIL"),"transport failures omit raw network details and permit manual continuation");
        var rows=await resumable.Browse("ytsearch:resume",0,2,false,CancellationToken.None,null,true);
        Check(heads==1 && next==2 && rows.Count==2 && rows[0].Id==Id && rows[1].Id=="aaaaaaaaaaa","first-page retry resumes after the last validated native page without duplicating it");
        rows[0].Title="Changed by UI";rows[0].Kind="playlist";rows[0].Published="wrong";
        rows=await resumable.Browse("ytsearch:resume",0,2,false,CancellationToken.None,null);
        Check(rows[0].Title=="Original" && rows[0].Kind=="video" && rows[0].Published=="","returned video objects cannot mutate cached catalogue entries");

        int outageHeads=0,outageNext=0;var outagePause=new InnerTubeBackoff(()=>now);
        var outage=Client((request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());
            if(!Json.Object(request.Body).ContainsKey("continuation")){outageHeads++;return Task.FromResult(Search(Video(),Token("OUTAGE")));}
            if(++outageNext==1)throw InnerTubeException.Http(503,"5");return Task.FromResult(Next(Video("aaaaaaaaaaa")));
        },outagePause);
        error=await Failure(()=>outage.Browse("ytsearch:outage",0,2,false,CancellationToken.None,null,true));
        Check(error is InnerTubeException && outagePause.RemainingSeconds==5,"temporary service outage pauses further HTTP");
        await Failure(()=>outage.Browse("ytsearch:outage",0,2,false,CancellationToken.None,null,true));
        Check(outageHeads==1 && outageNext==1,"reopening during a server pause cannot resend the continuation");
        now=now.AddSeconds(6);rows=await outage.Browse("ytsearch:outage",0,2,false,CancellationToken.None,null,true);
        Check(rows.Count==2 && outageHeads==1 && outageNext==2,"retry after service recovery preserves completed native pages");

        var playlist=Client((request,ct)=>Task.FromResult(request.Body==null?Config():Search(Video(),Video("aaaaaaaaaaa"))));
        var playlistPage=await playlist.Playlist("https://www.youtube.com/playlist?list=PLfixture",0,2,false,CancellationToken.None,null);
        playlistPage.Items[0].Channel="Changed by queue";playlistPage.Items[0].Url="https://example.invalid/";
        playlistPage=await playlist.Playlist("https://www.youtube.com/playlist?list=PLfixture",0,2,false,CancellationToken.None,null);
        Check(playlistPage.Items[0].Channel=="" && playlistPage.Items[0].Url.Contains(Id),"queue edits cannot mutate cached playlist entries");

        int budgetCalls=0;
        var budget=Client((request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());budgetCalls++;
            if(!Json.Object(request.Body).ContainsKey("continuation"))return Task.FromResult(Search(Token("1")));
            int value=Int32.Parse(Json.Str(Json.Object(request.Body),"continuation"));return Task.FromResult(value==64?Next(Video()):Next(Token((value+1).ToString(CultureInfo.InvariantCulture))));
        });
        error=await Failure(()=>budget.Browse("ytsearch:deep",0,1,false,CancellationToken.None,null,true));
        Check(error is InnerTubeException && ((InnerTubeException)error).Kind==InnerTubeFailure.RequestBudget && budgetCalls==64,"one manual action retains the 64-request budget");
        rows=await budget.Browse("ytsearch:deep",0,1,false,CancellationToken.None,null,true);
        Check(rows.Count==1 && budgetCalls==65,"manual retry advances beyond the request budget instead of restarting the same chain");

        int malformedHeads=0;bool malformed=true;
        var invalid=Client((request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());
            if(!Json.Object(request.Body).ContainsKey("continuation")){malformedHeads++;return Task.FromResult(Search(Video(),Token("N")));}
            if(malformed){malformed=false;return Task.FromResult(Next(Video("aaaaaaaaaaa"),Obj("continuationItemRenderer",Obj("unknownCommand",true))));}
            return Task.FromResult(Next(Video("bbbbbbbbbbb")));
        });
        error=await Failure(()=>invalid.Browse("ytsearch:invalid",0,2,false,CancellationToken.None,null));
        Check(error is InnerTubeException && ((InnerTubeException)error).Kind==InnerTubeFailure.InvalidResponse,"a broken continuation cannot silently end a list");
        rows=await invalid.Browse("ytsearch:invalid",0,2,false,CancellationToken.None,null);
        Check(malformedHeads==2 && rows.Count==2 && rows[1].Id=="bbbbbbbbbbb","invalid response state is discarded and partial rows from it never enter the cache");
        var malformedJson=Client((request,ct)=>Task.FromResult(request.Body==null?Config():"<html>PRIVATE_SERVER_BODY</html>"));
        error=await Failure(()=>malformedJson.Browse("ytsearch:json",0,1,false,CancellationToken.None,null));
        Check(error is InnerTubeException && !error.ToString().Contains("PRIVATE_SERVER_BODY"),"non-JSON API responses produce a localized safe error");
        var mismatched=Client((request,ct)=>Task.FromResult(request.Path.StartsWith("/watch?")?"ytInitialPlayerResponse = "+Encode(Obj("videoDetails",Obj("videoId","aaaaaaaaaaa")))+";":Config()));
        error=await Failure(()=>mismatched.Metadata("https://youtu.be/"+Id,CancellationToken.None,null));Check(error is InnerTubeException,"watch metadata cannot be attached to a different requested video");

        int cancelHeads=0,cancelNext=0;using(var cancel=new CancellationTokenSource()){
            var cancellable=Client((request,ct)=>{
                if(request.Body==null)return Task.FromResult(Config());if(!Json.Object(request.Body).ContainsKey("continuation")){cancelHeads++;return Task.FromResult(Search(Video(),Token("C")));}
                if(++cancelNext==1){cancel.Cancel();ct.ThrowIfCancellationRequested();}return Task.FromResult(Next(Video("aaaaaaaaaaa")));
            });
            error=await Failure(()=>cancellable.Browse("ytsearch:cancel",0,2,false,cancel.Token,null));Check(error is OperationCanceledException,"cancel remains cancellation rather than a service failure");
            rows=await cancellable.Browse("ytsearch:cancel",0,2,false,CancellationToken.None,null,true);Check(cancelHeads==1 && cancelNext==2 && rows.Count==2,"cancellation preserves only previously validated pages for an explicit retry");
        }

        var shared=new InnerTubeBackoff(()=>now);int firstCalls=0,secondCalls=0;
        var first=Client((request,ct)=>{
            firstCalls++;if(request.Body==null)return Task.FromResult(Config());
            if(Json.Str(Json.Object(request.Body),"query")=="limited")throw InnerTubeException.Http(429,"10");return Task.FromResult(Search(Video()));
        },shared);
        var second=Client((request,ct)=>{secondCalls++;return Task.FromResult(request.Body==null?Config():Search(Video()));},shared);
        await first.Browse("ytsearch:cached",0,1,false,CancellationToken.None,null);
        error=await Failure(()=>first.Browse("ytsearch:limited",0,1,false,CancellationToken.None,null));Check(error is InnerTubeException && ((InnerTubeException)error).StatusCode==429,"429 status survives transport normalization");
        int before=firstCalls;rows=await first.Browse("ytsearch:cached",0,1,false,CancellationToken.None,null);
        Check(rows.Count==1 && firstCalls==before,"cached pages stay usable while YouTube has paused new requests");
        error=await Failure(()=>second.Browse("ytsearch:other",0,1,false,CancellationToken.None,null));Check(error is InnerTubeException && secondCalls==0,"cooldown covers other clients and prevents even their bootstrap requests");
        now=now.AddSeconds(11);rows=await second.Browse("ytsearch:other",0,1,false,CancellationToken.None,null);Check(rows.Count==1 && secondCalls==2,"the next explicit action may send requests once cooldown expires");
        var bodyPause=new InnerTubeBackoff(()=>now);var bodyError=Client((request,ct)=>Task.FromResult(request.Body==null?Config():"{\"error\":{\"code\":429,\"message\":\"PRIVATE_ERROR_BODY\"}}"),bodyPause);
        error=await Failure(()=>bodyError.Browse("ytsearch:error",0,1,false,CancellationToken.None,null));Check(bodyPause.RemainingSeconds==60 && !error.ToString().Contains("PRIVATE_ERROR_BODY"),"JSON error status also activates cooldown without copying the response body");

        int endpoints=0,browses=0;var batchClient=Client((request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());
            if(request.Path.Contains("resolve_url")){endpoints++;return Task.FromResult(Encode(Obj("endpoint",Obj("browseEndpoint",Obj("browseId",endpoints==1?Channel:"UCzyxwvutsrqponmlkjihgfe")))));}
            if(++browses==2)throw InnerTubeException.Http(429,"10");return Task.FromResult(Search(Video()));
        });
        var channels=new List<LocalChannel>{new LocalChannel{Id=Channel,Title="First"},new LocalChannel{Id="UCzyxwvutsrqponmlkjihgfe",Title="Second"},new LocalChannel{Id="UCABCDEFGHIJKLMNOPQRSTUV",Title="Third"}};
        var batch=await new Backend(new Settings(),null,batchClient).ChannelBatch(channels,"video",0,false,CancellationToken.None,null);
        Check(batch.Count==1 && batch.ContainsKey(Channel) && endpoints==2 && browses==2,"Backend stops a rate-limited batch and retains completed channels");
        Check(InnerTubeException.Http(503).StopsBatch && !InnerTubeException.Http(404).StopsBatch,"service outage stops a batch while a single unavailable channel may be skipped");
    }
}
