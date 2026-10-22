using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;

namespace LowTube {
    public sealed class DownloadForm : Form {
        readonly List<Dictionary<string,object>> formats;
        readonly Settings settings;
        ComboBox mode,container,quality,bitrate,resolution;
        CheckBox fps,h264;
        Label hint,qualityLabel;
        Button download;
        bool rebuilding;
        public DownloadSelection Selection;
        public bool LimitFps { get { return fps.Checked; } }
        public bool H264Only { get { return h264.Checked; } }
        sealed class Entry {
            public Dictionary<string,object> Data;
            public string Caption;
            public override string ToString() { return Caption; }
        }
        bool Audio { get { return mode.SelectedIndex==1; } }
        string Kind { get { return container.SelectedItem as string??"MP4"; } }
        public DownloadForm(Settings s,Dictionary<string,object> info) {
            settings=s;formats=Json.Items(info,"formats").ToList();
            Text=L.T("Download options");ClientSize=new Size(660,660);MinimumSize=new Size(640,520);
            Font=new Font("Segoe UI",9F);AutoScaleMode=AutoScaleMode.Dpi;StartPosition=FormStartPosition.CenterParent;
            MaximizeBox=false;MinimizeBox=false;
            var flow=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(18)};
            Controls.Add(flow);
            flow.Controls.Add(new Label{Text=Json.Str(info,"title"),Tag="data",AutoSize=false,Size=new Size(605,42),Font=new Font(Font,FontStyle.Bold),AutoEllipsis=true});
            flow.Controls.Add(Ui.Label("Download type",600));
            mode=Ui.Combo(new[]{L.T("Video + audio"),L.T("Audio only")},L.T("Video + audio"),600);flow.Controls.Add(mode);
            flow.Controls.Add(Ui.Label("File format",600));container=Ui.Combo(new[]{"MP4","MKV","WebM"},Rules.KindCode(s.DownloadFormat),600);flow.Controls.Add(container);
            var row=new FlowLayoutPanel{AutoSize=true,WrapContents=false,Width=610,Margin=Padding.Empty};
            fps=new CheckBox{Text=L.T("Up to 30 FPS"),Checked=s.DownloadFps30,AutoSize=true,Margin=new Padding(0,6,18,6)};
            h264=new CheckBox{Text=L.T("H.264 only"),Checked=s.DownloadH264,AutoSize=true,Margin=new Padding(0,6,0,6)};
            row.Controls.Add(fps);row.Controls.Add(h264);flow.Controls.Add(row);
            flow.Controls.Add(Ui.Label("Download resolution",600));
            resolution=Ui.Combo(new[]{L.T("All available resolutions"),"720p","1080p","1440p (2K)","2160p (4K)"},L.T("All available resolutions"),600);flow.Controls.Add(resolution);
            resolution.SelectedIndexChanged+=delegate{
                if(resolution.SelectedIndex>=3 && !Audio){
                    rebuilding=true;container.SelectedItem="MKV";h264.Checked=false;fps.Checked=false;rebuilding=false;
                }
                RefreshFormats();
            };
            qualityLabel=Ui.Label("Quality — actual size / FPS / codec",600);flow.Controls.Add(qualityLabel);
            quality=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=600,DropDownWidth=820,Margin=new Padding(0,4,0,4)};flow.Controls.Add(quality);
            var audioRow=new FlowLayoutPanel{AutoSize=true,WrapContents=false,Width=610,Margin=Padding.Empty};
            audioRow.Controls.Add(Ui.Label("MP3 bitrate (kbps)",220));bitrate=Ui.Combo(new[]{"96","128","192","256","320"},s.AudioBitrate.ToString(),105);audioRow.Controls.Add(bitrate);flow.Controls.Add(audioRow);
            hint=new Label{AutoSize=false,Size=new Size(610,65),Margin=new Padding(0,8,0,8)};flow.Controls.Add(hint);
            var buttons=new FlowLayoutPanel{AutoSize=true,WrapContents=false,Width=610};
            download=Ui.Button("Download",Confirm);buttons.Controls.Add(download);
            var cancel=Ui.Button("Cancel",delegate{DialogResult=DialogResult.Cancel;Close();});buttons.Controls.Add(cancel);flow.Controls.Add(buttons);
            AcceptButton=download;CancelButton=cancel;
            mode.SelectedIndexChanged+=delegate{ChangeMode();};container.SelectedIndexChanged+=delegate{RefreshFormats();};
            fps.CheckedChanged+=delegate{RefreshFormats();};h264.CheckedChanged+=delegate{RefreshFormats();};
            quality.SelectedIndexChanged+=delegate{UpdateHint();};
            RefreshFormats();Ui.Attach(this);
        }
        void ChangeMode() {
            rebuilding=true;container.Items.Clear();container.Items.AddRange(Audio?new[]{"M4A","MP3","Opus"}:new[]{"MP4","MKV","WebM"});
            container.SelectedItem=Audio?Rules.KindCode(settings.AudioDownloadFormat):Rules.KindCode(settings.DownloadFormat);
            if(container.SelectedIndex<0)container.SelectedIndex=0;rebuilding=false;RefreshFormats();
        }
        void RefreshFormats() {
            if(rebuilding)return;
            string old=quality.SelectedItem is Entry?Json.Str(((Entry)quality.SelectedItem).Data,"format_id"):"";
            quality.BeginUpdate();quality.Items.Clear();
            resolution.Enabled=!Audio;fps.Enabled=!Audio;h264.Enabled=!Audio && (Kind=="MKV" || Kind=="MP4");bitrate.Enabled=Audio && Kind=="MP3";
            qualityLabel.Text=L.T(Audio?"Source audio quality":"Quality — actual size / FPS / codec");
            bool matchingAudio=Rules.HasDownloadAudio(formats,Kind);
            int tier=new[]{0,720,1080,1440,2160}[Math.Max(0,resolution.SelectedIndex)];
            var list=formats.Where(f=>(Audio || tier==0 || Rules.DownloadTier(f)==tier) && Rules.Downloadable(f,Kind,fps.Checked,h264.Checked) &&
                (Audio || Json.Str(f,"acodec")!="none" || matchingAudio))
                .OrderBy(f=>Audio?Rules.Number(f,"abr"):Rules.Number(f,"height"))
                .ThenBy(f=>Rules.Number(f,"fps")).ThenBy(f=>Rules.Number(f,"tbr")).ToList();
            foreach(var f in list) {
                string caption=Audio?Json.Str(f,"acodec")+" / "+L.T("{0} kbps",Json.Str(f,"abr",Json.Str(f,"tbr","?"))):
                    Rules.Dimensions(f)+" / "+Json.Str(f,"fps","?")+" FPS / "+Json.Str(f,"vcodec");
                caption+=" / "+Json.Str(f,"format_note")+" / ID "+Json.Str(f,"format_id");
                quality.Items.Add(new Entry{Data=f,Caption=caption});
            }
            var entries=quality.Items.Cast<Entry>().ToList();
            var chosen=entries.FirstOrDefault(e=>Json.Str(e.Data,"format_id")==old)??
                entries.LastOrDefault(e=>Audio || Rules.Number(e.Data,"height")<=settings.DownloadHeight)??entries.FirstOrDefault();
            if(chosen!=null)quality.SelectedItem=chosen;
            quality.EndUpdate();UpdateHint();
        }
        void UpdateHint() {
            download.Enabled=quality.SelectedItem!=null;
            if(!download.Enabled) { hint.Text=L.T("No matching streams. Try another format or remove the FPS / codec limit.");return; }
            if(!Audio && resolution.SelectedIndex>=3){hint.Text=L.T("1440p / 4K presets select MKV and allow all codecs and frame rates. Choose a native stream below. Availability depends on the video; no upscaling or re-encoding.");return;}
            hint.Text=L.T(Audio?(Kind=="MP3"?"Only audio is downloaded. MP3 conversion uses the CPU; a higher bitrate does not restore lost detail.":"Only the selected audio stream is downloaded, without re-encoding."):
                "The selected native resolution is preserved. No upscaling or stretching. Higher resolution / FPS may not play smoothly on this PC.");
        }
        void Confirm() {
            var entry=quality.SelectedItem as Entry;if(entry==null)return;
            Selection=new DownloadSelection{Kind=Kind,FormatId=Json.Str(entry.Data,"format_id"),
                Combined=!Audio && Json.Str(entry.Data,"acodec")!="none",Height=(int)Rules.Number(entry.Data,"height"),Bitrate=Int32.Parse((string)bitrate.SelectedItem)};
            DialogResult=DialogResult.OK;Close();
        }
    }
}
