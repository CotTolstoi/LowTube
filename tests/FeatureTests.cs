using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using LowTube;
public static class FeatureTests {
    static int count;
    static void Check(bool ok,string name){if(!ok)throw new Exception(name);count++;Console.WriteLine("PASS: "+name);}
    static async Task Wait(Func<bool> condition){for(int i=0;i<400;i++){if(condition())return;await Task.Delay(10);}throw new Exception("Timed out waiting for state");}
    // DownloadManager belongs to one UI context. Exercise that contract also in
    // the console harness; a thread-pool context introduced artificial Save races.
    sealed class TestContext : SynchronizationContext {
        readonly System.Collections.Concurrent.BlockingCollection<Action> callbacks=new System.Collections.Concurrent.BlockingCollection<Action>();
        public override void Post(SendOrPostCallback callback,object state){callbacks.Add(()=>callback(state));}
        public void Pump(Task task){while(!task.IsCompleted){Action next;if(callbacks.TryTake(out next,100))next();}task.GetAwaiter().GetResult();}
    }
    public static int Main(){var previous=SynchronizationContext.Current;try{var context=new TestContext();SynchronizationContext.SetSynchronizationContext(context);context.Pump(Run());Console.WriteLine("TOTAL: "+count+" feature checks passed");return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}finally{SynchronizationContext.SetSynchronizationContext(previous);}}
    static async Task Run(){
        L.Set("en");string url="https://www.youtube.com/watch?v=CdEpmU9pRaE";
        Check(ResumeRules.Key(url)==ResumeRules.Key("https://youtu.be/CdEpmU9pRaE?si=tracking"),"resume matches short links and tracking links");
        Check(ResumeRules.Key("https://www.youtube.com/shorts/CdEpmU9pRaE")=="CdEpmU9pRaE","shorts share a resume identity");
        Check(ResumeRules.Key("https://youtube.com.invalid/watch?v=CdEpmU9pRaE")=="","foreign domains are not resume identities");
        var points=new List<ResumePoint>();ResumeRules.Record(points,url,"Title",123,600,false);
        Check(ResumeRules.Position(points,url,false)==123,"recorded position resumes");
        Check(ResumeRules.Position(points,url,true)==0,"live streams never resume an old offset");
        ResumeRules.Record(points,url,"Title",Double.NaN,600,false);Check(points[0].Position==123,"invalid samples cannot destroy a good checkpoint");
        ResumeRules.Record(points,url,"Title",595,600,false);Check(points.Count==0,"nearly finished videos restart");
        ResumeRules.Record(points,url,"Title",20,0,false);Check(points.Count==1,"unknown duration can retain a valid checkpoint");
        ResumeRules.Record(points,url,"Title",0,0,false);Check(points.Count==0,"explicit restart or completion clears resume point");
        Check(QueueRules.PlaylistUrl(url+"&list=PLabc")=="https://www.youtube.com/playlist?list=PLabc","watch links retain playlist continuation");
        Check(QueueRules.PlaylistUrl("https://example.invalid/?list=PLabc")=="","playlist normalization rejects foreign hosts");
        var queue=new List<WatchEntry>{new WatchEntry{Video=new Video{Url=url,Title="A"}},new WatchEntry{Video=new Video{Url=url,Title="B"},Error="failed"}};
        QueueRules.Move(queue,0,1);Check(queue[0].Video.Title=="B","queue reorder changes order");QueueRules.Move(queue,0,-1);Check(queue[0].Video.Title=="B","moving beyond boundary preserves queue");
        queue.Add(new WatchEntry{Video=new Video{Url="https://www.youtube.com/playlist?list=PLsample",Kind="playlist"},Page=2,PageSize=25});
        var restored=Json.Serializer().Deserialize<List<WatchEntry>>(Json.Serializer().Serialize(queue));
        Check(restored[0].Error=="failed" && restored[2].Playlist && restored[2].Page==2 && restored[2].PageSize==25,"queue persistence retains failures and playlist continuation");
        var batch=PlaylistBatch.Parse(Json.Object("{\"playlist_count\":100,\"entries\":[null,{\"id\":\"CdEpmU9pRaE\",\"url\":\"https://youtu.be/CdEpmU9pRaE\",\"title\":\"A\"}]}"),0,50);
        Check(batch.Items.Count==1 && batch.HasMore,"unavailable entries do not terminate a known playlist");
        batch=PlaylistBatch.Parse(Json.Object("{\"playlist_count\":100,\"entries\":[]}"),1,50);Check(!batch.HasMore,"last known playlist page ends");
        batch=PlaylistBatch.Parse(Json.Object("{\"entries\":[null,null]}"),0,2);Check(batch.HasMore,"full unknown page of deleted entries can continue");
        batch=PlaylistBatch.Parse(Json.Object("{\"entries\":[]}"),5,50);Check(!batch.HasMore,"empty unknown page terminates pagination");
        string dir=Path.Combine(Path.GetTempPath(),"lowtube-features-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(dir);
        try{
            string path=Path.Combine(dir,"downloads.json");var calls=new List<string>();var gate=new TaskCompletionSource<bool>();int running=0,maxRunning=0;
            Func<Settings,DownloadJob,CancellationToken,Action<string>,Task> transfer=async (s,j,ct,progress)=>{
                int n=Interlocked.Increment(ref running);maxRunning=Math.Max(n,maxRunning);lock(calls)calls.Add(j.Title);
                try{progress("[download] 50% at 1MiB/s ETA 00:10");if(j.Title=="first")await gate.Task;if(j.Title=="bad")throw new IOException("synthetic failure");ct.ThrowIfCancellationRequested();}finally{Interlocked.Decrement(ref running);}
            };
            var manager=new DownloadManager(path,()=>new Settings{CookiesFile="PRIVATE_COOKIE_PATH"},e=>{throw e;},transfer);
            var pick=new DownloadSelection{Kind="MP4",FormatId="137",Height=1080};
            manager.Add(url,"first",dir,pick);await Wait(()=>running==1);manager.Add(url,"bad",dir,pick);manager.Add(url,"third",dir,pick);gate.SetResult(true);
            await Wait(()=>manager.Jobs[2].State=="Completed" && !manager.Busy);
            Check(maxRunning==1 && String.Join(",",calls)=="first,bad,third","downloads are sequential and continue after a failed job");
            Check(manager.Jobs[1].State=="Failed" && manager.Jobs[1].Detail.Contains("synthetic"),"failed download retains actionable error");
            Check(manager.Jobs[0].LastOutput.Contains("50%"),"download progress is retained");
            await manager.Shutdown();Check(!File.ReadAllText(path).Contains("PRIVATE_COOKIE_PATH"),"download persistence excludes account settings");
            var saved=new List<DownloadJob>{new DownloadJob{State="Downloading"},new DownloadJob{State="Queued"},new DownloadJob{State="Completed"}};Json.Save(path,saved);
            var idle=new DownloadManager(path,()=>new Settings(),e=>{throw e;},transfer);Check(idle.Jobs[0].State=="Interrupted" && idle.Jobs[1].State=="Interrupted" && idle.Jobs[2].State=="Completed" && !idle.Busy,"restart never automatically launches unfinished downloads");await idle.Shutdown();
            var started=new TaskCompletionSource<bool>();
            var cancellable=new DownloadManager(Path.Combine(dir,"cancel.json"),()=>new Settings(),e=>{throw e;},async(s,j,ct,progress)=>{started.TrySetResult(true);await Task.Delay(30000,ct);});
            cancellable.Add(url,"cancel",dir,pick);await started.Task;cancellable.Cancel(cancellable.Jobs[0]);await Wait(()=>!cancellable.Busy);
            Check(cancellable.Jobs[0].State=="Cancelled","cancel terminates the active job");
            cancellable.Retry(cancellable.Jobs[0]);await Wait(()=>cancellable.Busy);await cancellable.Shutdown();Check(cancellable.Jobs[0].State=="Interrupted","shutdown saves active retry as interrupted");
        }finally{Directory.Delete(dir,true);}
    }
}
