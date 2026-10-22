using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text;
using System.Windows.Forms;
namespace LowTube {
    public sealed partial class MainForm {
        Dictionary<string,WatchedVideo> watched=new Dictionary<string,WatchedVideo>();
        List<LocalPlaylist> localPlaylists=new List<LocalPlaylist>();
        Form localPlaylistWindow;
        ComboBox localPlaylistNames;
        ListView localPlaylistItems;
        string WatchedPath {get{return Path.Combine(Paths.Data,"watched.json");}}
        string LocalPlaylistsPath {get{return Path.Combine(Paths.Data,"local-playlists.json");}}
        void InitializeLibraryFeatures(FlowLayoutPanel nav){
            foreach(var p in Json.Load(WatchedPath,new List<WatchedVideo>())??new List<WatchedVideo>())if(p!=null && p.Key.Length==11)watched[p.Key]=p;
            if(watched.Count==0)foreach(var p in resumePoints.Where(x=>x!=null))WatchedRules.Record(watched,"https://youtu.be/"+p.Key,p.Position,p.Duration,false);
            localPlaylists=LocalPlaylistRules.Clean(Json.Load(LocalPlaylistsPath,new List<LocalPlaylist>()));
            cards.WatchProgress=v=>{WatchedVideo progress;return watched.TryGetValue(ResumeRules.Key(v.Url),out progress)?WatchedRules.Fraction(progress):0;};
            cards.ContextRequested+=(v,point)=>ShowVideoContext(v,cards,point);
            videos.MouseUp+=(sender,e)=>{if(e.Button!=MouseButtons.Right)return;var hit=videos.GetItemAt(e.X,e.Y);if(hit==null)return;videos.SelectedItems.Clear();hit.Selected=true;ShowVideoContext(hit.Tag as Video,videos,e.Location);};
            var button=NavButton("Local playlists",OpenLocalPlaylists);locked.Remove(button);nav.Controls.Add(button);
        }
        void RecordWatched(double pos,double duration,bool complete=false){WatchedRules.Record(watched,mediaUrl,pos,duration,mediaLive,complete);cards.Invalidate();}
        void SaveWatched(){try{Json.Save(WatchedPath,watched.Values.OrderByDescending(x=>x.Updated).Take(5000).ToList());}catch(Exception e){Error(e);}}
        void ClearWatched(Video video){watched.Remove(ResumeRules.Key(video.Url));resumePoints.RemoveAll(x=>x.Key==ResumeRules.Key(video.Url));SaveResume();SaveWatched();cards.Invalidate();}
        void ShowVideoContext(Video video,Control owner,Point point){
            if(video==null)return;var menu=new ContextMenuStrip();
            Action<string,Action> item=(name,action)=>{var entry=menu.Items.Add(L.T(name));entry.Click+=delegate{try{action();}catch(Exception e){Error(e);}};};
            item("Play video",async delegate{await Play(false,video);});item("Audio only",async delegate{await Play(true,video);});
            item("Copy video URL",delegate{Clipboard.SetText(Rules.YouTubeUrl(video.Url));Notify(L.T("Video URL copied."));});
            item("Open in browser",delegate{Process.Start(new ProcessStartInfo(Rules.YouTubeUrl(video.Url)){UseShellExecute=true});});
            menu.Items.Add(new ToolStripSeparator());
            item("Add to queue",delegate{EnqueueVideos(new[]{video});});
            if(video.Kind!="playlist" && !ContentFeeds.IsPlaylist(video.Url)){
                item("Download",async delegate{await Download(video);});item("Add to local playlist",delegate{ChooseLocalPlaylist(video);});
                item("Clear watched progress",delegate{ClearWatched(video);});
            }
            menu.Closed+=delegate{BeginInvoke(new Action(menu.Dispose));};menu.Show(owner,point);
        }
        void EnqueueVideos(IEnumerable<Video> source){
            var items=source.Where(x=>x!=null).ToList();if(watchQueue.Count+items.Count>5000){Notify(L.T("Watch queue is full."));return;}
            watchQueue.AddRange(items.Select(v=>new WatchEntry{Video=v}));SaveQueue();Notify(L.T("Added {0} items to the watch queue.",items.Count));
        }
        string AskText(string caption,string value){
            using(var f=new Form{Text=L.T(caption),ClientSize=new Size(440,115),StartPosition=FormStartPosition.CenterParent,FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false,MinimizeBox=false,Font=Font}){
                var text=new TextBox{Left=15,Top=15,Width=410,Text=value,MaxLength=100};f.Controls.Add(text);
                var ok=Ui.Button("Save",delegate{f.DialogResult=DialogResult.OK;});ok.Location=new Point(240,60);f.Controls.Add(ok);
                var cancel=Ui.Button("Cancel",delegate{f.DialogResult=DialogResult.Cancel;});cancel.Location=new Point(335,60);f.Controls.Add(cancel);f.AcceptButton=ok;f.CancelButton=cancel;Ui.Attach(f);
                return f.ShowDialog(this)==DialogResult.OK?text.Text:null;
            }
        }
        LocalPlaylist NewLocalPlaylist(){
            if(localPlaylists.Count>=200){Notify(L.T("Up to 200 local playlists are supported."));return null;}
            string name=AskText("New playlist","");if(name==null)return null;
            try{var p=new LocalPlaylist{Name=LocalPlaylistRules.Name(name)};localPlaylists.Add(p);SaveLocalPlaylists();RefreshLocalPlaylistNames(p.Id);return p;}catch(Exception e){Error(e);return null;}
        }
        void SaveLocalPlaylists(){try{Json.Save(LocalPlaylistsPath,localPlaylists);}catch(Exception e){Error(e);}}
        void AddLocalVideo(LocalPlaylist p,Video v){
            if(p==null)return;if(p.Items.Count>=1000){Notify(L.T("A local playlist can contain up to 1000 videos."));return;}
            p.Items.Add(v);SaveLocalPlaylists();RefreshLocalPlaylistRows();Notify(L.T("Added to local playlist: {0}",p.Name));
        }
        void ChooseLocalPlaylist(Video video){
            if(localPlaylists.Count==0){AddLocalVideo(NewLocalPlaylist(),video);return;}
            using(var f=FeatureWindow("Add to local playlist",new Size(620,360))){
                var list=new ListBox{Dock=DockStyle.Fill};foreach(var p in localPlaylists)list.Items.Add(p.Name);list.SelectedIndex=0;f.Controls.Add(list);
                var buttons=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=50};f.Controls.Add(buttons);
                buttons.Controls.Add(Ui.Button("Add",delegate{if(list.SelectedIndex>=0){AddLocalVideo(localPlaylists[list.SelectedIndex],video);f.Close();}}));
                buttons.Controls.Add(Ui.Button("New playlist",delegate{var p=NewLocalPlaylist();if(p!=null){AddLocalVideo(p,video);f.Close();}}));f.ShowDialog(this);
            }
        }
        LocalPlaylist SelectedLocalPlaylist(){return localPlaylistNames==null || localPlaylistNames.SelectedIndex<0 || localPlaylistNames.SelectedIndex>=localPlaylists.Count?null:localPlaylists[localPlaylistNames.SelectedIndex];}
        void RefreshLocalPlaylistNames(string id=null){
            if(localPlaylistNames==null)return;id=id??(SelectedLocalPlaylist()==null?"":SelectedLocalPlaylist().Id);
            localPlaylistNames.BeginUpdate();localPlaylistNames.Items.Clear();foreach(var p in localPlaylists)localPlaylistNames.Items.Add(p.Name);localPlaylistNames.EndUpdate();
            int index=localPlaylists.FindIndex(x=>x.Id==id);localPlaylistNames.SelectedIndex=localPlaylists.Count==0?-1:Math.Max(0,index);RefreshLocalPlaylistRows();
        }
        void RefreshLocalPlaylistRows(int selected=-1){
            if(localPlaylistItems==null)return;var p=SelectedLocalPlaylist();localPlaylistItems.BeginUpdate();localPlaylistItems.Items.Clear();
            if(p!=null)foreach(var v in p.Items)localPlaylistItems.Items.Add(new ListViewItem(new[]{v.Title,v.Channel}){Tag=v});localPlaylistItems.EndUpdate();
            if(selected>=0 && selected<localPlaylistItems.Items.Count){localPlaylistItems.Items[selected].Selected=true;localPlaylistItems.Items[selected].EnsureVisible();}
        }
        void OpenLocalPlaylists(){
            if(localPlaylistWindow!=null){localPlaylistWindow.Activate();return;}
            var f=FeatureWindow("Local playlists",new Size(900,550));localPlaylistWindow=f;
            localPlaylistItems=FeatureList(new[]{"Video","Channel"},new[]{560,260});f.Controls.Add(localPlaylistItems);
            var top=new FlowLayoutPanel{Dock=DockStyle.Top,Height=45};f.Controls.Add(top);localPlaylistNames=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=300};localPlaylistNames.SelectedIndexChanged+=delegate{RefreshLocalPlaylistRows();};top.Controls.Add(localPlaylistNames);
            top.Controls.Add(Ui.Button("New playlist",delegate{NewLocalPlaylist();}));
            top.Controls.Add(Ui.Button("Rename",delegate{var p=SelectedLocalPlaylist();if(p==null)return;string name=AskText("Rename",p.Name);if(name==null)return;try{p.Name=LocalPlaylistRules.Name(name);SaveLocalPlaylists();RefreshLocalPlaylistNames(p.Id);}catch(Exception e){Error(e);}}));
            top.Controls.Add(Ui.Button("Delete playlist",delegate{var p=SelectedLocalPlaylist();if(p!=null && MessageBox.Show(f,L.T("Delete local playlist “{0}”?",p.Name),L.T("Delete playlist"),MessageBoxButtons.YesNo)==DialogResult.Yes){localPlaylists.Remove(p);SaveLocalPlaylists();RefreshLocalPlaylistNames();}}));
            var bottom=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=115,AutoScroll=true};f.Controls.Add(bottom);
            bottom.Controls.Add(Ui.Button("Play playlist",async delegate{
                var p=SelectedLocalPlaylist();if(p==null || p.Items.Count==0 || operation!=null || advancingQueue)return;
                if(watchQueue.Count+p.Items.Count>5000){Notify(L.T("Watch queue is full."));return;}
                await ResetContinuity();watchQueue.InsertRange(0,p.Items.Select(v=>new WatchEntry{Video=v}));SaveQueue();mediaAudio=false;await AdvanceQueue(true);
            }));
            bottom.Controls.Add(Ui.Button("Add to queue",delegate{var p=SelectedLocalPlaylist();if(p!=null)EnqueueVideos(p.Items);}));
            foreach(int dir in new[]{-1,1}){int d=dir;bottom.Controls.Add(Ui.Button(d<0?"Move up":"Move down",delegate{var p=SelectedLocalPlaylist();int i=localPlaylistItems.SelectedIndices.Count==0?-1:localPlaylistItems.SelectedIndices[0];if(p==null || i<0 || i+d<0 || i+d>=p.Items.Count)return;QueueRules.Move(p.Items,i,i+d);SaveLocalPlaylists();RefreshLocalPlaylistRows(i+d);}));}
            bottom.Controls.Add(Ui.Button("Remove",delegate{var p=SelectedLocalPlaylist();if(p==null || localPlaylistItems.SelectedIndices.Count==0)return;int i=localPlaylistItems.SelectedIndices[0];p.Items.RemoveAt(i);SaveLocalPlaylists();RefreshLocalPlaylistRows(Math.Min(i,p.Items.Count-1));}));
            var url=new TextBox{Width=430,MaxLength=2048};bottom.Controls.Add(url);
            bottom.Controls.Add(Ui.Button("Add video URL",delegate{try{var p=SelectedLocalPlaylist();if(p==null)return;string clean=Rules.YouTubeUrl(url.Text);if(ResumeRules.Key(clean).Length==0)throw new ArgumentException(L.T("Enter a video URL, not a playlist URL."));AddLocalVideo(p,new Video{Url=clean,Id=ResumeRules.Key(clean),Title=clean});url.Clear();}catch(Exception e){Error(e);}}));
            localPlaylistItems.DoubleClick+=async delegate{if(localPlaylistItems.SelectedItems.Count>0)await Play(false,localPlaylistItems.SelectedItems[0].Tag as Video);};
            localPlaylistItems.MouseUp+=(sender,e)=>{if(e.Button==MouseButtons.Right){var hit=localPlaylistItems.GetItemAt(e.X,e.Y);if(hit!=null)ShowVideoContext(hit.Tag as Video,localPlaylistItems,e.Location);}};
            f.FormClosed+=delegate{localPlaylistWindow=null;localPlaylistItems=null;localPlaylistNames=null;};RefreshLocalPlaylistNames();f.Show(this);
        }
        NumericUpDown DownloadRateInput(){return new NumericUpDown{Minimum=0,Maximum=1048576,Value=Math.Max(0,Math.Min(1048576,settings.DownloadLimitKiB)),Width=120,ThousandsSeparator=true,Increment=128};}
        void ExportDiagnostics(){
            try{using(var dialog=new SaveFileDialog{Title=L.T("Export diagnostics"),Filter="Text (*.txt)|*.txt",FileName="LowTube-diagnostics-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+".txt"}){
                if(dialog.ShowDialog(this)!=DialogResult.OK)return;
                var versions=new StringBuilder();versions.AppendLine("LowTube: "+typeof(MainForm).Assembly.GetName().Version);
                foreach(string tool in new[]{"mpv","yt-dlp","ffmpeg","ffprobe","node"}){
                    try{var path=Paths.Tool(tool);versions.AppendLine(tool+": "+(File.Exists(path)?DiagnosticReport.Atom(FileVersionInfo.GetVersionInfo(path).FileVersion):"not installed"));}catch(Exception){versions.AppendLine(tool+": unavailable");}
                }
                File.WriteAllText(dialog.FileName,DiagnosticReport.Build(settings,latestPlayback,continuityEvents,watchQueue.Count,downloads.Jobs.Count,versions.ToString()),new UTF8Encoding(true));Notify(L.T("Diagnostics exported."));
            }}catch(Exception e){Error(e);}
        }
    }
}
