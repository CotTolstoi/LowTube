using System;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace LowTube {
    public sealed class EqualizerForm : Form {
        readonly EqualizerSettings draft;readonly TrackBar[] bands=new TrackBar[10];readonly Label[] values=new Label[10];
        readonly ComboBox preset;readonly NumericUpDown preamp;readonly CheckBox enabled;readonly Label result;bool updating,busy;
        public EqualizerForm(EqualizerSettings initial,Func<EqualizerSettings,Task> apply){
            draft=AudioEqualizer.Copy(initial);Text=L.T("Equalizer");Size=new Size(810,530);MinimumSize=new Size(720,480);StartPosition=FormStartPosition.CenterParent;Font=new Font("Segoe UI",9F);AutoScaleMode=AutoScaleMode.Dpi;
            var root=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(16),ColumnCount=1,RowCount=5};
            root.RowStyles.Add(new RowStyle(SizeType.Absolute,48));root.RowStyles.Add(new RowStyle(SizeType.Percent,100));root.RowStyles.Add(new RowStyle(SizeType.Absolute,45));root.RowStyles.Add(new RowStyle(SizeType.Absolute,65));root.RowStyles.Add(new RowStyle(SizeType.Absolute,44));Controls.Add(root);
            var top=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};root.Controls.Add(top,0,0);
            enabled=new CheckBox{Text=L.T("Enable equalizer"),AutoSize=true,Checked=draft.Enabled,Margin=new Padding(0,8,20,0)};top.Controls.Add(enabled);
            preset=Ui.Combo(AudioEqualizer.Names.Select(x=>L.T(x)).ToArray(),L.T(AudioEqualizer.Names[Math.Max(0,Array.IndexOf(AudioEqualizer.Ids,draft.Preset))]),210);top.Controls.Add(preset);
            var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=10,RowCount=3};grid.RowStyles.Add(new RowStyle(SizeType.Absolute,25));grid.RowStyles.Add(new RowStyle(SizeType.Percent,100));grid.RowStyles.Add(new RowStyle(SizeType.Absolute,25));root.Controls.Add(grid,0,1);
            for(int i=0;i<10;i++){
                int index=i;grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,10));int frequency=AudioEqualizer.Frequencies[i];
                grid.Controls.Add(new Label{Text=frequency<1000?frequency+" Hz":(frequency/1000)+" kHz",Tag="data",Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleCenter},i,0);
                bands[i]=new TrackBar{Minimum=-12,Maximum=12,TickFrequency=3,SmallChange=1,LargeChange=3,Orientation=Orientation.Vertical,Dock=DockStyle.Fill,Value=draft.Bands[i],AccessibleName=frequency+" Hz"};grid.Controls.Add(bands[i],i,1);
                values[i]=new Label{Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleCenter,Tag="data"};grid.Controls.Add(values[i],i,2);
                bands[i].ValueChanged+=delegate{if(updating)return;draft.Bands[index]=bands[index].Value;RememberCustom();RefreshValues();};
            }
            var gainRow=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};gainRow.Controls.Add(Ui.Label("Preamp (dB)",180));preamp=new NumericUpDown{Minimum=-12,Maximum=12,Value=draft.Preamp,Width=80};gainRow.Controls.Add(preamp);root.Controls.Add(gainRow,0,2);
            preamp.ValueChanged+=delegate{if(updating)return;draft.Preamp=(int)preamp.Value;RememberCustom();};
            preset.SelectedIndexChanged+=delegate{if(updating || preset.SelectedIndex<0)return;AudioEqualizer.Preset(draft,AudioEqualizer.Ids[preset.SelectedIndex]);updating=true;for(int i=0;i<10;i++)bands[i].Value=draft.Bands[i];preamp.Value=draft.Preamp;updating=false;RefreshValues();};
            result=Ui.Label("Press Apply to hear and save changes. Positive bands automatically reduce input gain to leave headroom.",760);result.Dock=DockStyle.Fill;root.Controls.Add(result,0,3);
            var footer=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};root.Controls.Add(footer,0,4);
            var applyButton=new Button{Text=L.T("Apply"),AutoSize=true,Height=32};var close=new Button{Text=L.T("Close"),AutoSize=true,Height=32};footer.Controls.Add(applyButton);footer.Controls.Add(close);
            applyButton.Click+=async delegate{if(busy)return;busy=true;applyButton.Enabled=close.Enabled=false;
                try{draft.Enabled=enabled.Checked;await apply(AudioEqualizer.Copy(draft));if(!IsDisposed)result.Text=L.T("Equalizer saved and applied.");}
                catch(Exception e){if(!IsDisposed)result.Text=Rules.Redact(e.Message);}
                finally{busy=false;if(!IsDisposed)applyButton.Enabled=close.Enabled=true;}
            };
            close.Click+=delegate{Close();};FormClosing+=delegate(object sender,FormClosingEventArgs e){if(busy)e.Cancel=true;};AcceptButton=applyButton;CancelButton=close;RefreshValues();Ui.Attach(this);
        }
        void RememberCustom(){draft.CustomBands=(int[])draft.Bands.Clone();draft.CustomPreamp=draft.Preamp;draft.Preset="custom";updating=true;preset.SelectedIndex=6;updating=false;}
        void RefreshValues(){for(int i=0;i<10;i++)values[i].Text=bands[i].Value.ToString("+0;-0;0")+" dB";}
    }
}
