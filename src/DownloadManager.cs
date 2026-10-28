using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
namespace LowTube {
    public sealed class DownloadJob {
        public string Id=Guid.NewGuid().ToString("N"),Url="",Title="",Folder="",State="Queued",Detail="",LastOutput="";
        public DownloadSelection Selection=new DownloadSelection();
    }
    // All job state changes are on the UI context. Reader threads only update a bounded progress string.
    public sealed class DownloadManager {
        readonly string path;readonly Func<Settings> settings;readonly Action<Exception> report;
        readonly Func<Settings,DownloadJob,CancellationToken,Action<string>,Task> transfer;
        public readonly List<DownloadJob> Jobs;CancellationTokenSource active;DownloadJob current;Task pump=Task.FromResult(0);bool stopping;
        public DownloadManager(string path,Func<Settings> settings,Action<Exception> report,Func<Settings,DownloadJob,CancellationToken,Action<string>,Task> transfer=null){
            this.path=path;this.settings=settings;this.report=report;this.transfer=transfer??((s,j,ct,progress)=>new Backend(s).Download(j.Url,j.Selection.Kind,ct,progress,j.Selection));
            Jobs=(Json.Load(path,new List<DownloadJob>())??new List<DownloadJob>()).Where(x=>x!=null && x.Selection!=null).Take(1000).ToList();
            foreach(var j in Jobs)if(j.State=="Downloading" || j.State=="Queued"){j.State="Interrupted";j.Detail="";}
        }
        public bool Busy {get{return current!=null;}}
        public void Save(){try{Json.Save(path,Jobs);}catch(Exception e){report(e);}}
        public void Add(string url,string title,string folder,DownloadSelection selection){
            if(Jobs.Count>=1000)throw new InvalidOperationException(L.T("Remove old downloads before adding more."));
            Jobs.Add(new DownloadJob{Url=url,Title=title,Folder=folder,Selection=selection});Save();Start();
        }
        void Start(){if(!stopping && pump.IsCompleted)pump=Run();}
        async Task Run(){
            // Yield before taking a job so Start always retains the actual running task.
            await Task.Yield();
            while(!stopping){
                var j=Jobs.FirstOrDefault(x=>x.State=="Queued");if(j==null)break;
                current=j;active=new CancellationTokenSource();j.State="Downloading";j.Detail="";j.LastOutput="";Save();
                try{
                    var s=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(settings()));s.DownloadFolder=j.Folder;
                    await transfer(s,j,active.Token,delegate(string raw){
                        var lines=Rules.Redact(raw).Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries);
                        if(lines.Length>0){string text=lines[lines.Length-1];j.LastOutput=text.Length>700?text.Substring(text.Length-700):text;}
                    });
                    j.State="Completed";j.Detail=L.T("Saved to: {0}",j.Folder);
                }catch(OperationCanceledException){if(j.State=="Downloading")j.State=stopping?"Interrupted":"Cancelled";j.Detail="";}
                catch(Exception e){j.State="Failed";j.Detail=Rules.Redact(e.Message);}
                finally{active.Dispose();active=null;current=null;Save();}
            }
        }
        public void Cancel(DownloadJob j){if(j==null)return;if(j==current){j.State="Cancelled";active.Cancel();}else if(j.State=="Queued")j.State="Cancelled";Save();}
        public void Retry(DownloadJob j){if(j==null || j==current || j.State=="Downloading" || j.State=="Queued")return;j.State="Queued";j.Detail="";Save();Start();}
        public void Remove(DownloadJob j){if(j!=null && j!=current && j.State!="Queued"){Jobs.Remove(j);Save();}}
        public async Task Shutdown(){stopping=true;if(current!=null){current.State="Interrupted";active.Cancel();}foreach(var j in Jobs)if(j.State=="Queued")j.State="Interrupted";Save();await pump;}
    }
}
