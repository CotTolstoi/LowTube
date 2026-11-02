using System;
using System.Drawing;
using System.IO;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace LowTube {
    public sealed class AudioForm : Form {
        readonly Player player;
        readonly string session;
        readonly Action stopped;
        readonly CancellationTokenSource lifetime=new CancellationTokenSource();
        readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer{Interval=1000};
        PictureBox preview;
        Label state,position,placeholder;
        TrackBar seek,volume;
        Button pause;
        bool awaitingClose;
        bool polling,dragging,updating,adjustingVolume,stopOnClose=true;
        double duration;
        bool Current { get { return !IsDisposed && !lifetime.IsCancellationRequested && player.Session==session; } }
        public AudioForm(Player p,string id,string title,string channel,Action onStopped,Func<Task> beforeStop=null) {
            player=p;session=p.Session;stopped=onStopped;
            Text=L.T("LowTube — audio only");ClientSize=new Size(420,416);FormBorderStyle=FormBorderStyle.FixedSingle;
            MaximizeBox=false;StartPosition=FormStartPosition.CenterScreen;Font=new Font("Segoe UI",9F);AutoScaleMode=AutoScaleMode.Dpi;
            var layout=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=7,Padding=new Padding(12)};
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
            foreach(int h in new[]{190,43,23,40,36,35,25})layout.RowStyles.Add(new RowStyle(SizeType.Absolute,h));
            Controls.Add(layout);
            var art=new Panel{Dock=DockStyle.Fill,BackColor=Color.FromArgb(28,31,37),Margin=Padding.Empty};
            preview=new PictureBox{Dock=DockStyle.Fill,SizeMode=PictureBoxSizeMode.Zoom};art.Controls.Add(preview);
            placeholder=new Label{Text=L.T("Loading preview…"),Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleCenter,ForeColor=Color.White};art.Controls.Add(placeholder);placeholder.BringToFront();layout.Controls.Add(art,0,0);
            layout.Controls.Add(new Label{Text=title,Tag="data",Dock=DockStyle.Fill,Font=new Font(Font,FontStyle.Bold),AutoEllipsis=true,TextAlign=ContentAlignment.MiddleLeft},0,1);
            layout.Controls.Add(new Label{Text=channel,Tag="data",Dock=DockStyle.Fill,AutoEllipsis=true,ForeColor=Color.DimGray},0,2);
            var timeline=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,Margin=Padding.Empty};timeline.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));timeline.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,113));
            seek=new TrackBar{Dock=DockStyle.Fill,Minimum=0,Maximum=1000,TickStyle=TickStyle.None,Enabled=false,AccessibleName=L.T("Position")};
            position=new Label{Text="--:-- / --:--",Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleRight};timeline.Controls.Add(seek,0,0);timeline.Controls.Add(position,1,0);layout.Controls.Add(timeline,0,3);
            var controls=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false,Margin=Padding.Empty};
            controls.Controls.Add(Ui.Button("−10 s",async delegate{await Send("seek",-10,"relative");}));
            pause=Ui.Button("Pause",async delegate{await Send("cycle","pause");});controls.Controls.Add(pause);
            controls.Controls.Add(Ui.Button("+10 s",async delegate{await Send("seek",10,"relative");}));
            controls.Controls.Add(Ui.Button("Stop",delegate{Close();}));layout.Controls.Add(controls,0,4);
            var sound=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,Margin=Padding.Empty};sound.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,100));sound.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
            sound.Controls.Add(Ui.Label("Volume",95),0,0);volume=new TrackBar{Dock=DockStyle.Fill,Minimum=0,Maximum=100,Value=100,TickStyle=TickStyle.None,AccessibleName=L.T("Volume")};sound.Controls.Add(volume,1,0);layout.Controls.Add(sound,0,5);
            state=new Label{Text=L.T("Buffering…"),Dock=DockStyle.Fill,AutoEllipsis=true};layout.Controls.Add(state,0,6);
            seek.MouseDown+=delegate{dragging=true;};seek.MouseUp+=async delegate{dragging=false;await Seek();};seek.KeyUp+=async delegate{await Seek();};
            volume.Scroll+=async delegate{if(!updating)await SetVolume();};
            timer.Tick+=async delegate{await Poll();};
            Shown+=async delegate{timer.Start();await LoadPreview(id);};
            FormClosing+=async delegate(object sender,FormClosingEventArgs e){
                if(!stopOnClose || beforeStop==null || player.Session!=session)return;
                e.Cancel=true;if(awaitingClose)return;awaitingClose=true;
                try{await beforeStop();}catch(Exception){}stopOnClose=false;if(player.Session==session){player.Stop();stopped();}Close();
            };
            FormClosed+=delegate{timer.Stop();lifetime.Cancel();if(stopOnClose && player.Session==session){player.Stop();stopped();}};
            Ui.Attach(this);
        }
        public void CloseForSwitch(){stopOnClose=false;Close();}
        async Task Seek(){if(duration>0 && !updating)await Send("seek",duration*seek.Value/1000.0,"absolute");}
        async Task SetVolume(){
            if(adjustingVolume || !Current)return;adjustingVolume=true;
            try{int sent;do{sent=volume.Value;await Send("set_property","volume",sent);}while(Current && player.Running && sent!=volume.Value);}
            finally{adjustingVolume=false;}
        }
        async Task Send(params object[] command) {
            if(!Current || !player.Running)return;
            try{await player.Command(command);}catch(Exception e){if(Current)state.Text=Rules.Redact(e.Message);}
        }
        static double Numeric(object value) { double n;return Double.TryParse(Convert.ToString(value,System.Globalization.CultureInfo.InvariantCulture),System.Globalization.NumberStyles.Any,System.Globalization.CultureInfo.InvariantCulture,out n)?n:0; }
        async Task<object> OptionalProperty(string name,object fallback) {
            try{return await player.Command("get_property",name);}
            catch(InvalidOperationException){if(!player.Running)throw;return fallback;}
        }
        async Task Poll() {
            if(polling || !Current)return;polling=true;
            try {
                if(!player.Running){timer.Stop();state.Text=L.T("Playback ended");pause.Enabled=false;seek.Enabled=false;volume.Enabled=false;return;}
                var pos=await OptionalProperty("time-pos",0);if(!Current)return;
                var len=await OptionalProperty("duration",0);if(!Current)return;
                var paused=await player.Command("get_property","pause");if(!Current)return;
                var buffering=await player.Command("get_property","paused-for-cache");if(!Current)return;
                double seconds=Numeric(pos);duration=Numeric(len);
                updating=true;
                seek.Enabled=duration>0;
                if(!dragging && duration>0)seek.Value=Math.Max(0,Math.Min(1000,(int)(seconds/duration*1000)));
                position.Text=Ui.Time(seconds)+" / "+(duration>0?Ui.Time(duration):"--:--");
                bool isPaused=Object.Equals(paused,true);
                pause.Text=L.T(isPaused?"Resume":"Pause");
                state.Text=L.T(Object.Equals(buffering,true)?"Buffering…":isPaused?"Paused":"Playing audio");
            } catch(Exception) { if(Current)state.Text=L.T(player.Running?"Waiting for player…":"Playback ended"); }
            finally{updating=false;polling=false;}
        }
        async Task LoadPreview(string id) {
            try {
                string url=Rules.ThumbnailUrl(id);
                if(url.Length==0)throw new InvalidDataException();
                var token=lifetime.Token;
                Image bitmap=await Task.Run(delegate {
                    token.ThrowIfCancellationRequested();
                    var request=(HttpWebRequest)WebRequest.Create(url);request.Timeout=12000;request.ReadWriteTimeout=12000;request.AllowAutoRedirect=false;
                    using(token.Register(delegate{request.Abort();}))
                    using(var response=(HttpWebResponse)request.GetResponse())
                    using(var source=response.GetResponseStream())
                    using(var bytes=new MemoryStream()) {
                        const int limit=2*1024*1024;
                        if(response.ContentLength>limit)throw new InvalidDataException();
                        byte[] block=new byte[8192];int count;
                        while((count=source.Read(block,0,block.Length))>0) {
                            token.ThrowIfCancellationRequested();if(bytes.Length+count>limit)throw new InvalidDataException();bytes.Write(block,0,count);
                        }
                        bytes.Position=0;
                        using(var decoded=Image.FromStream(bytes)) {
                            if(decoded.Width>4096 || decoded.Height>4096)throw new InvalidDataException();
                            return (Image)new Bitmap(decoded);
                        }
                    }
                },token);
                if(!Current){bitmap.Dispose();return;}
                preview.Image=bitmap;placeholder.Visible=false;
            } catch(Exception) { if(Current)placeholder.Text=L.T("Preview unavailable"); }
        }
        protected override void Dispose(bool disposing) {
            if(disposing) {
                lifetime.Cancel();timer.Dispose();
                if(preview!=null && preview.Image!=null){var old=preview.Image;preview.Image=null;old.Dispose();}
                // Pending network work can still observe the cancelled source.
            }
            base.Dispose(disposing);
        }
    }
}
