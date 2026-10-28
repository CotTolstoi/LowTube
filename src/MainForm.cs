using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace LowTube {
    public sealed partial class MainForm : Form {
        Settings settings;
        List<Video> history;
        List<Video> subscriptionFeed=new List<Video>();
        readonly Player player=new Player();
        CancellationTokenSource operation;
        TextBox search,log;
        ListView videos;
        VideoGrid cards;Panel videoHost;Label feedTitle;CheckBox pin;bool updatingPin;
        ComboBox quality,decoder,renderer,contentType,channelFilter;
        List<Video> displayRows=new List<Video>();bool changingFilters;string displayedLabel="";
        string ContentKind {get{return contentType==null?"all":ContentFeeds.Kinds[Math.Max(0,contentType.SelectedIndex)];}}
        string ChannelFilterId {get{var c=channelFilter==null?null:channelFilter.SelectedItem as LocalChannel;return c==null?"":c.Id;}}
        AudioForm audioWindow;
        string mediaTitle="",mediaUrl="";bool mediaAudio,videoSettingsOpen,playbackStarting,initializing=true;
        CheckBox avc,fps;
        Label status,now,accountLabel;
        Button cancel,previousPage,nextPageButton;
        int PageSize {get{return Paging.Size(settings.PageSize);}}
        readonly List<Control> locked=new List<Control>();
        string currentTarget="",currentLabel="";
        bool currentAccount;
        int page;
        bool pageHasMore;
        bool closing,diagnosing;
        string playbackDetails="";
        
        public MainForm() {
            SuspendLayout();StartupTrace.Mark("main window construction");
            Directory.CreateDirectory(Paths.Data);
            settings=Json.Load(Path.Combine(Paths.Data,"settings.json"),new Settings())??new Settings();
            L.Set(settings.Language);Theme.Set(settings.Theme);
            history=new List<Video>();StartupTrace.Mark("settings loaded");
            Text=L.T("LowTube 0.12.2 — lightweight YouTube client");
            Size=new Size(1200,820); MinimumSize=new Size(980,680); StartPosition=FormStartPosition.CenterScreen;
            Font=new Font("Segoe UI",9F); BackColor=Color.FromArgb(245,247,250);
            AutoScaleMode=AutoScaleMode.Dpi;
            var shell=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=2,RowCount=2,Padding=new Padding(14,10,14,8)};
            shell.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,200));shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
            shell.RowStyles.Add(new RowStyle(SizeType.Absolute,58));shell.RowStyles.Add(new RowStyle(SizeType.Percent,100));Controls.Add(shell);
            var brand=new Label {Text="▶ LowTube",Tag="brand",Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleCenter,Font=new Font("Segoe UI",17F,FontStyle.Bold),Margin=new Padding(0,0,20,12)};shell.Controls.Add(brand,0,0);
            var layout=new TableLayoutPanel { Dock=DockStyle.Fill,ColumnCount=1,RowCount=8,Padding=new Padding(12,0,0,0) };
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
            foreach(float h in new float[]{40,40,76})layout.RowStyles.Add(new RowStyle(SizeType.Absolute,h));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent,100));
            foreach(float h in new float[]{42,40,62,28})layout.RowStyles.Add(new RowStyle(SizeType.Absolute,h));
            shell.Controls.Add(layout,1,1);
            var top=new TableLayoutPanel { Dock=DockStyle.Fill,ColumnCount=3,RowCount=1 };
            top.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100)); top.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,115)); top.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,125));
            search=new TextBox { Dock=DockStyle.Fill,Margin=new Padding(0,5,8,0),Font=new Font("Segoe UI",11F) };
            search.KeyDown+=async delegate(object sender,KeyEventArgs e) { if(e.KeyCode==Keys.Enter) { e.SuppressKeyPress=true; if(operation==null && !initializing) await Search(); } };
            top.Controls.Add(search,0,0); top.Controls.Add(Button(L.T("Search / URL"),async delegate { await Search(); },true),1,0);
            top.Controls.Add(Button(L.T("Account"),delegate { Account(); },true),2,0);
            shell.Controls.Add(top,1,0);
            var nav=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(0,4,12,0)};
            nav.Controls.Add(NavButton("Recommendations",async delegate{await Recommendations(0);}));
            nav.Controls.Add(NavButton("Subscriptions",async delegate{await Subscriptions();}));
            nav.Controls.Add(NavButton("YouTube subscriptions",async delegate{await SubscriptionView(true);}));
            nav.Controls.Add(NavButton("YouTube recommendations",async delegate{await Browse(":ytrec",L.T("YouTube recommendations"),true,0);}));
            nav.Controls.Add(NavButton("Synchronize",async delegate{await Synchronize();}));
            nav.Controls.Add(NavButton("YouTube history",async delegate{await Browse(":ythistory",L.T("YouTube history"),true,0);}));
            nav.Controls.Add(NavButton("Local history",delegate{HistoryView(0);}));
            nav.Controls.Add(NavButton("Channels",async delegate{await ManageChannels();}));
            nav.Controls.Add(NavButton("Folder",delegate{OpenFolder();}));
            nav.Controls.Add(NavButton("Settings",delegate{Preferences();}));
            nav.Controls.Add(NavButton("Equalizer",delegate{OpenEqualizer();}));
            accountLabel=new Label{AutoSize=true,MaximumSize=new Size(174,0),Margin=new Padding(10,20,0,0)};nav.Controls.Add(accountLabel);shell.Controls.Add(nav,0,1);
            feedTitle=new Label{AutoEllipsis=true,Text=L.T("Local history"),Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleLeft,Font=new Font("Segoe UI",14F,FontStyle.Bold),Tag="data"};layout.Controls.Add(feedTitle,0,0);
            var views=Flow();
            views.Controls.Add(Button(L.T("Cards"),delegate{SetView(true);},false));views.Controls.Add(Button(L.T("List"),delegate{SetView(false);},false));
            contentType=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=130};foreach(string name in ContentFeeds.Labels)contentType.Items.Add(L.T(name));contentType.SelectedIndex=0;views.Controls.Add(contentType);locked.Add(contentType);
            channelFilter=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=170,DisplayMember="Title"};channelFilter.Items.Add(new LocalChannel{Title=L.T("All channels")});channelFilter.SelectedIndex=0;views.Controls.Add(channelFilter);locked.Add(channelFilter);
            contentType.SelectedIndexChanged+=async delegate{if(changingFilters)return;if(currentTarget==":local-subscriptions" || currentTarget==":youtube-subscriptions")await SubscriptionView(currentTarget==":youtube-subscriptions");else if(currentTarget==":guest-recommendations")await Recommendations(0);else if(currentTarget==":ytrec")await RecommendationView(true,0);else if(currentTarget==":local-history")HistoryView(0);else if(currentTarget==":ythistory")await Browse(currentTarget,currentLabel,currentAccount,0);else if(currentLabel==L.T("Search results"))await Search();else ApplyContentFilters();};
            channelFilter.SelectedIndexChanged+=async delegate{if(!changingFilters && (currentTarget==":local-subscriptions" || currentTarget==":youtube-subscriptions"))await SubscriptionView(currentTarget==":youtube-subscriptions");};
            previousPage=Button(L.T("← Previous"),async delegate{await ChangePage(Math.Max(0,page-1));},true);
            nextPageButton=Button(L.T("Next →"),async delegate{await NextPage();},true);
            var navigation=new FlowLayoutPanel{AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,WrapContents=false,Margin=Padding.Empty};navigation.Controls.Add(previousPage);navigation.Controls.Add(nextPageButton);views.Controls.Add(navigation);layout.Controls.Add(views,0,1);
            var options=new TableLayoutPanel { Dock=DockStyle.Fill,RowCount=2,ColumnCount=1 };
            options.RowStyles.Add(new RowStyle(SizeType.Percent,50)); options.RowStyles.Add(new RowStyle(SizeType.Percent,50));
            var row1=Flow(); quality=Combo(new[]{"144","240","360","480","720","1080","1440","2160"},settings.Height.ToString(),78);
            avc=new CheckBox { Text=L.T("H.264 only"),Checked=settings.H264,AutoSize=true,Margin=new Padding(9,7,2,0) };
            fps=new CheckBox { Text=L.T("Playback: up to 30 FPS"),Checked=settings.Fps30,AutoSize=true,Margin=new Padding(9,7,2,0) };
            row1.Controls.Add(Label(L.T("Max. height, px:"))); row1.Controls.Add(quality); row1.Controls.Add(avc); row1.Controls.Add(fps);
            var row2=Flow(); decoder=Combo(Rules.Decoders,settings.Decoder,140);renderer=Combo(Rules.Renderers,settings.Renderer,98);
            Action matchRenderer=delegate{renderer.SelectedItem=Rules.CompatibleRenderer((string)decoder.SelectedItem,(string)renderer.SelectedItem);};
            decoder.SelectedIndexChanged+=delegate{matchRenderer();};renderer.SelectedIndexChanged+=delegate{matchRenderer();};matchRenderer();
            row2.Controls.Add(Label(L.T("Decoder:")));row2.Controls.Add(decoder);row2.Controls.Add(Label(L.T("Renderer:")));row2.Controls.Add(renderer);
            row2.Controls.Add(Button(L.T("Available formats"),async delegate { await Formats(); },true));
            row2.Controls.Add(Button(L.T("Player diagnostics"),async delegate { await Diagnose(); },false));
            options.Controls.Add(row1,0,0);options.Controls.Add(row2,0,1);layout.Controls.Add(options,0,2);
            foreach(Control c in new Control[]{quality,avc,fps,decoder,renderer}) locked.Add(c);
            videos=new ListView { Dock=DockStyle.Fill,View=View.Details,FullRowSelect=true,MultiSelect=false,HideSelection=false,BorderStyle=BorderStyle.FixedSingle };
            videos.Columns.Add(L.T("Video"),570);videos.Columns.Add(L.T("Channel"),210);videos.Columns.Add(L.T("Duration"),85);videos.Columns.Add(L.T("Date"),145);
            videos.DoubleClick+=async delegate { if(operation==null) await Play(false); };
            videoHost=new Panel{Dock=DockStyle.Fill};videoHost.Controls.Add(videos);
            cards=new VideoGrid{Dock=DockStyle.Fill};cards.Activated+=async delegate{if(operation==null)await Play(false);};videoHost.Controls.Add(cards);
            cards.Visible=settings.CardView;videos.Visible=!settings.CardView;layout.Controls.Add(videoHost,0,3);
            var actions=Flow();
            actions.Controls.Add(Button(L.T("▶ Video"),async delegate { await Play(false); },true));
            actions.Controls.Add(Button(L.T("♫ Audio only"),async delegate { await Play(true); },true));
            actions.Controls.Add(Button(L.T("Download"),async delegate { await Download(); },true));
            actions.Controls.Add(Button(L.T("Subscribe locally"),async delegate { await Subscribe(); },true));



            cancel=Button(L.T("Cancel"),delegate { if(operation!=null) operation.Cancel(); },false);cancel.Enabled=false;actions.Controls.Add(cancel);layout.Controls.Add(actions,0,4);
            var controls=Flow();
            controls.Controls.Add(Button(L.T("Pause"),async delegate { await Control("cycle","pause"); },false));
            controls.Controls.Add(Button(L.T("−10 s"),async delegate { await Control("seek",-10,"relative"); },false));
            controls.Controls.Add(Button(L.T("+10 s"),async delegate { await Control("seek",10,"relative"); },false));
            controls.Controls.Add(Button(L.T("Video settings"),async delegate { await VideoSettings(); },false));
            controls.Controls.Add(Button(L.T("Stop"),delegate { StopPlayer(); },false));
            pin=new CheckBox{Text=L.T("Always on top"),AutoSize=true,Checked=settings.PlayerOnTop,Margin=new Padding(5,8,4,0)};
            pin.CheckedChanged+=async delegate{if(updatingPin)return;try{settings.PlayerOnTop=pin.Checked;SaveSettings();if(audioWindow!=null)audioWindow.TopMost=pin.Checked;if(player.Running && !mediaAudio)await player.Command("set_property","ontop",pin.Checked);}catch(Exception e){Error(e);}};controls.Controls.Add(pin);
            now=new Label { AutoSize=true,Margin=new Padding(10,8,0,0),MaximumSize=new Size(200,23) };controls.Controls.Add(now);layout.Controls.Add(controls,0,5);
            log=new TextBox { Dock=DockStyle.Fill,Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Vertical,Font=new Font("Consolas",8.5F),BackColor=Color.White };
            layout.Controls.Add(log,0,6);status=new Label { Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleLeft };layout.Controls.Add(status,0,7);
            InitializeWorkspace(nav,actions,controls,layout);
            UpdateAccountLabel(); HistoryView(0);
            foreach(var control in locked)control.Enabled=false;
            bool painted=false;Paint+=async delegate{if(painted)return;painted=true;StartupTrace.Mark("first window paint");await StartupTrace.Save();};
            Shown+=async delegate {
                StartupTrace.Mark("window shown");await Task.Yield();StartupTrace.Mark("UI message loop ready");
                status.Text=L.T("Loading local history…");
                try{
                    var loaded=await Task.Run(delegate{return Json.Load(Path.Combine(Paths.Data,"history.json"),new List<Video>())??new List<Video>();});
                    if(closing)return;history=loaded.Where(v=>v!=null).Take(500).ToList();HistoryView(0);
                    string missing=await Task.Run(delegate{foreach(string name in new[]{"yt-dlp","mpv","ffmpeg","ffprobe","qjs"})try{Paths.Tool(name);}catch(FileNotFoundException){return name;}return "";});
                    if(closing)return;if(missing.Length>0)Append(L.T("Close LowTube and run SETUP.cmd first. Internet access is required to install components."));
                    else if(!File.Exists(Path.Combine(Paths.Root,"tools","yt-dlp","yt-dlp.exe")))Append(L.T("For faster HDD startup, close LowTube and run UPDATE_YTDLP.cmd once."));
                }catch(Exception e){if(!closing)Error(e);}
                finally{initializing=false;if(!closing){foreach(var control in locked)control.Enabled=true;UpdatePageButtons();StartupTrace.Mark("history and tool checks ready");}}
                if(!closing){StartFeeds();await StartupTrace.Save();}
                if(!closing)try{await player.Prepare(settings,Append);}catch(Exception e){if(!closing)Append(Rules.Redact(e.Message));}
            };
            player.PlaybackEnded+=(endedSession,preparedNext)=>{if(!closing && !IsDisposed)BeginInvoke(new Action(async delegate{try{await OnEnded(endedSession,preparedNext);}catch(Exception e){Error(e);}}));};
            player.QuickSettingRequested+=delegate(string key,string value){if(!closing && !IsDisposed)BeginInvoke(new Action(async delegate{await VideoSettings(key,value);}));};
            player.SettingsRequested+=delegate{if(!closing && !IsDisposed)BeginInvoke(new Action(async delegate{await VideoSettings();}));};
            Action languageChanged=delegate{
                changingFilters=true;int typeIndex=contentType.SelectedIndex;contentType.Items.Clear();foreach(string name in ContentFeeds.Labels)contentType.Items.Add(L.T(name));contentType.SelectedIndex=typeIndex;changingFilters=false;
                UpdateAccountLabel();status.Text=L.T("Ready");
                currentLabel=L.Relocalize(currentLabel);
                now.Text=player.Running?L.T(mediaAudio?"Audio only: {0}":"Video: {0}",mediaTitle):L.T("Player stopped");
            };
            L.Changed+=languageChanged;Disposed+=delegate{L.Changed-=languageChanged;};Ui.Attach(this);
            ResumeLayout(true);StartupTrace.Mark("main controls ready");
            FormClosing+=async delegate(object sender,FormClosingEventArgs e){await CloseWorkspace(e);};
        }
        Button NavButton(string text,Action action){var b=Button(L.T(text),action,true);b.AutoSize=false;b.Size=new Size(174,42);b.TextAlign=ContentAlignment.MiddleLeft;b.Padding=new Padding(12,0,0,0);return b;}
        Video SelectedVideo(){return settings.CardView?cards.Selected:videos.SelectedItems.Count>0?(Video)videos.SelectedItems[0].Tag:null;}
        void SetView(bool value){var selected=SelectedVideo();settings.CardView=value;cards.Visible=value;videos.Visible=!value;if(value)cards.SelectVideo(selected);else{foreach(ListViewItem item in videos.Items)item.Selected=Object.ReferenceEquals(item.Tag,selected);}SaveSettings();}
        static FlowLayoutPanel Flow() { return new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false,AutoScroll=true,Margin=Padding.Empty }; }
        static Label Label(string text) { return new Label { Text=text,AutoSize=true,Margin=new Padding(3,8,4,0) }; }
        Button Button(string text,Action click,bool busy) {
            var b=new Button { Text=text,AutoSize=true,Height=29,MinimumSize=new Size(0,28),Margin=new Padding(0,2,7,2),Padding=new Padding(5,0,5,0) };
            b.Click+=delegate { try{click();}catch(Exception e){Error(e);} };if(busy)locked.Add(b);return b;
        }
        static ComboBox Combo(string[] items,string selected,int width) {
            var c=new ComboBox { DropDownStyle=ComboBoxStyle.DropDownList,Width=width,Margin=new Padding(0,4,6,0),FormattingEnabled=true };
            c.Format+=delegate(object sender,ListControlConvertEventArgs e){string id=e.ListItem as string;if(id=="auto")e.Value=L.T("Automatic");else if(id=="no")e.Value=L.T("Software (no)");};
            c.Items.AddRange(items);c.SelectedItem=selected;if(c.SelectedIndex<0)c.SelectedIndex=0;return c;
        }
        void SaveSettings() {
            settings.Height=Int32.Parse((string)quality.SelectedItem);settings.H264=avc.Checked;settings.Fps30=fps.Checked;
            settings.Decoder=(string)decoder.SelectedItem;settings.Renderer=(string)renderer.SelectedItem;
            Json.Save(Path.Combine(Paths.Data,"settings.json"),settings);
        }
        void Append(string text) {
            if(closing || IsDisposed) return;
            if(InvokeRequired) { try{BeginInvoke(new Action<string>(Append),text);}catch(InvalidOperationException){} return; }
            if(log.TextLength>22000) log.Text=log.Text.Substring(log.TextLength-12000);
            log.AppendText(Rules.Redact(text).TrimEnd()+Environment.NewLine);
        }
        void Error(Exception e) { if(closing)return;Append(e.Message);Notify(L.T("Request failed. Click here for details.")+" "+e.Message); }
        async Task Work(string title,Func<CancellationToken,Task> action) {
            if(operation!=null || initializing || closePending)return;
            operation=new CancellationTokenSource();var own=operation;
            foreach(var c in locked)c.Enabled=false;cancel.Enabled=true;status.Text=title;Notify(title);
            try{if(preparingNext){if(preloadCancel!=null)preloadCancel.Cancel();while(preparingNext)await Task.Delay(25);}await PauseFeeds();SaveSettings();await action(own.Token);if(!closing){status.Text=L.T("Done");if(notice.Text==title)Notify(L.T("Done"));}}
            catch(OperationCanceledException){if(!closing){status.Text=L.T("Cancelled");Notify(status.Text);}}
            catch(Exception e){if(!closing){status.Text=L.T("Request failed");Error(e);}}
            finally{operation=null;own.Dispose();if(!closing){foreach(var c in locked)c.Enabled=true;cancel.Enabled=false;UpdatePageButtons();}}
        }
        async Task Search() {
            try{await Browse(ContentFeeds.SearchTarget(search.Text,ContentKind),L.T("Search results"),false,0);}catch(Exception e){Error(e);}
        }
        async Task Browse(string target,string label,bool account,int nextPage) {
            if(target==":ytrec"){await RecommendationView(true,nextPage);return;}
            await Work(L.T("Loading list…"),async delegate(CancellationToken ct) {
                var result=target==":ythistory"?await new Backend(settings).BrowseVisiblePage(target,nextPage,account,ContentKind,ct,Append):await new Backend(settings).BrowsePage(target,nextPage,account,ct,Append);
                currentTarget=target;currentLabel=label;currentAccount=account;page=nextPage;pageHasMore=result.HasMore;ShowVideos(result.Items,label+L.T(" — page ")+(nextPage+1));
                if(result.Items.Count==0){status.Text=L.T("No results on this page.");Append(status.Text);}
            });
        }
        Task Recommendations(int nextPage){return RecommendationView(false,nextPage);}
        async Task SyncFeeds(CancellationToken ct){
            var data=ContentFeeds.Load();data.SessionStamp=SavedSession.Stamp;var backend=new Backend(settings);int failed=0;
            try{data.Channels=await backend.SessionChannels(ct,Append);}catch(OperationCanceledException){throw;}catch(Exception e){failed++;Append(L.T("YouTube channels could not be synchronized: {0}",e.Message));}
            ct.ThrowIfCancellationRequested();ContentFeeds.Save(data);
            try{data.Subscriptions=await backend.Browse(":ytsubs",0,true,ct,Append);}catch(OperationCanceledException){throw;}catch(Exception e){failed++;Append(L.T("YouTube subscriptions could not be synchronized: {0}",e.Message));}
            ct.ThrowIfCancellationRequested();ContentFeeds.Save(data);
            recommendationCache.Clear();QueueFeedRefresh();
            Append(L.T("YouTube channels saved: {0}",data.Channels.Count));
            if(failed>0)Append(L.T("The session was saved, but some YouTube lists could not be refreshed. Retry Synchronize."));
        }
        async Task Synchronize(){
            await Work(L.T("Synchronizing..."),async delegate(CancellationToken ct){
                string browser=BrowserSessions.Browser(settings),profile=BrowserSessions.Profile(settings);
                if(String.IsNullOrWhiteSpace(profile) || !Directory.Exists(profile)){
                    var found=await Task.Run(()=>BrowserDiscovery.Detect(ct),ct);
                    var choice=found.Profiles.FirstOrDefault();if(choice==null)throw new InvalidOperationException(L.T("No compatible profile found. Open your browser, sign in to YouTube, then try again."));
                    browser=choice.Browser;profile=choice.Profile;Append(L.T("Importing from: {0}",choice.Label));
                }
                await SavedSession.CaptureBrowser(browser,profile,ct,true);ct.ThrowIfCancellationRequested();
                settings.Auth="saved";settings.AuthMedia=true;settings.BrowserName=browser;settings.BrowserProfile=profile;SaveSettings();UpdateAccountLabel();
                await SyncFeeds(ct);currentTarget=":youtube-subscriptions";page=0;ConfigureChannels(ContentFeeds.Load().Channels);ShowVideos(ContentFeeds.Load().Subscriptions,L.T("YouTube subscriptions"));
            });
        }
        void ConfigureChannels(List<LocalChannel> channels){
            string id=ChannelFilterId;changingFilters=true;
            try{channelFilter.Items.Clear();channelFilter.Items.Add(new LocalChannel{Title=L.T("All channels")});foreach(var c in channels)channelFilter.Items.Add(c);channelFilter.SelectedIndex=0;for(int i=1;i<channelFilter.Items.Count;i++)if(((LocalChannel)channelFilter.Items[i]).Id==id)channelFilter.SelectedIndex=i;}finally{changingFilters=false;}
        }
        async Task Subscriptions(){await SubscriptionView(false);}
        async Task SubscriptionView(bool youtube,int nextPage=0){
            BeginFeedView(L.T(youtube?"YouTube subscriptions":"Subscription videos"));
            var channels=youtube?ContentFeeds.Load().Channels:LocalFeeds.Load();
            if(!youtube && channels.Count==0){await ManageChannels();return;}
            if(youtube && settings.Auth=="none"){Error(new InvalidOperationException(L.T("Connect a browser session in Account first.")));return;}
            currentTarget=youtube?":youtube-subscriptions":":local-subscriptions";currentAccount=youtube;page=nextPage;pageHasMore=false;ConfigureChannels(channels);
            ShowVideos(new List<Video>(),L.T(youtube?"YouTube subscriptions":"Subscription videos")+L.T(" — page ")+(page+1));
            if(feedsReady){
                string wantedTarget=currentTarget,wantedKind=ContentKind,wantedChannel=ChannelFilterId;int wantedPage=page;
                var chosen=channels.Where(c=>ChannelFilterId.Length==0 || c.Id==ChannelFilterId).ToList();
                foreach(var c in chosen)foreach(string type in ContentKind=="all"?ContentFeeds.Kinds.Skip(1):new[]{ContentKind}){
                    string prefix=FeedScope(youtube)+"|"+c.Id+"|"+type+"|";attemptedFeedKeys.RemoveWhere(key=>key.StartsWith(prefix,StringComparison.Ordinal));
                }
                await DisplaySubscriptionCache(true);
                if(currentTarget!=wantedTarget || ContentKind!=wantedKind || page!=wantedPage || ChannelFilterId!=wantedChannel)return;
                QueueChannels(chosen,youtube,ContentKind,0,0,true);PromoteSubscriptionRequest(youtube,wantedKind,wantedPage);
            }else{status.Text=L.T("Waiting for feed service initialization.");Append(status.Text);}
            if(channels.Count==0 && youtube){status.Text=L.T("Synchronize to import the subscribed channel list.");Append(status.Text);}
        }
        void UpdatePageButtons(){
            if(previousPage==null || nextPageButton==null)return;
            bool available=!initializing && operation==null && currentTarget.Length>0;
            previousPage.Enabled=available && page>0;
            nextPageButton.Enabled=available && pageHasMore;
            if(currentTarget==":local-history")nextPageButton.Enabled=available && (page+1)*PageSize<ContentFeeds.Filter(history,ContentKind,"").Count();
        }
        void HistoryView(int number){
            currentTarget=":local-history";currentAccount=false;page=Math.Max(0,number);currentLabel=L.T("Local playback history");
            var filtered=ContentFeeds.Filter(history,ContentKind,"");ShowVideos(filtered.Skip(page*PageSize).Take(PageSize),currentLabel+L.T(" — page ")+(page+1));
        }
        Task NextPage(){return ChangePage(page+1);}
        async Task ChangePage(int number){
            if(operation!=null || initializing)return;number=Math.Max(0,number);
            if(number>page && nextPageButton!=null && !nextPageButton.Enabled)return;
            if(currentTarget==":local-history"){HistoryView(number);return;}
            if(currentTarget==":local-subscriptions" || currentTarget==":youtube-subscriptions")await SubscriptionView(currentTarget==":youtube-subscriptions",number);
            else if(currentTarget==":guest-recommendations")await Recommendations(number);
            else if(currentTarget.Length>0)await Browse(currentTarget,currentLabel,currentAccount,number);
        }
        async Task Subscribe(){
            await Work(L.T("Adding channel…"),async delegate(CancellationToken ct){
                Video chosen=SelectedVideo();
                var channel=chosen!=null && LocalFeeds.ValidChannelId(chosen.ChannelId)?new LocalChannel{Id=chosen.ChannelId,Title=chosen.Channel}:
                    await new Backend(settings).ResolveChannel(SelectedUrl(),ct,Append);
                ct.ThrowIfCancellationRequested();var channels=LocalFeeds.Load();
                if(LocalFeeds.Add(channels,channel)){LocalFeeds.Save(channels);QueueChannels(new[]{channel},false,"all",0,1,true);Append(L.T("Subscribed locally: {0}",channel.Title));}
                else Append(L.T("Already subscribed: {0}",channel.Title));
            });
        }
        async Task ManageChannels(){
            using(var dialog=new ChannelsForm(settings)) {
                bool show=dialog.ShowDialog(this)==DialogResult.OK;
                // Refresh cached rows after removals, too.
                var ids=new HashSet<string>(LocalFeeds.Load().Select(c=>c.Id));
                if(currentTarget!=":youtube-subscriptions")subscriptionFeed=subscriptionFeed.Where(v=>ids.Contains(v.ChannelId)).ToList();
                if(currentTarget==":local-subscriptions"){page=0;ShowVideos(subscriptionFeed.Take(PageSize),L.T("Subscription videos"));}
                if(show && ids.Count>0)await Subscriptions();
            }
        }
        void ShowVideos(IEnumerable<Video> list,string label) {
            displayRows=list.Where(v=>v!=null).ToList();displayedLabel=label;ApplyContentFilters();
        }
        void ApplyContentFilters(bool preserve=false){
            bool subscriptions=currentTarget==":local-subscriptions" || currentTarget==":youtube-subscriptions";
            if(channelFilter!=null)channelFilter.Visible=subscriptions;
            var snapshot=ContentFeeds.Filter(displayRows,ContentKind,subscriptions?ChannelFilterId:"").ToList();cards.SetVideos(snapshot,preserve);feedTitle.Text=displayedLabel;
            videos.BeginUpdate();videos.Items.Clear();foreach(var v in snapshot){var item=new ListViewItem(new[]{v.Title,v.Channel,v.Kind=="playlist"?L.T("Playlist"):v.LiveStatus=="is_live"?L.T("LIVE"):v.Duration,v.Date}){Tag=v};videos.Items.Add(item);}videos.EndUpdate();status.Text=displayedLabel;
            UpdatePageButtons();
            if(snapshot.Count==0 && displayRows.Count>0)status.Text=L.T("No matching content on this page. Try the next page or another filter.");
        }
        string SelectedUrl() {
            var chosen=SelectedVideo();if(chosen!=null)return chosen.Url;
            return Rules.YouTubeUrl(search.Text);
        }
        async Task Play(bool audio,Video requested=null,bool continuing=false) {
            if(stoppingPlayer || closePending || operation!=null)return;
            await ResetContinuity();
            var selected=requested??SelectedVideo();string selectedUrl=selected==null?search.Text:selected.Url;
            if((selected!=null && selected.Kind=="playlist") || ContentFeeds.IsPlaylist(selectedUrl)){
                await BeginPlaylist(selected,selectedUrl,audio);return;
            }
            await Work(audio?L.T("Resolving audio stream…"):L.T("Resolving video stream…"),async delegate(CancellationToken ct) {
                var trace=StartupTrace.StartAttempt("play",settings,audio);
                playbackStarting=true;try{
                string url=selected==null?SelectedUrl():selected.Url;status.Text=L.T("Getting video information…");var backend=new Backend(settings,trace);var info=await backend.Resolve(url,audio,ct,Append);ct.ThrowIfCancellationRequested();
                if(!audio && settings.Fps30) Append(L.T("The 30 FPS limit can select 480p when higher quality is only available at 50/60 FPS. See Available formats."));
                var media=Media.From(info,audio);await CapturePosition();
                double resume=settings.ResumePlayback?ResumeRules.Position(resumePoints,url,media.IsLive):0;
                resumeSession="";CloseAudioWindow();status.Text=L.T("Starting mpv…");
                info=await StreamOpenRecovery.Open(info,settings.AutoRecover,()=>backend.Resolve(url,audio,ct,Append,false,true),async refreshed=>{var next=Media.From(refreshed,audio);await player.Start(settings,next,audio,Append,next.IsLive?0:resume,false,100,1,trace);if(ct.IsCancellationRequested){player.Stop();ct.ThrowIfCancellationRequested();}},()=>backend.InvalidateStreamCache(url),ct,()=>{trace.Event("stream open failed; one fresh metadata retry");Append(L.T("Stream opening failed. Refreshing links and retrying once…"));});
                media=Media.From(info,audio);playbackDetails=PlaybackDetails(info);Append(playbackDetails);
                mediaTitle=media.Title;mediaAudio=audio;mediaUrl=url;mediaLive=media.IsLive;latestPlayback.Clear();resumeSession=player.Session;
                if(resume>0)Notify(L.T("Resumed at {0}. Use Start over to replay.",Ui.Time(resume)));
                if(playbackWindow!=null)await player.Command("script-message","lowtube-status-visible","yes");
                if(!continuing)SeedPlaylist(url);
                if(ct.IsCancellationRequested){player.Stop();ct.ThrowIfCancellationRequested();}
                now.Text=L.T(audio?"Audio only: {0}":"Video: {0}",media.Title);
                OpenCompactPlayer(info);RememberVideo(info,url);
                Append(L.T(audio?"Audio is playing in the compact player. Closing it stops playback.":"Video is playing in mpv. F4 / right-click: settings; Space: pause; F: fullscreen; I: statistics."));
                }catch(OperationCanceledException){queuePlaybackCancelled=true;throw;}catch(Exception e){trace.Failure(e);throw;}finally{playbackStarting=false;StartupTrace.Flush();}
            });
        }
        string PlaybackDetails(Dictionary<string,object> info){
            return L.T("Video ID: ")+Json.Str(info,"id")+L.T("\r\nSelected: ")+Rules.SelectedFormat(info)+
                    L.T("\r\nPlayback settings: height ≤ ")+settings.Height+", H.264="+settings.H264+", ≤30 FPS="+settings.Fps30+
                    L.T(", decoder=")+settings.Decoder+L.T(", renderer=")+settings.Renderer+
                    L.T("\r\nBuffer: up to ")+Rules.CacheSeconds(settings)+L.T(" s / ")+Rules.CacheMiB(settings)+L.T(" MiB per demuxer.\r\n");
        }
        static double Number(object value,double fallback){double n;return Double.TryParse(Convert.ToString(value,System.Globalization.CultureInfo.InvariantCulture),System.Globalization.NumberStyles.Any,System.Globalization.CultureInfo.InvariantCulture,out n) && !Double.IsNaN(n) && !Double.IsInfinity(n)?n:fallback;}
        async Task VideoSettings(string quickKey=null,string quickValue=null){
            if(videoSettingsOpen || recovering || operation!=null || initializing){if(quickKey!=null && player.Running)await player.SyncMenu(settings,false);return;}
            if(!player.Running || mediaAudio){Append(L.T("Start video playback first."));return;}
            videoSettingsOpen=true;string own=player.Session;
            try{
                double speed=Number(await player.Command("get_property","speed"),1);
                string actualDecoder="?";try{actualDecoder=Convert.ToString(await player.Command("get_property","hwdec-current"));}catch(Exception){}
                using(var dialog=new VideoSettingsForm(settings,speed,actualDecoder)){
                    if(quickKey==null){if(dialog.ShowDialog(this)!=DialogResult.OK)return;}
                    else if(!dialog.SetQuickSelection(settings,speed,quickKey,quickValue))return;
                    if(player.Session!=own || !player.Running)return;
                    bool windowOnly=PlayerTuning.Signature(settings)==PlayerTuning.Signature(dialog.Selection) && settings.Height==dialog.Selection.Height && settings.H264==dialog.Selection.H264 && settings.Fps30==dialog.Selection.Fps30 && settings.Decoder==dialog.Selection.Decoder && settings.Renderer==dialog.Selection.Renderer && settings.SafeSeeking==dialog.Selection.SafeSeeking && Rules.CacheSeconds(settings)==Rules.CacheSeconds(dialog.Selection) && Rules.CacheMiB(settings)==Rules.CacheMiB(dialog.Selection) && Rules.BackCacheMiB(settings)==Rules.BackCacheMiB(dialog.Selection) && Rules.StartCacheSeconds(settings)==Rules.StartCacheSeconds(dialog.Selection);
                    await InvalidatePrepared();preloadAttempt="";settings=dialog.Selection;updatingPin=true;pin.Checked=settings.PlayerOnTop;updatingPin=false;quality.SelectedItem=settings.Height.ToString();avc.Checked=settings.H264;fps.Checked=settings.Fps30;decoder.SelectedItem=settings.Decoder;renderer.SelectedItem=settings.Renderer;SaveSettings();
                    if(windowOnly){await player.Command("set_property","border",!settings.PlayerBorderless);await player.Command("set_property","ontop",settings.PlayerOnTop);await player.Command("set_property","speed",dialog.Speed);return;}
                    await Work(L.T("Applying video settings…"),async delegate(CancellationToken ct){
                        var trace=StartupTrace.StartAttempt("quality/settings change",settings,false);try{
                        var backend=new Backend(settings,trace);var info=await backend.Resolve(mediaUrl,false,ct,Append);ct.ThrowIfCancellationRequested();
                        if(player.Session!=own || !player.Running){trace.Event("CANCELLED: player session changed");return;}
                        double position=Number(await player.Command("get_property","time-pos"),0);
                        bool paused=Object.Equals(await player.Command("get_property","pause"),true);
                        double volume=Number(await player.Command("get_property","volume"),100);
                        if(player.Session!=own){trace.Event("CANCELLED: player session changed");return;}ct.ThrowIfCancellationRequested();
                        resumeSession="";info=await StreamOpenRecovery.Open(info,settings.AutoRecover,()=>backend.Resolve(mediaUrl,false,ct,Append,false,true),async refreshed=>{var next=Media.From(refreshed,false);await player.Start(settings,next,false,Append,next.IsLive?0:position,paused,volume,dialog.Speed,trace);if(ct.IsCancellationRequested){player.Stop();ct.ThrowIfCancellationRequested();}},()=>backend.InvalidateStreamCache(mediaUrl),ct,()=>{trace.Event("stream open failed after settings change; one fresh metadata retry");Append(L.T("Stream opening failed. Refreshing links and retrying once…"));});resumeSession=player.Session;latestPlayback.Clear();if(playbackWindow!=null)await player.Command("script-message","lowtube-status-visible","yes");
                        if(ct.IsCancellationRequested){player.Stop();ct.ThrowIfCancellationRequested();}
                        playbackDetails=PlaybackDetails(info);Append(playbackDetails);
                        Append(L.T("Video settings applied at {0}.",Ui.Time(position)));
                        }catch(Exception e){trace.Failure(e);throw;}finally{StartupTrace.Flush();}
                    });
                }
            }catch(Exception e){Error(e);}finally{videoSettingsOpen=false;SyncPlayerMenu();if(!mediaAudio)player.FocusWindow();}
        }
        async void SyncPlayerMenu(){try{if(player.Running)await player.SyncMenu(settings,false);}catch(Exception){}}
        void CloseAudioWindow(){if(audioWindow!=null){var window=audioWindow;audioWindow=null;window.CloseForSwitch();}}
        async void StopPlayer(){if(stoppingPlayer)return;stoppingPlayer=true;try{CancelPlaybackWork();await CapturePosition();resumeSession="";CloseAudioWindow();player.Stop();now.Text=L.T("Player stopped");await InvalidatePrepared();if(recovering)await recoveryTask;}finally{stoppingPlayer=false;}}
        async Task Download(Video requested=null) {
            var selected=requested??SelectedVideo();if(selected!=null && selected.Kind=="playlist"){await Play(false);return;}
            await Work(L.T("Loading download options…"),async delegate(CancellationToken ct){
                string url=selected==null?SelectedUrl():selected.Url;var info=await new Backend(settings).Resolve(url,false,ct,Append,true);ct.ThrowIfCancellationRequested();
                using(var dialog=new DownloadForm(settings,info)) {
                    if(dialog.ShowDialog(this)!=DialogResult.OK)return;
                    ct.ThrowIfCancellationRequested();var selection=dialog.Selection;
                    settings.DownloadFps30=dialog.LimitFps;settings.DownloadH264=dialog.H264Only;
                    if(selection.AudioOnly)settings.AudioDownloadFormat=selection.Kind;
                    else{settings.DownloadFormat=selection.Kind;settings.DownloadHeight=selection.Height;}
                    settings.AudioBitrate=selection.Bitrate;SaveSettings();status.Text=L.T("Downloading file…");
                    Append(L.T("Selected format: {0}; file type: {1}",selection.FormatId,selection.Kind));
                    downloads.Add(url,Json.Str(info,"title",url),settings.DownloadFolder,selection);
                    Notify(L.T("Download queued. Open Downloads to manage it."));OpenDownloads();
                }
            });
        }
        async Task Formats() {
            await Work(L.T("Checking available formats…"),async delegate(CancellationToken ct){
                var root=await new Backend(settings).Resolve(SelectedUrl(),false,ct,Append,true);
                var b=new System.Text.StringBuilder(L.T("Size = actual width × height. Label = source quality name.\r\nThe playback height cap does not upscale or stretch video. Downloads select a native format in a separate dialog.\r\nPlayback: ")+(settings.Fps30?"≤30 FPS":L.T("any FPS"))+L.T(". Download default: ")+(settings.DownloadFps30?"≤30 FPS":L.T("any FPS"))+L.T(".\r\n\r\nID       Size           FPS    Video codec       Audio codec   Container  Label\r\n"));
                foreach(var f in Json.Items(root,"formats"))b.AppendLine(String.Format("{0,-8} {1,-14} {2,-6} {3,-17} {4,-13} {5,-9} {6}",Json.Str(f,"format_id"),Rules.Dimensions(f),Json.Str(f,"fps"),Json.Str(f,"vcodec"),Json.Str(f,"acodec"),Json.Str(f,"ext"),Json.Str(f,"format_note")));
                ShowReport(L.T("Available formats — ")+Json.Str(root,"title"),b.ToString());
            });
        }
        async Task Control(params object[] command){try{await player.Command(command);}catch(Exception e){Error(e);}}
        async Task<string> PlayerSnapshot(){
            var b=new System.Text.StringBuilder(L.T("Snapshot at ")+DateTime.Now.ToString("HH:mm:ss")+"\r\n");
            foreach(string property in new[]{"seeking","video-pts","audio-pts","video-sync","initial-audio-sync","hwdec-current","video-codec","video-params/w","video-params/h","estimated-vf-fps",
                "time-pos","duration","pause","paused-for-cache","cache-buffering-state","demuxer-cache-duration","cache-speed","video-bitrate","audio-bitrate","speed",
                "demuxer-cache-state","decoder-frame-drop-count","frame-drop-count","avsync"}) {
                try{b.AppendLine(property+": "+Json.Serializer().Serialize(await player.Command("get_property",property)));}
                catch(InvalidOperationException){if(!player.Running)throw;b.AppendLine(property+L.T(": unavailable"));}
            }
            return b.ToString();
        }
        async Task Diagnose(){
            if(diagnosing)return;diagnosing=true;
            try{
                if(!player.Running)throw new InvalidOperationException(L.T("Start playback first. To diagnose stutters, click this button while the problem is happening."));
                string details=playbackDetails;
                string first=await PlayerSnapshot();await Task.Delay(2000);string second=await PlayerSnapshot();
                if(!closing)ShowReport(L.T("mpv diagnostics — two snapshots"),details+"\r\n"+first+"\r\n"+second+
                    L.T("\r\nhwdec-current = no: software decoding.\r\npaused-for-cache = true: waiting for data.\r\nIncreasing frame-drop-count with a filled buffer may indicate decoding/output trouble.\r\nDrop counters accumulate; compare both snapshots.\r\nraw-input-rate: bytes/s; cache-duration: seconds. Zero input rate with a full buffer is normal.\r\nCopy this report during a stall; it contains no cookies or signed URLs."));
            }catch(Exception e){Error(e);}finally{diagnosing=false;}
        }
        void ShowReport(string title,string text){
            using(var form=new Form{Text=title,Size=new Size(980,590),StartPosition=FormStartPosition.CenterParent}){
                var box=new TextBox{Dock=DockStyle.Fill,Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Both,WordWrap=false,Font=new Font("Consolas",9F),Text=text};
                var copy=new Button{Text=L.T("Copy report"),Dock=DockStyle.Bottom,Height=36};
                copy.Click+=delegate{try{Clipboard.SetText(box.Text);}catch(Exception e){MessageBox.Show(form,e.Message);}};
                form.Controls.Add(box);form.Controls.Add(copy);Ui.Attach(form);form.ShowDialog(this);
            }
        }
        void OpenFolder(){Directory.CreateDirectory(settings.DownloadFolder);Process.Start(new ProcessStartInfo(settings.DownloadFolder){UseShellExecute=true});}
        void UpdateAccountLabel(){accountLabel.Text=L.T(settings.Auth=="saved" && SavedSession.Exists?"Saved YouTube session":"Account: disconnected");}
        async void Account(){
            string oldStamp=SavedSession.Stamp;feedModal=true;await PauseFeeds();
            try{using(var dialog=new AccountForm(settings)){dialog.ShowDialog(this);Backend.ClearMetadataCache();SaveSettings();UpdateAccountLabel();}}finally{feedModal=false;recommendationCache.Clear();QueueFeedRefresh();}
            if(SavedSession.Exists && oldStamp!=SavedSession.Stamp)await Work(L.T("Synchronizing..."),async ct=>{await SyncFeeds(ct);});
        }
        void OpenEqualizer(){
            feedModal=true;if(feedRequest!=null)feedRequest.Cancel();
            try{using(var form=new EqualizerForm(settings.Equalizer,async value=>{
                var before=AudioEqualizer.Copy(settings.Equalizer);await player.ApplyEqualizer(value);settings.Equalizer=AudioEqualizer.Copy(value);
                Exception failure=null;try{SaveSettings();}catch(Exception e){failure=e;settings.Equalizer=before;}
                if(failure!=null){try{await player.ApplyEqualizer(before);}catch(Exception){}throw failure;}
            }))form.ShowDialog(this);}finally{feedModal=false;}
        }
        void Preferences(){
            int previousSize=PageSize;
            feedModal=true;if(feedRequest!=null)feedRequest.Cancel();try{
            using(var form=new Form{Text=L.T("LowTube settings"),Size=new Size(690,690),StartPosition=FormStartPosition.CenterParent,Font=Font,AutoScaleMode=AutoScaleMode.Dpi,FormBorderStyle=FormBorderStyle.FixedDialog,MaximizeBox=false,MinimizeBox=false}){
                var flow=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,Padding=new Padding(16),WrapContents=false,AutoScroll=true};form.Controls.Add(flow);
                flow.Controls.Add(Ui.Label("Language",620));
                string[] codes={"en","uk","ru"};
                var language=Ui.Combo(new[]{"English","Українська","Русский"},"English",280);language.SelectedIndex=Math.Max(0,Array.IndexOf(codes,L.Language));flow.Controls.Add(language);
                flow.Controls.Add(Ui.Label("Videos per page (1–250)",620));
                var pageSizeInput=new NumericUpDown{Minimum=1,Maximum=250,Value=PageSize,DecimalPlaces=0,Increment=1,Width=160};flow.Controls.Add(pageSizeInput);
                flow.Controls.Add(Ui.Label("Background feed updates",620));
                var feedPreset=Ui.Combo(FeedPreset.Names.Select(x=>L.T(x)).ToArray(),L.T(FeedPreset.Names[Math.Max(0,Math.Min(2,settings.FeedPreset))]),350);flow.Controls.Add(feedPreset);
                flow.Controls.Add(Ui.Label("Slow: 2 channels, 30 min. Balanced: 4 channels, 15 min. Fast: 6 channels, 5 min. One background request at a time; playback starts take priority.",620));
                flow.Controls.Add(Ui.Label("Background work: up to 4 requests per interval, with 120/60/30-second pauses. Date lookup: at most 1 per interval, visible videos only.",620));
                var resumePlayback=new CheckBox{Text=L.T("Resume from last position"),Checked=settings.ResumePlayback,AutoSize=true};flow.Controls.Add(resumePlayback);
                var autoRecover=new CheckBox{Text=L.T("Automatically restore interrupted streams (up to 2 attempts)"),Checked=settings.AutoRecover,AutoSize=true};flow.Controls.Add(autoRecover);
                var preload=new CheckBox{Text=L.T("Prepare the next video 20 seconds before the end"),Checked=settings.PreloadNext,AutoSize=true};flow.Controls.Add(preload);
                flow.Controls.Add(Ui.Label("Theme",620));
                var theme=Ui.Combo(new[]{L.T("Light"),L.T("Dark")},L.T(settings.Theme=="dark"?"Dark":"Light"),280);flow.Controls.Add(theme);
                flow.Controls.Add(Ui.Label("Guest recommendations topic",620));
                var topic=new TextBox{Text=settings.DiscoveryTopic,MaxLength=200,Width=580};flow.Controls.Add(topic);
                flow.Controls.Add(Ui.Label("Recommendations language",620));
                var recommendationNames=(string[])LocalFeeds.LanguageNames.Clone();recommendationNames[0]=L.T("Any language");
                var recommendationLanguage=Ui.Combo(recommendationNames,recommendationNames[Array.IndexOf(LocalFeeds.LanguageCodes,LocalFeeds.LanguageCode(settings.DiscoveryLanguage))],280);
                recommendationLanguage.Tag="data";flow.Controls.Add(recommendationLanguage);
                flow.Controls.Add(Ui.Label("Language is a search preference, not a strict spoken-language filter. It does not change the interface language.",620));
                flow.Controls.Add(Ui.Label("Recommendations use public search for this topic. Local subscriptions do not sync with Google.",620));
                flow.Controls.Add(new Label{Text=L.T("Download folder"),AutoSize=true});var folder=new TextBox{Text=settings.DownloadFolder,Width=580};flow.Controls.Add(folder);
                var browse=new Button{Text=L.T("Choose folder"),AutoSize=true};browse.Click+=delegate{using(var d=new FolderBrowserDialog{SelectedPath=folder.Text})if(d.ShowDialog(form)==DialogResult.OK)folder.Text=d.SelectedPath;};flow.Controls.Add(browse);
                flow.Controls.Add(Ui.Label("Download limit (KiB/s; 0 = unlimited)",620));var downloadRate=DownloadRateInput();flow.Controls.Add(downloadRate);
                flow.Controls.Add(Ui.Label("Applies to new or retried jobs. Cancel and retry an active download to apply the limit.",620));
                flow.Controls.Add(Ui.Button("Export diagnostics",ExportDiagnostics));
                flow.Controls.Add(new Label{Text=L.T("Playback buffer (applies to the next playback session)"),AutoSize=true});
                var buffer=new BufferEditor(settings);flow.Controls.Add(buffer);
                flow.Controls.Add(Ui.Label("Video and separate audio each have a buffer. The memory cap can limit the requested seconds. A larger buffer does not increase the startup threshold.",620));
                var report=Ui.Button("Startup report",delegate{ShowReport(L.T("Startup report"),StartupTrace.Report());});flow.Controls.Add(report);
                var fresh=Ui.Button("Clear stream cache",delegate{Backend.ClearMetadataCache();MessageBox.Show(form,L.T("Stream cache cleared. The next playback will fetch fresh metadata."));});flow.Controls.Add(fresh);
                var update=new Button{Text=L.T("Update components (after closing LowTube)"),AutoSize=true};update.Click+=delegate{MessageBox.Show(form,L.T("Close LowTube and run SETUP.cmd again to update yt-dlp, mpv, FFmpeg, Node.js and QuickJS. Internet access is required."));};flow.Controls.Add(update);
                var clear=new Button{Text=L.T("Clear local history"),AutoSize=true};clear.Click+=delegate{history.Clear();watched.Clear();resumePoints.Clear();SaveResume();Json.Save(Path.Combine(Paths.Data,"history.json"),history);ShowVideos(history,L.T("Local history cleared"));};flow.Controls.Add(clear);
                var help=new Button{Text=L.T("Open instructions"),AutoSize=true};help.Click+=delegate{Process.Start(new ProcessStartInfo(Path.Combine(Paths.Root,"README_"+(L.Language=="en"?"EN":L.Language=="ru"?"RU":"UK")+".txt")){UseShellExecute=true});};flow.Controls.Add(help);
                var save=new Button{Text=L.T("Save"),AutoSize=true};save.Click+=delegate{try{string full=Path.GetFullPath(folder.Text.Trim());Directory.CreateDirectory(full);settings.DownloadFolder=full;buffer.Write(settings);settings.ResumePlayback=resumePlayback.Checked;settings.AutoRecover=autoRecover.Checked;settings.PreloadNext=preload.Checked;settings.DownloadLimitKiB=(int)downloadRate.Value;QueueChanged();settings.PageSize=(int)pageSizeInput.Value;settings.FeedPreset=feedPreset.SelectedIndex;nextFeedRefresh=DateTime.UtcNow.AddMinutes(FeedPreset.Get(settings.FeedPreset).Minutes);settings.Language=codes[language.SelectedIndex];settings.Theme=theme.SelectedIndex==1?"dark":"light";settings.DiscoveryTopic=topic.Text.Trim();settings.DiscoveryLanguage=LocalFeeds.LanguageCodes[recommendationLanguage.SelectedIndex];L.Set(settings.Language);Theme.Set(settings.Theme);SaveSettings();form.Close();}catch(Exception e){MessageBox.Show(form,e.Message);}};var footer=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=45,Padding=new Padding(16,0,0,0)};footer.Controls.Add(save);form.Controls.Add(footer);form.AcceptButton=save;Ui.Attach(form);form.ShowDialog(this);
            }
            }finally{feedModal=false;}
            if(PageSize!=previousSize){
                recommendationCache.Clear();feedJobs.RemoveAll(x=>x.Key.StartsWith("rec:",StringComparison.Ordinal));page=0;
                BeginInvoke(new Action(async delegate{try{if(currentTarget.Length>0)await ChangePage(0);else ShowVideos(history.Take(PageSize),L.T("Local playback history"));}catch(Exception e){Error(e);}}));
            }
        }
    }
    public sealed class AccountForm : Form {
        public AccountForm(Settings s,bool autoConnectOnShow=false,bool showAdvanced=false) {
            Text=L.T("YouTube account — local browser session");Size=new Size(690,680);StartPosition=FormStartPosition.CenterParent;Font=new Font("Segoe UI",9F);FormBorderStyle=FormBorderStyle.FixedDialog;MaximizeBox=false;MinimizeBox=false;
            var outer=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(16)};Controls.Add(outer);
            outer.Controls.Add(Ui.Label("Open YouTube, sign in with your Google account in the browser, then return here and connect your browser session.",630));
            var googleSignIn=new Button{Text=L.T("Sign in with Google in browser"),AutoSize=true};
            googleSignIn.Click+=delegate{try{Process.Start(new ProcessStartInfo("https://www.youtube.com/"){UseShellExecute=true});}catch(Exception e){MessageBox.Show(this,e.Message);}};outer.Controls.Add(googleSignIn);
            var autoConnect=new Button{Text=L.T("Connect YouTube"),AutoSize=true};outer.Controls.Add(autoConnect);
            var autoStatus=Ui.Label(SavedSession.Exists?"A saved session is available.":"The browser can be closed after a successful import.",630);outer.Controls.Add(autoStatus);
            var advancedToggle=new CheckBox{Text=L.T("Show advanced session settings"),AutoSize=true};outer.Controls.Add(advancedToggle);
            var p=new FlowLayoutPanel{AutoSize=true,FlowDirection=FlowDirection.TopDown,WrapContents=false,Width=640,Visible=false};outer.Controls.Add(p);
            advancedToggle.CheckedChanged+=delegate{p.Visible=advancedToggle.Checked;};advancedToggle.Checked=showAdvanced;
            if(autoConnectOnShow)Shown+=delegate{autoConnect.PerformClick();};
            p.Controls.Add(new Label{Text=L.T("Sign in to YouTube in your browser, select its profile and save the session. Passwords are entered only on Google's website. Google history is experimental and read-only."),AutoSize=true,MaximumSize=new Size(640,0)});
            var open=new Button{Text=L.T("Open YouTube in Firefox"),AutoSize=true};open.Click+=delegate{
                string[] candidates={Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"Mozilla Firefox","firefox.exe"),Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),"Mozilla Firefox","firefox.exe")};
                string exe=candidates.FirstOrDefault(File.Exists);
                if(exe==null){MessageBox.Show(this,L.T("Open Firefox manually and go to https://www.youtube.com/ ."));return;}
                Process.Start(new ProcessStartInfo(exe,Rules.Quote("https://www.youtube.com/")){UseShellExecute=false});
            };p.Controls.Add(open);
            var mode=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=330};mode.Items.AddRange(new[]{L.T("No account"),L.T("Browser session"),L.T("cookies.txt — separate file"),L.T("Saved YouTube session")});mode.SelectedIndex=s.Auth=="saved"?3:(s.Auth=="firefox" || s.Auth=="browser")?1:s.Auth=="file"?2:0;p.Controls.Add(mode);
            p.Controls.Add(Ui.Label("Browser",630));
            var browser=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=330};browser.Items.AddRange(BrowserSessions.Names);browser.SelectedItem=BrowserSessions.Browser(s);p.Controls.Add(browser);
            p.Controls.Add(Ui.Label("Profile folder (blank = automatic). Other compatible browsers: select their engine and profile folder.",630));
            var profile=new ComboBox{DropDownStyle=ComboBoxStyle.DropDown,Width=630,Text=BrowserSessions.Profile(s)??""};p.Controls.Add(profile);
            var profiles=new Dictionary<string,string>();string previousBrowser=browser.Text;
            Action refresh=delegate{string current=profile.Text;profile.Items.Clear();profile.Items.Add("");profile.Items.AddRange(BrowserSessions.Discover(browser.Text,Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData)));profile.Text=current;};
            refresh();
            browser.SelectedIndexChanged+=delegate{profiles[previousBrowser]=profile.Text;previousBrowser=browser.Text;string remembered;profile.Text=profiles.TryGetValue(previousBrowser,out remembered)?remembered:"";refresh();};
            var profileButtons=new FlowLayoutPanel{AutoSize=true,Width=630};p.Controls.Add(profileButtons);
            var browseProfile=new Button{Text=L.T("Choose profile folder"),AutoSize=true};browseProfile.Click+=delegate{using(var d=new FolderBrowserDialog())if(d.ShowDialog(this)==DialogResult.OK)profile.Text=d.SelectedPath;};profileButtons.Controls.Add(browseProfile);
            var refreshProfiles=new Button{Text=L.T("Refresh profiles"),AutoSize=true};refreshProfiles.Click+=delegate{refresh();};profileButtons.Controls.Add(refreshProfiles);
            var openDefault=new Button{Text=L.T("Open YouTube in default browser"),AutoSize=true};openDefault.Click+=delegate{try{Process.Start(new ProcessStartInfo("https://www.youtube.com/"){UseShellExecute=true});}catch(Exception e){MessageBox.Show(this,e.Message);}};profileButtons.Controls.Add(openDefault);
            p.Controls.Add(Ui.Label("Some browsers lock or encrypt cookies. If import fails, use cookies.txt. After saving, the browser can be closed.",630));
            p.Controls.Add(new Label{Text=L.T("Netscape cookies.txt file (only for file mode)"),AutoSize=true});
            var cookie=new TextBox{Text=s.CookiesFile,Width=630};p.Controls.Add(cookie);
            var pick=new Button{Text=L.T("Choose cookies.txt"),AutoSize=true};pick.Click+=delegate{using(var d=new OpenFileDialog{Filter=L.T("Cookies (*.txt)|*.txt|All files|*.*")})if(d.ShowDialog(this)==DialogResult.OK)cookie.Text=d.FileName;};p.Controls.Add(pick);
            var media=new CheckBox{Text=L.T("Use this session for search, playback and downloads too"),Checked=s.AuthMedia,AutoSize=true};p.Controls.Add(media);
            p.Controls.Add(new Label{Text=L.T("By default, the session is used only for YouTube history.\nDo not share cookies.txt. No account disconnects LowTube only.\nRecommendations and local subscriptions never use this session."),AutoSize=true,MaximumSize=new Size(640,0)});
            var sessionButtons=new FlowLayoutPanel{AutoSize=true,Width=630};p.Controls.Add(sessionButtons);
            var copySession=new Button{Text=L.T("Save browser session"),AutoSize=true};
            var importSession=new Button{Text=L.T("Import saved session"),AutoSize=true};
            var removeSession=new Button{Text=L.T("Delete saved session"),AutoSize=true};
            sessionButtons.Controls.AddRange(new Control[]{copySession,importSession,removeSession});
            var save=new Button{Text=L.T("Save"),AutoSize=true};
            var capture=new CancellationTokenSource();FormClosed+=delegate{capture.Cancel();};
            autoConnect.Click+=async delegate{
                autoConnect.Enabled=advancedToggle.Enabled=p.Enabled=false;autoStatus.Text=L.T("Looking for browsers and profiles...");
                try{
                    var detected=await Task.Run(()=>BrowserDiscovery.Detect(capture.Token),capture.Token);
                    if(IsDisposed)return;
                    if(detected.Profiles.Count==0){autoStatus.Text=L.T("No compatible profile found. Open your browser, sign in to YouTube, then try again.");return;}
                    BrowserProfileChoice selected=detected.Profiles.Count==1 && !detected.Limited?detected.Profiles[0]:ChooseDetectedProfile(detected);
                    if(selected==null){autoStatus.Text=L.T("Import cancelled. Your saved session was not changed.");return;}
                    autoStatus.Text=L.T("Importing YouTube session...");
                    await SavedSession.CaptureBrowser(selected.Browser,selected.Profile,capture.Token,true);
                    if(IsDisposed)return;
                    browser.SelectedItem=selected.Browser;profile.Text=selected.Profile;mode.SelectedIndex=3;media.Checked=true;
                    s.Auth="saved";s.AuthMedia=true;s.BrowserName=selected.Browser;s.BrowserProfile=selected.Profile;
                    if(selected.Browser=="firefox")s.FirefoxProfile=selected.Profile;
                    Backend.ClearMetadataCache();
                    autoStatus.Text=L.T("YouTube session saved. The browser is no longer needed for playback.");
                }catch(OperationCanceledException){}catch(Exception e){if(!IsDisposed)autoStatus.Text=e.Message;}
                finally{if(!IsDisposed)autoConnect.Enabled=advancedToggle.Enabled=p.Enabled=true;}
            };
            copySession.Click+=async delegate{
                autoConnect.Enabled=advancedToggle.Enabled=browser.Enabled=profile.Enabled=profileButtons.Enabled=mode.Enabled=copySession.Enabled=importSession.Enabled=removeSession.Enabled=save.Enabled=false;
                try{await SavedSession.CaptureBrowser(browser.Text,profile.Text,capture.Token);if(!IsDisposed){mode.SelectedIndex=3;media.Checked=true;s.Auth="saved";s.AuthMedia=true;s.BrowserName=browser.Text;s.BrowserProfile=profile.Text.Trim();MessageBox.Show(this,L.T("YouTube session saved. The browser is no longer needed for playback."));}}
                catch(OperationCanceledException){}catch(Exception e){if(!IsDisposed)MessageBox.Show(this,e.Message);}
                finally{if(!IsDisposed)autoConnect.Enabled=advancedToggle.Enabled=browser.Enabled=profile.Enabled=profileButtons.Enabled=mode.Enabled=copySession.Enabled=importSession.Enabled=removeSession.Enabled=save.Enabled=true;}
            };
            importSession.Click+=delegate{using(var d=new OpenFileDialog{Filter=L.T("Cookies (*.txt)|*.txt|All files|*.*")})if(d.ShowDialog(this)==DialogResult.OK)try{SavedSession.Import(d.FileName);mode.SelectedIndex=3;media.Checked=true;s.Auth="saved";s.AuthMedia=true;}catch(Exception e){MessageBox.Show(this,e.Message);}};
            removeSession.Click+=delegate{try{SavedSession.Remove();if(mode.SelectedIndex==3)mode.SelectedIndex=0;}catch(Exception e){MessageBox.Show(this,e.Message);}};
            save.Click+=delegate{
                if(mode.SelectedIndex==3 && !SavedSession.Exists){MessageBox.Show(this,L.T("Save a YouTube session in Account first."));return;}
                if(mode.SelectedIndex==2 && !File.Exists(cookie.Text)){MessageBox.Show(this,L.T("Choose an existing cookies.txt file."));return;}
                if(mode.SelectedIndex==1 && profile.Text.Trim().Length>0 && !Directory.Exists(profile.Text.Trim())){MessageBox.Show(this,L.T("Profile folder not found."));return;}
                try{BrowserSessions.Argument(browser.Text,profile.Text);}catch(Exception e){MessageBox.Show(this,e.Message);return;}
                s.Auth=mode.SelectedIndex==3?"saved":mode.SelectedIndex==1?"browser":mode.SelectedIndex==2?"file":"none";s.BrowserName=browser.Text;s.BrowserProfile=profile.Text.Trim();if(browser.Text=="firefox")s.FirefoxProfile=s.BrowserProfile;s.CookiesFile=cookie.Text.Trim();s.AuthMedia=media.Checked;DialogResult=DialogResult.OK;Close();
            };p.Controls.Add(save);Ui.Attach(this);
        }
        static BrowserProfileChoice ChooseDetectedProfile(BrowserDiscoveryResult found){
            using(var dialog=new Form{Text=L.T("Choose a browser profile"),Size=new Size(570,350),StartPosition=FormStartPosition.CenterParent,MinimizeBox=false,MaximizeBox=false}){
                var label=Ui.Label(found.Limited?"Search was limited. Choose a found profile or cancel and open the desired browser before retrying.":"Choose the browser profile where you signed in to YouTube.",530);label.Dock=DockStyle.Top;dialog.Controls.Add(label);
                var list=new ListBox{Dock=DockStyle.Fill};foreach(var item in found.Profiles)list.Items.Add(item);dialog.Controls.Add(list);list.BringToFront();
                var buttons=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=45};var ok=new Button{Text=L.T("Connect YouTube"),AutoSize=true,DialogResult=DialogResult.OK,Enabled=false};
                var cancel=new Button{Text=L.T("Cancel"),AutoSize=true,DialogResult=DialogResult.Cancel};buttons.Controls.AddRange(new Control[]{ok,cancel});dialog.Controls.Add(buttons);buttons.BringToFront();
                list.SelectedIndexChanged+=delegate{ok.Enabled=list.SelectedItem!=null;};dialog.AcceptButton=ok;dialog.CancelButton=cancel;Ui.Attach(dialog);
                return dialog.ShowDialog()==DialogResult.OK?list.SelectedItem as BrowserProfileChoice:null;
            }
        }
    }
    public static class Program {
        [STAThread] public static void Main(string[] args) {
            StartupTrace.Begin();Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);StartupTrace.Mark("Windows styles ready");
            try{Application.Run(new MainForm());}catch(Exception e){MessageBox.Show(e.Message,L.T("LowTube — startup error"));}
        }
    }
}
