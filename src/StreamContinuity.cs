using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
namespace LowTube {
    public sealed partial class MainForm {
        sealed class PreparedItem { public string Owner,Ticket;public WatchEntry Entry;public Media Media;public Dictionary<string,object> Info;public bool Audio; }
        PreparedItem prepared;
        CancellationTokenSource preloadCancel,recoveryCancel;
        Task recoveryTask=Task.FromResult(false);
        bool preparingNext,recovering;
        string preloadAttempt="";
        string waitingPrepared="";DateTime preparedDeadline;
        int continuityGeneration;
        readonly RecoveryBudget recoveryBudget=new RecoveryBudget();
        readonly List<string> continuityEvents=new List<string>();
        DateTime stalledSince=DateTime.MinValue;
        double lastStallPosition=-1;
        void EventNote(string code){continuityEvents.Add(DateTime.UtcNow.ToString("HH:mm:ss.fff")+" "+DiagnosticReport.Atom(code));if(continuityEvents.Count>200)continuityEvents.RemoveAt(0);}
        void CancelRecovery(){waitingPrepared="";continuityGeneration++;if(recoveryCancel!=null)recoveryCancel.Cancel();stalledSince=DateTime.MinValue;}
        async Task ResetContinuity(){CancelRecovery();if(recovering){player.Stop();await recoveryTask;}await InvalidatePrepared();recoveryBudget.Reset();preloadAttempt="";}
        async Task InvalidatePrepared(){
            if(preloadCancel!=null)preloadCancel.Cancel();var previous=prepared;
            try{await player.CancelNext();}catch(Exception){EventNote("prefetch cancel failed");}
            if(previous!=null && Object.ReferenceEquals(prepared,previous)){
                if(player.Session==previous.Ticket)AdoptPrepared(previous.Ticket);else prepared=null;
            }
        }
        async void CheckPreparedTransition(){
            if(waitingPrepared.Length==0 || DateTime.UtcNow<preparedDeadline || closing || closePending)return;
            if(operation!=null || advancingQueue){preparedDeadline=DateTime.UtcNow.AddSeconds(1);return;}
            string owner=waitingPrepared;waitingPrepared="";
            if(prepared!=null && player.Session==prepared.Ticket){AdoptPrepared(prepared.Ticket);return;}
            await InvalidatePrepared();
            if(player.Session==owner && !player.Running && operation==null && settings.AutoNext)await AdvanceQueue(false);
        }
        async void QueueChanged(){
            var first=watchQueue.FirstOrDefault(x=>String.IsNullOrEmpty(x.Error));
            if(prepared!=null && (!settings.AutoNext || !settings.PreloadNext || !Object.ReferenceEquals(first,prepared.Entry)))await InvalidatePrepared();
        }
        void ObserveContinuity(Dictionary<string,object> sample){
            if(closing || closePending || playbackStarting || recovering || videoSettingsOpen || stoppingPlayer || resumeSession.Length==0)return;
            double pos=Number(Json.Str(sample,"time-pos"),-1),duration=Number(Json.Str(sample,"duration"),0);
            bool paused=Json.Str(sample,"pause")=="True",seeking=Json.Str(sample,"seeking")=="True",buffering=Json.Str(sample,"paused-for-cache")=="True";
            if(!paused && !seeking && buffering && pos>=0){
                if(Math.Abs(pos-lastStallPosition)>0.25 || stalledSince==DateTime.MinValue)stalledSince=DateTime.UtcNow;
                lastStallPosition=pos;
                if((DateTime.UtcNow-stalledSince).TotalSeconds>=45)RequestRecovery("buffer stalled");
            }else stalledSince=DateTime.MinValue;
            if(settings.PreloadNext && settings.AutoNext && !mediaLive && !paused && !seeking && !buffering && duration>0 && pos>=0 && duration-pos<=20 && duration-pos>0 && player.Running && operation==null && !preparingNext && prepared==null && preloadAttempt!=resumeSession)PrepareNext();
        }
        async void PrepareNext(){
            string owner=resumeSession;preloadAttempt=owner;preparingNext=true;bool failed=false;
            var cancelNext=new CancellationTokenSource();preloadCancel=cancelNext;cancelNext.CancelAfter(25000);var ct=cancelNext.Token;
            try{
                await PauseFeeds();ct.ThrowIfCancellationRequested();
                var entry=watchQueue.FirstOrDefault(x=>String.IsNullOrEmpty(x.Error));if(entry==null)return;
                var snapshot=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(settings));
                // Only one page, one video and one attempt per playback session.
                if(entry.Playlist){
                    var batch=await new Backend(snapshot).PlaylistPage(entry.Video.Url,entry.Page,entry.PageSize,entry.Account,ct,Append);ct.ThrowIfCancellationRequested();
                    if(resumeSession!=owner || !player.Running || !Object.ReferenceEquals(watchQueue.FirstOrDefault(x=>String.IsNullOrEmpty(x.Error)),entry))return;
                    int index=watchQueue.IndexOf(entry);if(watchQueue.Count-1+batch.Items.Count+(batch.HasMore?1:0)>5000)return;
                    watchQueue.Remove(entry);var added=batch.Items.Select(v=>new WatchEntry{Video=v,Origin=entry.Origin}).ToList();
                    if(batch.HasMore){entry.Page++;added.Add(entry);}watchQueue.InsertRange(index,added);SaveQueue();
                    entry=watchQueue.FirstOrDefault(x=>String.IsNullOrEmpty(x.Error));if(entry==null || entry.Playlist)return;
                }
                var info=await new Backend(snapshot).Resolve(entry.Video.Url,mediaAudio,ct,Append);ct.ThrowIfCancellationRequested();
                if(resumeSession!=owner || !player.Running || operation!=null || !settings.AutoNext || !settings.PreloadNext || !Object.ReferenceEquals(watchQueue.FirstOrDefault(x=>String.IsNullOrEmpty(x.Error)),entry))return;
                var media=Media.From(info,mediaAudio);if(media.IsLive)return;
                var item=new PreparedItem{Owner=owner,Ticket=Guid.NewGuid().ToString("N"),Entry=entry,Media=media,Info=info,Audio=mediaAudio};prepared=item;
                double position=snapshot.ResumePlayback?ResumeRules.Position(resumePoints,entry.Video.Url,false):0;
                await player.StageNext(snapshot,media,item.Audio,position,owner,item.Ticket);
                if(ct.IsCancellationRequested){await InvalidatePrepared();return;}
                EventNote("next stream prepared");
            }catch(OperationCanceledException){EventNote("prefetch cancelled");}
            catch(Exception e){EventNote("prefetch unavailable "+e.GetType().Name);failed=true;}
            finally{if(preloadCancel==cancelNext)preloadCancel=null;cancelNext.Dispose();preparingNext=false;}
            if(failed)await InvalidatePrepared();
        }
        void AdoptPrepared(string ticket){
            var item=prepared;if(item==null || item.Ticket!=ticket || closing || closePending || stoppingPlayer || player.Session!=ticket || !player.Running)return;
            waitingPrepared="";prepared=null;mediaTitle=item.Media.Title;mediaUrl=item.Entry.Video.Url;mediaAudio=item.Audio;mediaLive=item.Media.IsLive;
            latestPlayback.Clear();resumeSession=ticket;preloadAttempt="";recoveryBudget.Reset();stalledSince=DateTime.MinValue;
            watchQueue.Remove(item.Entry);SaveQueue();CloseAudioWindow();
            now.Text=L.T(mediaAudio?"Audio only: {0}":"Video: {0}",mediaTitle);OpenCompactPlayer(item.Info);RememberVideo(item.Info,mediaUrl);
            EventNote("prepared stream started");SyncPlayerMenu();
        }
        void OpenCompactPlayer(Dictionary<string,object> info){
            if(!mediaAudio)return;
            var window=new AudioForm(player,Json.Str(info,"id"),mediaTitle,Json.Str(info,"channel",Json.Str(info,"uploader")),OnPlayerStopped,CapturePosition);
            window.TopMost=settings.PlayerOnTop;audioWindow=window;window.FormClosed+=delegate{if(audioWindow==window)audioWindow=null;};window.Show();
        }
        void RememberVideo(Dictionary<string,object> info,string url){var v=Video.From(info);v.Url=url;v.Date=DateTime.Now.ToString("dd.MM.yyyy HH:mm");history.RemoveAll(x=>x==null || x.Url==url);history.Insert(0,v);history=history.Take(500).ToList();try{Json.Save(System.IO.Path.Combine(Paths.Data,"history.json"),history);}catch(Exception e){Error(e);}}
        void RequestRecovery(string reason){
            if(closing || closePending || recovering || stoppingPlayer || playbackStarting || videoSettingsOpen || resumeSession.Length==0 || player.Session!=resumeSession)return;
            EventNote("stream failure "+reason);
            if(!settings.AutoRecover || recoveryBudget.Attempts>=2){Notify(L.T("Stream recovery stopped. Check the connection, quality or browser session, then press Play."));return;}
            recoveryTask=RecoverStream();
        }
        async Task RecoverStream(){
            recovering=true;string owner=resumeSession,url=mediaUrl;bool audio=mediaAudio,live=mediaLive;
            int generation=continuityGeneration;var own=new CancellationTokenSource();recoveryCancel=own;
            double position=Number(Json.Str(latestPlayback,"time-pos"),ResumeRules.Position(resumePoints,url,live));
            bool paused=Json.Str(latestPlayback,"pause")=="True";
            double volume=Number(Json.Str(latestPlayback,"volume"),100),speed=Number(Json.Str(latestPlayback,"speed"),1);
            try{
                await InvalidatePrepared();await PauseFeeds();
                if(player.Running && player.Session==owner){
                    try{
                        position=Number(await player.Command("get_property","time-pos"),position);
                        paused=Object.Equals(await player.Command("get_property","pause"),true);
                        volume=Number(await player.Command("get_property","volume"),volume);
                        speed=Number(await player.Command("get_property","speed"),speed);
                    }catch(Exception){}
                }
                // Wait for a foreground operation without spawning concurrent extractors.
                for(int i=0;operation!=null && i<150;i++)await Task.Delay(200,own.Token);
                if(operation!=null){Notify(L.T("Stream recovery stopped. Check the connection, quality or browser session, then press Play."));return;}
                while(resumeSession==owner && generation==continuityGeneration && recoveryBudget.Take()){
                    own.Token.ThrowIfCancellationRequested();EventNote("recovery attempt "+recoveryBudget.Attempts);Notify(L.T("Restoring stream… attempt {0}/2",recoveryBudget.Attempts));
                    try{
                        await Task.Delay(recoveryBudget.Attempts==1?1000:3000,own.Token);
                        Dictionary<string,object> info;
                        using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(own.Token)){
                            timeout.CancelAfter(45000);info=await new Backend(settings).Resolve(url,audio,timeout.Token,Append,false,true);
                        }
                        own.Token.ThrowIfCancellationRequested();if(generation!=continuityGeneration || owner!=resumeSession)return;
                        var media=Media.From(info,audio);
                        await player.Start(settings,media,audio,Append,live?0:position,paused,volume,speed,StartupTrace.StartAttempt("stream recovery",settings,audio));
                        if(own.IsCancellationRequested || generation!=continuityGeneration || owner!=resumeSession){player.Stop();own.Token.ThrowIfCancellationRequested();return;}
                        resumeSession=player.Session;mediaTitle=media.Title;mediaLive=media.IsLive;latestPlayback.Clear();preloadAttempt="";stalledSince=DateTime.MinValue;
                        CloseAudioWindow();OpenCompactPlayer(info);if(playbackWindow!=null)await player.Command("script-message","lowtube-status-visible","yes");
                        EventNote("recovery succeeded");Notify(L.T("Stream restored."));return;
                    }catch(OperationCanceledException){if(own.IsCancellationRequested)throw;EventNote("recovery request timed out");}
                    catch(Exception e){EventNote("recovery failed "+e.GetType().Name);}
                }
                Notify(L.T("Stream recovery stopped. Check the connection, quality or browser session, then press Play."));
            }catch(OperationCanceledException){EventNote("recovery cancelled");}
            catch(Exception e){EventNote("recovery aborted "+e.GetType().Name);}
            finally{if(recoveryCancel==own)recoveryCancel=null;own.Dispose();recovering=false;}
        }
    }
}
