using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace LowTube {
    public sealed class GoogleAccountForm : Form {
        CancellationTokenSource operation;bool closing;
        readonly List<Control> locked=new List<Control>();Label state;Button cancel;
        public GoogleAccountForm(Settings settings){
            Text=L.T("Google account");Size=new Size(710,620);MinimumSize=new Size(600,480);StartPosition=FormStartPosition.CenterParent;Font=new Font("Segoe UI",9F);AutoScaleMode=AutoScaleMode.Dpi;
            var panel=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(20)};Controls.Add(panel);
            Add(panel,"Connect YouTube",delegate{using(var session=new AccountForm(settings,true))session.ShowDialog(this);Backend.ClearMetadataCache();});
            state=new Label{AutoSize=true,MaximumSize=new Size(640,0)};panel.Controls.Add(state);
            panel.Controls.Add(Ui.Label("Sign in on Google's website in your default browser. LowTube never asks for your Google password.",640));
            Add(panel,"Sign in with Google",async delegate{await Run(async ct=>{await GoogleAccount.SignIn(ct);});});
            panel.Controls.Add(Ui.Label("Google sign-in provides read-only access to your YouTube subscriptions. Personal recommendations and watch history are not available through the official API.",640));
            Add(panel,"Import OAuth client JSON",delegate{using(var file=new OpenFileDialog{Filter="Google OAuth JSON (*.json)|*.json"})if(file.ShowDialog(this)==DialogResult.OK){try{GoogleAccount.ImportClient(file.FileName);UpdateState();}catch(Exception e){MessageBox.Show(this,e.Message);}}});
            panel.Controls.Add(Ui.Label("One-time setup: create a Desktop app OAuth client, enable YouTube Data API v3, and import its JSON file. No client ID is bundled.",640));
            Add(panel,"Google sign-in instructions",delegate{Process.Start(new ProcessStartInfo(Path.Combine(Paths.Root,"GOOGLE_SIGN_IN_"+(L.Language=="en"?"EN":L.Language=="ru"?"RU":"UK")+".txt")){UseShellExecute=true});});
            Add(panel,"Disconnect Google on this PC",delegate{GoogleAccount.Disconnect();UpdateState();});
            Add(panel,"Revoke Google access",async delegate{await Run(async ct=>{await GoogleAccount.Revoke(ct);});});
            panel.Controls.Add(Ui.Label("Disconnect removes the saved token from this PC. Revoke also removes Google's authorization for this OAuth client. Neither changes your subscriptions.",640));
            panel.Controls.Add(Ui.Label("Browser session (existing features)",640));
            Add(panel,"Browsers / cookies settings",delegate{using(var old=new AccountForm(settings,false,true))old.ShowDialog(this);Backend.ClearMetadataCache();});
            panel.Controls.Add(Ui.Label("Browser sessions and cookies remain available for experimental YouTube history and optional signed-in playback. They are separate from Google OAuth.",640));
            cancel=Ui.Button("Cancel sign-in",delegate{if(operation!=null)operation.Cancel();});cancel.Enabled=false;panel.Controls.Add(cancel);
            UpdateState();Ui.Attach(this);FormClosing+=delegate{closing=true;if(operation!=null)operation.Cancel();};
        }
        void Add(Control panel,string label,Action action){var button=Ui.Button(label,delegate{try{action();}catch(Exception e){MessageBox.Show(this,e.Message);}});locked.Add(button);panel.Controls.Add(button);}
        void UpdateState(){state.Text=L.T(GoogleAccount.Connected?"Google: connected (saved session)":GoogleAccount.Configured?"Google: ready to sign in":"Google: OAuth client setup required");}
        async Task Run(Func<CancellationToken,Task> action){
            if(operation!=null)return;var own=new CancellationTokenSource();operation=own;foreach(var c in locked)c.Enabled=false;cancel.Enabled=true;state.Text=L.T("Waiting for Google…");
            try{await action(own.Token);}
            catch(OperationCanceledException){if(!closing)MessageBox.Show(this,L.T("Google sign-in cancelled or timed out."));}
            catch(Exception e){if(!closing)MessageBox.Show(this,e.Message);}
            finally{operation=null;own.Dispose();if(!closing){foreach(var c in locked)c.Enabled=true;cancel.Enabled=false;UpdateState();}}
        }
    }
}
