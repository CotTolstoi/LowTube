using System;
using System.Collections.Generic;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace LowTube {
    public sealed class ChannelsForm : Form {
        readonly List<LocalChannel> channels=LocalFeeds.Load();
        readonly ListBox list;
        readonly Label status;
        CancellationTokenSource pending;
        public ChannelsForm(Settings settings) {
            Text=L.T("Local channels");Size=new Size(700,490);MinimumSize=new Size(580,400);
            StartPosition=FormStartPosition.CenterParent;Font=new Font("Segoe UI",9F);AutoScaleMode=AutoScaleMode.Dpi;
            var layout=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(16),ColumnCount=1,RowCount=5};
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute,56));layout.RowStyles.Add(new RowStyle(SizeType.Percent,100));
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute,36));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,42));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,50));Controls.Add(layout);
            var note=Ui.Label("Add a channel URL or @handle. These subscriptions are saved on this PC only. The feed contains recent public uploads, not the full channel archive.",630);note.Dock=DockStyle.Fill;layout.Controls.Add(note,0,0);
            list=new ListBox{Dock=DockStyle.Fill,HorizontalScrollbar=true,IntegralHeight=false};layout.Controls.Add(list,0,1);RefreshList();
            var input=new TextBox{Dock=DockStyle.Fill,Margin=new Padding(0,6,0,0),MaxLength=2048,AccessibleName=L.T("Channel URL or @handle")};layout.Controls.Add(input,0,2);
            var row=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};layout.Controls.Add(row,0,3);
            var add=new Button{Text=L.T("Add channel"),AutoSize=true,Height=30};row.Controls.Add(add);
            var remove=Ui.Button("Remove channel",delegate{
                var chosen=list.SelectedItem as LocalChannel;if(chosen==null)return;
                try{var updated=new List<LocalChannel>(channels);updated.Remove(chosen);LocalFeeds.Save(updated);channels.Remove(chosen);RefreshList();status.Text=L.T("Channel removed.");}
                catch(Exception e){status.Text=e.Message;}
            });row.Controls.Add(remove);
            var show=Ui.Button("Open feed",delegate{DialogResult=DialogResult.OK;Close();});row.Controls.Add(show);
            var close=Ui.Button("Close",delegate{Close();});row.Controls.Add(close);CancelButton=close;
            status=new Label{Dock=DockStyle.Fill,Tag="data",AutoEllipsis=true};layout.Controls.Add(status,0,4);
            add.Click+=async delegate{
                if(pending!=null)return;
                var own=new CancellationTokenSource();pending=own;add.Enabled=remove.Enabled=show.Enabled=input.Enabled=false;
                status.Text=L.T("Adding channel…");
                try {
                    var channel=await new Backend(settings).ResolveChannel(input.Text,own.Token,delegate{});
                    own.Token.ThrowIfCancellationRequested();if(IsDisposed)return;
                    var updated=new List<LocalChannel>(channels);bool added=LocalFeeds.Add(updated,channel);
                    if(added){LocalFeeds.Save(updated);channels.Add(channel);RefreshList();input.Clear();}
                    status.Text=L.T(added?"Subscribed locally: {0}":"Already subscribed: {0}",channel.Title);
                }catch(OperationCanceledException){}catch(Exception e){if(!IsDisposed)status.Text=Rules.Redact(e.Message);}
                finally{pending=null;own.Dispose();if(!IsDisposed)add.Enabled=remove.Enabled=show.Enabled=input.Enabled=true;}
            };
            FormClosing+=delegate{if(pending!=null)pending.Cancel();};Ui.Attach(this);
        }
        void RefreshList(){list.BeginUpdate();list.Items.Clear();foreach(var channel in channels)list.Items.Add(channel);list.EndUpdate();}
    }
}
