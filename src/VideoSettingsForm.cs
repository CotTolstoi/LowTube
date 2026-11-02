using System;
using System.Drawing;
using System.Windows.Forms;
namespace LowTube {
    public sealed class VideoSettingsForm : Form {
        public Settings Selection { get;private set; }
        public double Speed { get;private set; }
        public bool SetQuickSelection(Settings source,double speed,string key,string value){
            Selection=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(source));Speed=speed;int n;double rate;
            switch(key){
             case "height":if(!Int32.TryParse(value,out n) || Array.IndexOf(new[]{144,240,360,480,720,1080,1440,2160},n)<0)return false;Selection.Height=n;break;
             case "h264":if(value!="true" && value!="false")return false;Selection.H264=value=="true";break;
             case "fps":if(value!="true" && value!="false")return false;Selection.Fps30=value=="true";break;
             case "ontop":if(value!="true" && value!="false")return false;Selection.PlayerOnTop=value=="true";break;
             case "borderless":if(value!="true" && value!="false")return false;Selection.PlayerBorderless=value=="true";break;
             case "speed":if(!Double.TryParse(value,System.Globalization.NumberStyles.Float,System.Globalization.CultureInfo.InvariantCulture,out rate) || Double.IsNaN(rate) || rate<0.25 || rate>2)return false;Speed=rate;break;
             default:return false;
            }return true;
        }
        public VideoSettingsForm(Settings source,double speed,string actualDecoder="?"){
            Text=L.T("Video settings");Size=new Size(680,650);MinimumSize=new Size(620,480);Font=new Font("Segoe UI",9F);
            StartPosition=FormStartPosition.CenterScreen;AutoScaleMode=AutoScaleMode.Dpi;MaximizeBox=false;TopMost=true;
            var flow=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(16)};Controls.Add(flow);
            flow.Controls.Add(Ui.Label("Quality changes reuse mpv and keep position, pause and volume. A new stream must buffer. Changing renderer may restart mpv.",610));
            flow.Controls.Add(Ui.Label("Max. height, px:",610));var quality=Ui.Combo(new[]{"144","240","360","480","720","1080","1440","2160"},source.Height.ToString(),150);flow.Controls.Add(quality);
            var h264=new CheckBox{Text=L.T("H.264 only"),Checked=source.H264,AutoSize=true};flow.Controls.Add(h264);
            var fps=new CheckBox{Text=L.T("Playback: up to 30 FPS"),Checked=source.Fps30,AutoSize=true};flow.Controls.Add(fps);
            flow.Controls.Add(new Label{Text=L.T("Active decoder: {0}",actualDecoder),AutoSize=true,Tag="data"});
            flow.Controls.Add(Ui.Label("Decoder:",610));var decoder=Ui.Combo(Rules.Decoders,source.Decoder,230);flow.Controls.Add(decoder);
            flow.Controls.Add(Ui.Label("Renderer:",610));var renderer=Ui.Combo(Rules.Renderers,source.Renderer,230);flow.Controls.Add(renderer);
            Action matchRenderer=delegate{renderer.SelectedItem=Rules.CompatibleRenderer((string)decoder.SelectedItem,(string)renderer.SelectedItem);};
            decoder.SelectedIndexChanged+=delegate{matchRenderer();};renderer.SelectedIndexChanged+=delegate{matchRenderer();};matchRenderer();
            flow.Controls.Add(Ui.Label("Auto: compatible hardware. DXVA2: older GPUs. D3D11VA: newer Intel/AMD/NVIDIA. NVDEC: supported NVIDIA drivers. Copy modes use system RAM. No: CPU. Direct modes select a compatible renderer; actual support depends on the GPU, driver and codec.",610));
            flow.Controls.Add(Ui.Label("Playback speed",610));var rate=new NumericUpDown{DecimalPlaces=2,Increment=0.25M,Minimum=0.25M,Maximum=2M,Value=(decimal)Math.Max(0.25,Math.Min(2,speed)),Width=130};flow.Controls.Add(rate);
            var pin=new CheckBox{Text=L.T("Always on top"),Checked=source.PlayerOnTop,AutoSize=true};flow.Controls.Add(pin);
            var fullscreenTop=new CheckBox{Text=L.T("Keep above borderless fullscreen apps"),Checked=source.AboveFullscreen,AutoSize=true};flow.Controls.Add(fullscreenTop);fullscreenTop.CheckedChanged+=delegate{if(fullscreenTop.Checked)pin.Checked=true;};
            flow.Controls.Add(Ui.Label("Requires Always on top. Exclusive fullscreen games and secure desktops cannot be covered reliably.",610));
            var borderless=new CheckBox{Text=L.T("Borderless window"),Checked=source.PlayerBorderless,AutoSize=true};flow.Controls.Add(borderless);
            var safe=new CheckBox{Text=L.T("Pause audio while seeking (wait for video)"),Checked=source.SafeSeeking,AutoSize=true};flow.Controls.Add(safe);
            flow.Controls.Add(Ui.Label("Performance tuning",610));
            var tips=new ToolTip{AutoPopDelay=15000};Disposed+=delegate{tips.Dispose();};
            Func<string,string,bool,CheckBox> flag=delegate(string label,string help,bool value){var c=new CheckBox{Text=L.T(label),Checked=value,AutoSize=true};tips.SetToolTip(c,L.T(help));flow.Controls.Add(c);return c;};
            var flip=flag("D3D11 flip presentation","May help newer GPUs; older drivers can be slower. Changing this restarts mpv.",source.D3D11Flip);
            var zero=flag("D3D11 zero-copy","Avoids a GPU copy with compatible hardware decoding. Driver artifacts are possible.",source.D3D11ZeroCopy);
            flip.Enabled=zero.Enabled=(string)renderer.SelectedItem=="d3d11";
            renderer.SelectedIndexChanged+=delegate{flip.Enabled=zero.Enabled=(string)renderer.SelectedItem=="d3d11";};
            flow.Controls.Add(Ui.Label("Decoder threads (0 = automatic)",610));var threads=new NumericUpDown{Minimum=0,Maximum=16,Value=Math.Max(0,Math.Min(16,source.DecodeThreads)),Width=100};flow.Controls.Add(threads);
            flow.Controls.Add(Ui.Label("Scaling filter (bilinear = fastest)",610));var scale=Ui.Combo(PlayerTuning.Scales,PlayerTuning.Pick(source.VideoScale,PlayerTuning.Scales,"bilinear"),230);flow.Controls.Add(scale);
            flow.Controls.Add(Ui.Label("Frame dropping (vo = drop late displayed frames)",610));var drop=Ui.Combo(PlayerTuning.Drops,PlayerTuning.Pick(source.FrameDrop,PlayerTuning.Drops,"vo"),230);flow.Controls.Add(drop);
            tips.SetToolTip(drop,L.T("Decoder dropping can cause visible corruption. No dropping can increase lag."));
            flow.Controls.Add(Ui.Label("Video synchronization (audio = default)",610));var sync=Ui.Combo(PlayerTuning.Syncs,PlayerTuning.Pick(source.VideoSync,PlayerTuning.Syncs,"audio"),230);flow.Controls.Add(sync);
            var interpolation=flag("Frame interpolation","Requires display synchronization. Adds GPU work; does not create AI-generated frames.",source.VideoInterpolation);
            var deband=flag("Debanding","Reduces color banding but adds GPU work.",source.VideoDeband);
            var deinterlace=flag("Deinterlacing","For interlaced sources. Usually unnecessary for YouTube and increases processing.",source.VideoDeinterlace);
            var pitch=flag("Keep audio pitch when changing speed","Disabling may reduce audio processing, but pitch follows playback speed.",source.AudioPitchCorrection);
            flow.Controls.Add(Ui.Button("Reset performance tuning",delegate{flip.Checked=false;zero.Checked=false;threads.Value=2;scale.SelectedItem="bilinear";drop.SelectedItem="vo";sync.SelectedItem="audio";interpolation.Checked=deband.Checked=deinterlace.Checked=false;pitch.Checked=true;}));
            flow.Controls.Add(Ui.Label("Hover over options for details. Changes take effect after Apply; performance depends on hardware and drivers.",610));
            var buffer=new BufferEditor(source);flow.Controls.Add(buffer);
            flow.Controls.Add(Ui.Label("Video and separate audio each have a buffer. The memory cap can limit the requested seconds. A larger buffer does not increase the startup threshold.",610));
            var footer=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=45,Padding=new Padding(16,0,0,0)};Controls.Add(footer);
            var apply=Ui.Button("Apply",delegate{
                Selection=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(source));
                Selection.Height=Int32.Parse((string)quality.SelectedItem);Selection.H264=h264.Checked;Selection.Fps30=fps.Checked;
                Selection.Decoder=(string)decoder.SelectedItem;Selection.Renderer=(string)renderer.SelectedItem;Selection.SafeSeeking=safe.Checked;Selection.PlayerOnTop=pin.Checked;Selection.PlayerBorderless=borderless.Checked;Selection.AboveFullscreen=fullscreenTop.Checked;
                Selection.D3D11Flip=flip.Checked;Selection.D3D11ZeroCopy=zero.Checked;Selection.DecodeThreads=(int)threads.Value;
                Selection.VideoScale=(string)scale.SelectedItem;Selection.FrameDrop=(string)drop.SelectedItem;Selection.VideoSync=(string)sync.SelectedItem;
                Selection.VideoInterpolation=interpolation.Checked;Selection.VideoDeband=deband.Checked;Selection.VideoDeinterlace=deinterlace.Checked;Selection.AudioPitchCorrection=pitch.Checked;
                buffer.Write(Selection);Speed=(double)rate.Value;DialogResult=DialogResult.OK;Close();
            });footer.Controls.Add(apply);AcceptButton=apply;
            var cancel=Ui.Button("Cancel",delegate{Close();});footer.Controls.Add(cancel);CancelButton=cancel;Ui.Attach(this);
        }
    }
}
