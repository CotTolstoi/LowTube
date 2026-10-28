using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace LowTube {
    public sealed partial class MainForm {
        List<ResumePoint> resumePoints;
        List<WatchEntry> watchQueue;
        DownloadManager downloads;
        Form queueWindow,downloadsWindow,playbackWindow;
        ListView queueList,downloadList;
        TextBox playbackText;
        Label notice;
        string resumeSession="";bool mediaLive,advancingQueue,queueCancelled,closePending,stoppingPlayer;
        DateTime lastResumeWrite=DateTime.MinValue;
        Dictionary<string,object> latestPlayback=new Dictionary<string,object>();
        readonly System.Windows.Forms.Timer featureTimer=new System.Windows.Forms.Timer{Interval=1000};
        string ResumePath {get{return Path.Combine(Paths.Data,"resume.json");}}
        string QueuePath {get{return Path.Combine(Paths.Data,"watch-queue.json");}}
        void InitializeWorkspace(FlowLayoutPanel nav,FlowLayoutPanel actions,FlowLayoutPanel controls,TableLayoutPanel layout){
            resumePoints=Json.Load(ResumePath,new List<ResumePoint>())??new List<ResumePoint>();
            watchQueue=QueueRules.Clean(Json.Load(QueuePath,new List<WatchEntry>()));
            downloads=new DownloadManager(Path.Combine(Paths.Data,"downloads.json"),()=>settings,Error);
            foreach(var pair in new[]{new KeyValuePair<string,Action>("Watch queue",OpenQueue),new KeyValuePair<string,Action>("Downloads",OpenDownloads),new KeyValuePair<string,Action>("Playback status",OpenPlaybackStatus)}){
                var b=NavButton(pair.Key,pair.Value);locked.Remove(b);nav.Controls.Add(b);
            }
            actions.Controls.Add(Button(L.T("Add to queue"),AddToQueue,false));
            actions.Controls.Add(Button(L.T("Browse playlist"),async delegate{try{var v=SelectedVideo();string url=v==null?search.Text:v.Url;if(ContentFeeds.IsPlaylist(url)){changingFilters=true;contentType.SelectedIndex=0;changingFilters=false;await Browse(Rules.YouTubeUrl(url),v==null?L.T("Playlist"):v.Title,currentAccount,0);}}catch(Exception e){Error(e);}},true));
            controls.Controls.Add(Button(L.T("Next in queue"),async delegate{await AdvanceQueue(true);},false));
            controls.Controls.Add(Button(L.T("Start over"),async delegate{try{if(player.Running){await player.Command("seek",0,"absolute");ResumeRules.Record(resumePoints,mediaUrl,mediaTitle,0,0,mediaLive);SaveResume();}}catch(Exception e){Error(e);}},false));
            // A persistent non-modal notice is separate from feed status, which changes during browsing.
            layout.Controls.Remove(status);var bottom=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=1,Margin=Padding.Empty};
            bottom.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));bottom.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
            status.AutoEllipsis=true;bottom.Controls.Add(status,0,0);
            notice=new Label{Dock=DockStyle.Fill,AutoEllipsis=true,TextAlign=ContentAlignment.MiddleRight,Cursor=Cursors.Hand,Tag="data"};bottom.Controls.Add(notice,1,0);layout.Controls.Add(bottom,0,7);
            var noticeHelp=new ToolTip();notice.MouseEnter+=delegate{noticeHelp.SetToolTip(notice,notice.Text);};Disposed+=delegate{noticeHelp.Dispose();};
            notice.Click+=delegate{if(notice.Text.Length>0)ShowReport(L.T("Details"),notice.Text+Environment.NewLine+log.Text);};
            InitializeLibraryFeatures(nav);
            player.Progress+=OnPlaybackProgress;
            player.PreparedStarted+=ticket=>{if(!closing && IsHandleCreated)BeginInvoke(new Action(delegate{AdoptPrepared(ticket);}));};
            player.Stopped+=()=>{if(!closing && IsHandleCreated){if(InvokeRequired)BeginInvoke(new Action(delegate{if(player.Session==null)OnPlayerStopped();}));else OnPlayerStopped();}};
            player.PlaybackFailed+=(failedSession,message)=>{if(!closing && IsHandleCreated)BeginInvoke(new Action(delegate{if(failedSession==resumeSession)RequestRecovery("mpv error");}));};
            featureTimer.Tick+=delegate{
                if(closing)return;
                if(settings.PlayerOnTop && settings.AboveFullscreen && player.Running){if(mediaAudio){if(audioWindow!=null)RaiseForm(audioWindow);}else player.RaiseTopmost();}
                RefreshDownloadRows();RefreshPlaybackStatus();CheckPreparedTransition();
            };featureTimer.Start();Disposed+=delegate{featureTimer.Dispose();};
        }
        void Notify(string text){if(closing || notice==null)return;notice.Text=Rules.Redact(text);}
        void CancelPlaybackWork(){CancelRecovery();queueCancelled=true;if(operation!=null && (playbackStarting || videoSettingsOpen || advancingQueue))operation.Cancel();}
        async void OnPlayerStopped(){
            if(closing || closePending)return;
            CancelPlaybackWork();resumeSession="";CloseAudioWindow();now.Text=L.T("Player stopped");
            await InvalidatePrepared();
        }
        void SaveResume(){try{Json.Save(ResumePath,resumePoints);SaveWatched();}catch(Exception e){Error(e);}}
        void SaveQueue(){try{Json.Save(QueuePath,watchQueue);}catch(Exception e){Error(e);}QueueChanged();RefreshQueueRows();}
        void OnPlaybackProgress(Dictionary<string,object> sample){
            if(closing || IsDisposed || !IsHandleCreated)return;
            if(InvokeRequired){try{BeginInvoke(new Action<Dictionary<string,object>>(OnPlaybackProgress),sample);}catch(InvalidOperationException){}return;}
            if(Json.Str(sample,"session")!=resumeSession || resumeSession.Length==0)return;
            latestPlayback=sample;lastPlaybackSampleUtc=DateTime.UtcNow;
            double position=Number(Json.Str(sample,"time-pos"),-1),duration=Number(Json.Str(sample,"duration"),0);
            if(position>=0){RecordWatched(position,duration);ResumeRules.Record(resumePoints,mediaUrl,mediaTitle,position,duration,mediaLive);if((DateTime.UtcNow-lastResumeWrite).TotalSeconds>=5){SaveResume();lastResumeWrite=DateTime.UtcNow;}}
            ObserveContinuity(sample);
            RefreshPlaybackStatus();
        }
        async Task CapturePosition(){
            string own=resumeSession,url=mediaUrl,title=mediaTitle;bool live=mediaLive;
            if(!player.Running || own!=player.Session || String.IsNullOrEmpty(url))return;
            try{
                double pos=Number(await player.Command("get_property","time-pos"),-1);
                double duration=Number(Json.Str(latestPlayback,"duration"),0);
                if(own!=resumeSession || own!=player.Session)return;
                if(pos>=0){ResumeRules.Record(resumePoints,url,title,pos,duration,live);RecordWatched(pos,duration);SaveResume();}
            }catch(Exception){/* Periodic checkpoint remains available if IPC is already closed. */}
        }
        async Task CloseWorkspace(FormClosingEventArgs e){
            if(closing)return;e.Cancel=true;if(closePending)return;closePending=true;
            featureTimer.Stop();CancelRecovery();await InvalidatePrepared();if(recovering){player.Stop();await recoveryTask;}StopFeeds();if(operation!=null)operation.Cancel();
            await CapturePosition();resumeSession="";closing=true;
            try{await downloads.Shutdown();}catch(Exception){}
            SaveQueue();CloseAudioWindow();player.Dispose();try{SaveSettings();}catch(Exception){}
            Close();
        }
        void AddToQueue(){
            try{
                if(initializing)return;
                var v=SelectedVideo();if(v==null){string url=Rules.YouTubeUrl(search.Text);v=new Video{Url=url,Title=url,Kind=ContentFeeds.IsPlaylist(url)?"playlist":"video"};}
                if(watchQueue.Count>=5000)throw new InvalidOperationException(L.T("Watch queue is full."));
                watchQueue.Add(new WatchEntry{Video=v,Account=currentAccount && v.Kind=="playlist"});SaveQueue();Notify(L.T("Added to watch queue: {0}",v.Title));
            }catch(Exception e){Error(e);}
        }
        void SeedPlaylist(string url){
            string playlist=QueueRules.PlaylistUrl(currentTarget);if(playlist.Length==0 || !displayRows.Any(x=>x.Url==url))return;
            // Replace only continuation generated by the currently browsed playlist, keep custom entries.
            watchQueue.RemoveAll(x=>x.Origin==playlist);
            var tail=displayRows.Where(x=>x.Kind!="playlist").SkipWhile(x=>x.Url!=url).Skip(1).Select(v=>new WatchEntry{Video=v,Origin=playlist}).ToList();
            tail.Add(new WatchEntry{Video=new Video{Url=playlist,Title=currentLabel,Kind="playlist"},Page=page+1,PageSize=PageSize,Account=currentAccount,Origin=playlist});
            if(watchQueue.Count+tail.Count>5000){Notify(L.T("Watch queue is full."));SaveQueue();return;}watchQueue.InsertRange(0,tail);SaveQueue();
        }
        async Task BeginPlaylist(Video selected,string url,bool audio){
            if(operation!=null || initializing)return;
            if(watchQueue.Count>=5000){Notify(L.T("Watch queue is full."));return;}
            watchQueue.Insert(0,new WatchEntry{Video=selected??new Video{Url=Rules.YouTubeUrl(url),Title=L.T("Playlist"),Kind="playlist"},Account=currentAccount});
            mediaAudio=audio;SaveQueue();await AdvanceQueue(true);
        }
        async Task OnEnded(string endedSession,bool preloaded){
            if(closing || closePending || stoppingPlayer || resumeSession.Length==0 || endedSession!=resumeSession || player.Session!=endedSession)return;
            if(!preloaded && RecoveryBudget.Premature(Number(Json.Str(latestPlayback,"time-pos"),-1),Number(Json.Str(latestPlayback,"duration"),0),mediaLive)) {RequestRecovery("early EOF");return;}
            RecordWatched(Number(Json.Str(latestPlayback,"duration"),0),Number(Json.Str(latestPlayback,"duration"),0),true);

            ResumeRules.Record(resumePoints,mediaUrl,mediaTitle,0,0,false);SaveResume();resumeSession="";now.Text=L.T("Playback finished");
            if(preloaded){waitingPrepared=endedSession;preparedDeadline=DateTime.UtcNow.AddSeconds(8);return;}
            if(!settings.AutoNext)return;
            while(operation!=null && !closing && !closePending && player.Session==endedSession)await Task.Delay(150);
            if(!closing && !closePending && player.Session==endedSession)await AdvanceQueue(false);
        }
        async Task AdvanceQueue(bool explicitRequest){
            if(advancingQueue || closing || closePending || operation!=null || initializing)return;
            if(!explicitRequest && !settings.AutoNext)return;
            advancingQueue=true;queueCancelled=false;int failures=0,emptyPages=0;
            try{
                while(!closing && !closePending && !queueCancelled){
                    var entry=watchQueue.FirstOrDefault(x=>String.IsNullOrEmpty(x.Error));
                    if(entry==null){Notify(L.T("No playable items left in the queue."));return;}
                    if(entry.Playlist){
                        PlaylistBatch batch=null;bool cancelled=false;Exception failure=null;
                        await Work(L.T("Loading playlist page…"),async ct=>{
                            try{batch=await new Backend(settings).PlaylistPage(entry.Video.Url,entry.Page,entry.PageSize,entry.Account,ct,Append);ct.ThrowIfCancellationRequested();}
                            catch(OperationCanceledException){cancelled=true;throw;}catch(Exception e){failure=e;throw;}
                        });
                        if(cancelled || queueCancelled)return;
                        if(batch==null){entry.Error=failure==null?L.T("Request failed"):Rules.Redact(failure.Message);SaveQueue();if(++failures>=3)return;continue;}
                        int index=watchQueue.IndexOf(entry);if(index<0)continue;
                        watchQueue.RemoveAt(index);
                        if(batch.Items.Count>0 || batch.HasMore){
                            entry.Page++;var valid=batch.Items.Select(v=>new WatchEntry{Video=v,Origin=entry.Origin}).ToList();
                            // Fetch the next page only when these items finish. No unbounded background extraction.
                            if(batch.HasMore)valid.Add(entry);
                            if(watchQueue.Count+valid.Count>5000){entry.Page--;entry.Error=L.T("Watch queue is full.");watchQueue.Insert(index,entry);SaveQueue();Notify(entry.Error);return;}watchQueue.InsertRange(index,valid);
                            if(batch.Items.Count==0 && batch.HasMore && ++emptyPages>=3){entry.Error=L.T("Playlist page has no playable videos.");SaveQueue();return;}
                        }
                        SaveQueue();continue;
                    }
                    string before=player.Session;queuePlaybackCancelled=false;await Play(mediaAudio,entry.Video,true);
                    if(queuePlaybackCancelled || queueCancelled)return;
                    if(player.Running && player.Session!=before){watchQueue.Remove(entry);SaveQueue();return;}
                    entry.Error=L.T("Could not play this item. Select it and retry.");SaveQueue();
                    Notify(L.T("Skipped an unavailable queue item: {0}",entry.Video.Title));if(++failures>=3){Notify(L.T("Queue paused after three failures. Check the connection or session and retry."));return;}
                }
            }catch(Exception e){Error(e);}finally{advancingQueue=false;RefreshQueueRows();}
        }
        bool queuePlaybackCancelled;
        Form FeatureWindow(string title,Size size){var f=new Form{Text=L.T(title),Size=size,MinimumSize=new Size(600,350),StartPosition=FormStartPosition.CenterParent,Font=Font,AutoScaleMode=AutoScaleMode.Dpi};Ui.Attach(f);return f;}
        ListView FeatureList(string[] columns,int[] widths){var l=new ListView{Dock=DockStyle.Fill,View=View.Details,FullRowSelect=true,HideSelection=false,MultiSelect=false};for(int i=0;i<columns.Length;i++)l.Columns.Add(L.T(columns[i]),widths[i]);return l;}
        void OpenQueue(){
            if(queueWindow!=null){queueWindow.Activate();return;}
            queueWindow=FeatureWindow("Watch queue",new Size(850,480));var f=queueWindow;
            queueList=FeatureList(new[]{"Video","State"},new[]{510,280});f.Controls.Add(queueList);
            var buttons=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=85,AutoScroll=true};f.Controls.Add(buttons);
            Func<WatchEntry> chosen=()=>queueList.SelectedItems.Count==0?null:queueList.SelectedItems[0].Tag as WatchEntry;
            buttons.Controls.Add(Ui.Button("Play selected",async delegate{var item=chosen();if(item==null || advancingQueue || operation!=null)return;item.Error="";watchQueue.Remove(item);watchQueue.Insert(0,item);SaveQueue();await AdvanceQueue(true);}));
            foreach(int direction in new[]{-1,1}){int d=direction;buttons.Controls.Add(Ui.Button(d<0?"Move up":"Move down",delegate{if(advancingQueue)return;var item=chosen();int i=watchQueue.IndexOf(item);QueueRules.Move(watchQueue,i,i+d);SaveQueue();SelectQueue(item);}));}
            buttons.Controls.Add(Ui.Button("Remove",delegate{if(advancingQueue)return;var item=chosen();if(item!=null){watchQueue.Remove(item);SaveQueue();}}));
            buttons.Controls.Add(Ui.Button("Clear queue",delegate{if(advancingQueue)return;watchQueue.Clear();SaveQueue();}));
            var auto=new CheckBox{Text=L.T("Automatically play next"),Checked=settings.AutoNext,AutoSize=true};auto.CheckedChanged+=delegate{settings.AutoNext=auto.Checked;SaveSettings();QueueChanged();};buttons.Controls.Add(auto);
            buttons.Controls.Add(Ui.Label("Playlists load in pages. Failed items stay here for retry. Stopping playback keeps the queue.",760));
            f.FormClosed+=delegate{queueWindow=null;queueList=null;};RefreshQueueRows();f.Show(this);
        }
        void SelectQueue(WatchEntry value){if(queueList!=null)foreach(ListViewItem item in queueList.Items)if(Object.ReferenceEquals(item.Tag,value)){item.Selected=true;item.EnsureVisible();}}
        void RefreshQueueRows(){
            if(queueList==null || queueList.IsDisposed)return;var selected=queueList.SelectedItems.Count==0?null:queueList.SelectedItems[0].Tag as WatchEntry;
            queueList.BeginUpdate();queueList.Items.Clear();foreach(var item in watchQueue)queueList.Items.Add(new ListViewItem(new[]{item.Video.Title,item.Error.Length>0?item.Error:item.Playlist?L.T("Playlist — page {0}",item.Page+1):L.T("Queued")}){Tag=item});queueList.EndUpdate();SelectQueue(selected);
        }
        void OpenDownloads(){
            if(downloadsWindow!=null){downloadsWindow.Activate();return;}
            downloadsWindow=FeatureWindow("Downloads",new Size(970,520));var f=downloadsWindow;
            downloadList=FeatureList(new[]{"Video","Format","State","Progress / details"},new[]{310,90,120,400});f.Controls.Add(downloadList);
            var detail=new TextBox{Dock=DockStyle.Bottom,Height=70,Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Vertical};f.Controls.Add(detail);
            var buttons=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=115,AutoScroll=true};f.Controls.Add(buttons);
            Func<DownloadJob> chosen=()=>downloadList.SelectedItems.Count==0?null:downloadList.SelectedItems[0].Tag as DownloadJob;
            downloadList.SelectedIndexChanged+=delegate{var j=chosen();detail.Text=j==null?"":j.Title+Environment.NewLine+j.Folder+Environment.NewLine+j.Detail;};
            buttons.Controls.Add(Ui.Button("Cancel",delegate{downloads.Cancel(chosen());RefreshDownloadRows();}));
            buttons.Controls.Add(Ui.Button("Retry / resume",delegate{downloads.Retry(chosen());RefreshDownloadRows();}));
            buttons.Controls.Add(Ui.Button("Resume pending",delegate{foreach(var j in downloads.Jobs.Where(x=>x.State=="Interrupted" || x.State=="Cancelled").ToList())downloads.Retry(j);RefreshDownloadRows();}));
            buttons.Controls.Add(Ui.Button("Remove",delegate{downloads.Remove(chosen());RefreshDownloadRows();}));
            buttons.Controls.Add(Ui.Button("Folder",delegate{try{var j=chosen();string path=j==null?settings.DownloadFolder:j.Folder;Directory.CreateDirectory(path);System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path){UseShellExecute=true});}catch(Exception e){Error(e);}}));
            buttons.Controls.Add(Ui.Label("Download limit (KiB/s; 0 = unlimited)",300));var rate=DownloadRateInput();buttons.Controls.Add(rate);
            rate.ValueChanged+=delegate{settings.DownloadLimitKiB=(int)rate.Value;SaveSettings();};
            buttons.Controls.Add(Ui.Label("Applies to new or retried jobs. Cancel and retry an active download to apply the limit.",890));
            buttons.Controls.Add(Ui.Label("One download at a time. Interrupted jobs wait for Resume. Partial files are kept; conversion may restart.",890));
            f.FormClosed+=delegate{downloadsWindow=null;downloadList=null;};RefreshDownloadRows();f.Show(this);
        }
        void RefreshDownloadRows(){
            if(downloadList==null || downloadList.IsDisposed)return;
            var ids=new HashSet<string>(downloads.Jobs.Select(j=>j.Id));
            var existing=downloadList.Items.Cast<ListViewItem>().ToDictionary(x=>((DownloadJob)x.Tag).Id,x=>x);
            downloadList.BeginUpdate();
            for(int i=downloadList.Items.Count-1;i>=0;i--)if(!ids.Contains(((DownloadJob)downloadList.Items[i].Tag).Id))downloadList.Items.RemoveAt(i);
            foreach(var j in downloads.Jobs){ListViewItem row;existing.TryGetValue(j.Id,out row);
                if(row==null){row=new ListViewItem(new[]{j.Title,j.Selection.Kind,"",""}){Tag=j};downloadList.Items.Add(row);}
                string state=L.T(j.State),text=j.State=="Downloading"?j.LastOutput:j.Detail;
                if(row.SubItems[2].Text!=state)row.SubItems[2].Text=state;if(row.SubItems[3].Text!=text)row.SubItems[3].Text=text;
            }downloadList.EndUpdate();
        }
        async void OpenPlaybackStatus(){
            if(playbackWindow!=null){playbackWindow.Activate();return;}
            playbackWindow=FeatureWindow("Playback status",new Size(700,560));var f=playbackWindow;
            playbackText=new TextBox{Dock=DockStyle.Fill,Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Vertical,Font=new Font("Consolas",10F)};f.Controls.Add(playbackText);
            var buttons=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=42};f.Controls.Add(buttons);
            buttons.Controls.Add(Ui.Button("Copy",delegate{try{Clipboard.SetText(playbackText.Text);}catch(Exception e){Error(e);}}));
            buttons.Controls.Add(Ui.Button("Export diagnostics",ExportDiagnostics));
            buttons.Controls.Add(Ui.Button("Player diagnostics",async delegate{await Diagnose();}));
            f.FormClosed+=async delegate{playbackWindow=null;playbackText=null;try{if(player.Alive)await player.Command("script-message","lowtube-status-visible","no");}catch(Exception){}};
            RefreshPlaybackStatus();f.Show(this);
            try{if(player.Alive)await player.Command("script-message","lowtube-status-visible","yes");}catch(Exception e){Error(e);}
        }
        void RefreshPlaybackStatus(){
            if(playbackText==null)return;var p=latestPlayback;var b=new StringBuilder();
            string state=recovering?L.T("Restoring stream…"):playbackStarting?L.T("Starting mpv…"):!player.Running?L.T("Player stopped"):Json.Str(p,"paused-for-cache")=="True"?L.T("Buffering…"):Json.Str(p,"seeking")=="True"?L.T("Seeking…"):Json.Str(p,"pause")=="True"?L.T("Paused"):L.T("Playing");
            b.AppendLine(L.T("State")+": "+state);b.AppendLine(mediaTitle);b.AppendLine();
            b.AppendLine(L.T("Position")+": "+Ui.Time(Number(Json.Str(p,"time-pos"),-1))+" / "+Ui.Time(Number(Json.Str(p,"duration"),-1)));
            string[,] fields={{"Video codec","video-codec"},{"Audio codec","audio-codec"},{"Active decoder","hwdec-current"},{"Video output","current-vo"},{"GPU context","gpu-context"},{"Source FPS","container-fps"},{"Estimated video FPS","estimated-vf-fps"},{"Dropped display frames","frame-drop-count"},{"Dropped decoder frames","decoder-frame-drop-count"},{"A/V difference, s","avsync"},{"Buffered seconds","demuxer-cache-duration"},{"Cache readiness, %","cache-buffering-state"},{"Playback speed","speed"}};
            b.AppendLine(L.T("Resolution")+": "+Json.Str(p,"video-params/w","—")+" × "+Json.Str(p,"video-params/h","—"));
            for(int i=0;i<fields.GetLength(0);i++)b.AppendLine(L.T(fields[i,0])+": "+Json.Str(p,fields[i,1],"—"));
            b.AppendLine();b.AppendLine(L.T("Unavailable metrics are shown as —. Values refresh once per second while this window is open."));
            string text=b.ToString();if(playbackText.Text!=text)playbackText.Text=text;
        }
        [System.Runtime.InteropServices.DllImport("user32.dll")]static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int w,int h,uint flags);
        static void RaiseForm(Form form){if(!form.IsDisposed && form.WindowState!=FormWindowState.Minimized)SetWindowPos(form.Handle,new IntPtr(-1),0,0,0,0,0x13);}
    }
}
