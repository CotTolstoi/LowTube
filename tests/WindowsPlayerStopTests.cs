using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using LowTube;
public static class WindowsPlayerStopTests {
 static int count;
 sealed class TestContext : SynchronizationContext {
  readonly BlockingCollection<Action> callbacks=new BlockingCollection<Action>();
  public override void Post(SendOrPostCallback callback,object state){callbacks.Add(()=>callback(state));}
  public void Pump(Task task){while(!task.IsCompleted){Action next;if(callbacks.TryTake(out next,100))next();}task.GetAwaiter().GetResult();}
 }
 static void Check(bool ok,string name){if(!ok)throw new Exception(name);count++;Console.WriteLine("PASS: "+name);}
 static void Set(Player player,string name,object value){typeof(Player).GetField(name,BindingFlags.Instance|BindingFlags.NonPublic).SetValue(player,value);}
 static async Task Within(Task task){if(await Task.WhenAny(task,Task.Delay(4000))!=task)throw new TimeoutException("Fixture operation timed out");await task;}
 static async Task Drain(StreamReader reader){char[] buffer=new char[2048];while(await reader.ReadAsync(buffer,0,buffer.Length)>0){}}
 static ProcessStartInfo FixtureInfo(string pipe,string hook){
  var args=new[]{"--no-config","--load-scripts=no","--ytdl=no","--idle=yes","--force-window=no","--terminal=no","--vo=null","--ao=null","--hwdec=no","--msg-level=all=no","--input-ipc-server="+@"\\.\pipe\"+pipe,"--script="+Path.Combine(Paths.Root,"player","lowtube.lua"),"--script="+Path.Combine(Paths.Root,"player","lowtube_osc.lua"),"--script="+hook};
  return new ProcessStartInfo(Paths.Tool("mpv"),Rules.Arguments(args)){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=Paths.Root};
 }
 static async Task Attach(Player player,Process process,string pipe,Settings settings){
  var ready=new TaskCompletionSource<bool>();Set(player,"process",process);Set(player,"pipe",pipe);Set(player,"activeRenderer",PlayerTuning.ProcessKey(settings));Set(player,"ready",ready);
  typeof(Player).GetMethod("Listen",BindingFlags.Instance|BindingFlags.NonPublic).Invoke(player,new object[]{pipe,process,Stopwatch.StartNew(),new Action<string>(delegate{})});await Within(ready.Task);
 }
 static async Task WaitFor(Player player,string property,object expected){for(int i=0;i<100;i++){if(Object.Equals(await player.Command("get_property",property),expected))return;await Task.Delay(20);}throw new TimeoutException("Fixture property timed out: "+property);}
 public static int Main(){var previous=SynchronizationContext.Current;try{var context=new TestContext();SynchronizationContext.SetSynchronizationContext(context);context.Pump(Run());Console.WriteLine("TOTAL: "+count+" Windows player stop checks passed");return 0;}catch(Exception e){Console.Error.WriteLine("Windows stop fixture failed: "+e);return 1;}finally{SynchronizationContext.SetSynchronizationContext(previous);}}
 static async Task Run(){
  string root=Path.GetFullPath(Path.GetTempPath()),dir=Path.Combine(root,"lowtube-stop-fixture-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(dir);
  try{
   string path=Path.Combine(dir,"fixture.wav"),slow=Path.Combine(dir,"slow.wav"),hook=Path.Combine(dir,"delay.lua");
   using(var output=new BinaryWriter(File.Create(path))){int bytes=8000*2*4;output.Write(Encoding.ASCII.GetBytes("RIFF"));output.Write(36+bytes);output.Write(Encoding.ASCII.GetBytes("WAVEfmt "));output.Write(16);output.Write((short)1);output.Write((short)1);output.Write(8000);output.Write(16000);output.Write((short)2);output.Write((short)16);output.Write(Encoding.ASCII.GetBytes("data"));output.Write(bytes);output.Write(new byte[bytes]);}File.Copy(path,slow);
   // A bounded local hook makes closing during load deterministic, without a network stall.
   File.WriteAllText(hook,"local mp=require 'mp'\nmp.add_hook('on_load',80,function(h) if mp.get_property('path',''):match('slow%.wav$') then h:defer();mp.set_property_native('user-data/lowtube-fixture-waiting',true);mp.add_timeout(0.5,function() mp.set_property_native('user-data/lowtube-fixture-waiting',false);h:cont() end) end end)\n",new UTF8Encoding(false));
   string pipe="lowtube-stop-"+Guid.NewGuid().ToString("N");
   using(var process=new Process{StartInfo=FixtureInfo(pipe,hook)})
   using(var job=new ProcessJob())using(var timeout=new CancellationTokenSource(25000))using(var player=new Player()){
    process.Start();job.Attach(process);int pid=process.Id;var stdout=Drain(process.StandardOutput);var stderr=Drain(process.StandardError);
    using(timeout.Token.Register(()=>job.Kill())){
     var settings=new Settings();
     // Exercise the production Player IPC/state machine with null audio/video outputs.
     await Attach(player,process,pipe,settings);
     var media=new Media{VideoUrl=path};int stopped=0,ended=0,failed=0;
     player.Stopped+=()=>stopped++;player.PlaybackEnded+=(s,n)=>ended++;player.PlaybackFailed+=(s,m)=>failed++;
     await Within(player.Start(settings,media,true,delegate{},0,true));string owner=player.Session;
     await Within(player.StageNext(settings,media,true,0,owner,"fixture-next"));Check(player.HasPrepared(owner),"native next item is staged before close");
     await player.Command("keypress","q");await WaitFor(player,"idle-active",true);await Task.Delay(100);
     Check(player.Session==null && !player.Running && stopped==1,"q clears the current Player session and reports Stop once");
     Check(!player.HasPrepared(owner) && Object.Equals(await player.Command("get_property","prefetch-playlist"),false),"close cancels native prefetch and the prepared host ticket");
     Check(ended==0 && failed==0,"closing produces no EOF/recovery failure callbacks");
     await Within(player.Start(settings,media,true,delegate{},0,true));string restarted=player.Session;
     Check(restarted!=null && restarted!=owner && player.Running && process.Id==pid,"explicit playback after close reuses mpv successfully");
     await player.Command("script-message","lowtube-stopped",owner);
     await player.Command("script-message","lowtube-next-start","fixture-next");await Task.Delay(100);
     Check(player.Session==restarted && player.Running && stopped==1,"late Stop and prepared-start messages cannot replace the new session");
     await player.Command("set_property","user-data/lowtube-fixture-waiting",false);
     int refreshes=0;var info=new Dictionary<string,object>{{"url",slow}};
     var opening=StreamOpenRecovery.Open(info,true,()=>{refreshes++;return Task.FromResult(info);},i=>player.Start(settings,new Media{VideoUrl=Json.Str(i,"url")},true,delegate{},0,true),delegate{},CancellationToken.None);
     await WaitFor(player,"user-data/lowtube-fixture-waiting",true);await player.Command("keypress","CLOSE_WIN");
     bool cancelled=false;try{await Within(opening);}catch(OperationCanceledException){cancelled=true;}
     Check(cancelled && refreshes==0,"CLOSE_WIN during load cancels Start without a fresh-link retry");
     await WaitFor(player,"idle-active",true);await Task.Delay(150);
     Check(player.Session==null && !player.Running && stopped==2,"closed pending load remains stopped after deferred on_load completes");
     await Within(player.Start(settings,media,true,delegate{},0,true));Check(player.Running && player.Session!=null,"explicit Start still succeeds after a cancelled pending load");
     var transition=new TaskCompletionSource<bool>();player.PreparedStarted+=ticket=>{if(ticket=="fixture-transition")transition.TrySetResult(true);};
     await Within(player.StageNext(settings,media,true,0,player.Session,"fixture-transition"));
     await player.Command("seek",3.9,"absolute");await player.Command("set_property","pause",false);await Within(transition.Task);
     Check(player.Session=="fixture-transition" && player.Running,"natural EOF still starts the staged next item");
     Check(!player.HasPrepared(player.Session),"normal transition consumes exactly one prepared ticket");
     player.Stop();await WaitFor(player,"idle-active",true);
     Check(player.Session==null && !player.Running,"programmatic Stop uses the same native stop/playlist cleanup");
     await Within(player.Start(settings,media,true,delegate{},0,true));
     string oscOwner=player.Session;await Within(player.StageNext(settings,media,true,0,oscOwner,"fixture-osc-next"));int beforeOsc=stopped;
     // Calls the exact function assigned to the shipped OSC close button.
     await player.Command("script-binding","lowtube_osc/close-player");await WaitFor(player,"idle-active",true);await Task.Delay(100);
     Check(stopped==beforeOsc+1 && player.Session==null && !player.HasPrepared(oscOwner),"OSC close callback cancels playback and prepared next item");
     Check(player.Alive && process.Id==pid,"OSC close keeps the same idle mpv rather than quitting");
     await Within(player.Start(settings,media,true,delegate{},0,true));
     string quitOwner=player.Session;await Within(player.StageNext(settings,media,true,0,quitOwner,"fixture-quit-next"));
     int beforeQuit=stopped,beforeQuitFailures=failed;
     var quitStopped=new TaskCompletionSource<bool>();player.Stopped+=()=>quitStopped.TrySetResult(true);
     await player.Command("quit");await Within(quitStopped.Task);
     Check(stopped==beforeQuit+1 && failed==beforeQuitFailures,"native quit is Stop rather than a recoverable IPC failure");
     Check(player.Session==null && !player.Running && !player.HasPrepared(quitOwner),"native quit clears session and pending next item");
     player.Dispose();job.Kill();await stdout;await stderr;
    }
   }
   await ExitDuringLoad(slow,hook,false);
   await ExitDuringLoad(slow,hook,true);
   await IdleExit(path,hook);
  }finally{if(!Path.GetFullPath(dir).StartsWith(root,StringComparison.OrdinalIgnoreCase))throw new InvalidOperationException();Directory.Delete(dir,true);}
 }
 static async Task ExitDuringLoad(string path,string hook,bool crash){
  string pipe="lowtube-exit-"+Guid.NewGuid().ToString("N");
  using(var process=new Process{StartInfo=FixtureInfo(pipe,hook)})using(var job=new ProcessJob())using(var timeout=new CancellationTokenSource(10000))using(var player=new Player()){
   process.Start();job.Attach(process);var stdout=Drain(process.StandardOutput);var stderr=Drain(process.StandardError);
   using(timeout.Token.Register(()=>job.Kill())){
    var settings=new Settings();await Attach(player,process,pipe,settings);int stopped=0,failed=0,refreshes=0;
    player.Stopped+=()=>stopped++;player.PlaybackFailed+=(s,m)=>failed++;
    await player.Command("set_property","user-data/lowtube-fixture-waiting",false);
    var info=new Dictionary<string,object>{{"url",path}};
    var opening=StreamOpenRecovery.Open(info,true,()=>{refreshes++;return Task.FromResult(info);},i=>player.Start(settings,new Media{VideoUrl=path},true,delegate{},0,true),delegate{},CancellationToken.None);
    await WaitFor(player,"user-data/lowtube-fixture-waiting",true);
    if(crash)job.Kill();else await player.Command("quit");
    Exception error=null;try{await Within(opening);}catch(Exception e){error=e;}await Task.Delay(100);
    if(crash)Check(error!=null && !(error is OperationCanceledException) && failed==1 && stopped==0,"unexpected process loss remains a failure, not a user Stop");
    else{
     Check(error is OperationCanceledException && stopped==1 && failed==0,"quit during pending load cancels rather than reporting failure");
     Check(refreshes==0 && player.Session==null && !player.Running,"quit during pending load cannot refresh or restart playback");
    }
    player.Dispose();job.Kill();await stdout;await stderr;
   }
  }
 }
 static async Task IdleExit(string path,string hook){
  string pipe="lowtube-idle-exit-"+Guid.NewGuid().ToString("N");
  using(var process=new Process{StartInfo=FixtureInfo(pipe,hook)})using(var job=new ProcessJob())using(var timeout=new CancellationTokenSource(10000))using(var player=new Player()){
   process.Start();job.Attach(process);var stdout=Drain(process.StandardOutput);var stderr=Drain(process.StandardError);
   using(timeout.Token.Register(()=>job.Kill())){
    var settings=new Settings();await Attach(player,process,pipe,settings);int stopped=0,failed=0;var ended=new TaskCompletionSource<bool>();var quitStopped=new TaskCompletionSource<bool>();
    player.Stopped+=()=>{stopped++;quitStopped.TrySetResult(true);};player.PlaybackFailed+=(s,m)=>failed++;player.PlaybackEnded+=(s,n)=>ended.TrySetResult(true);
    await Within(player.Start(settings,new Media{VideoUrl=path},true,delegate{},0,true));string owner=player.Session;
    await player.Command("seek",3.9,"absolute");await player.Command("set_property","pause",false);await Within(ended.Task);
    Check(!player.Running && player.Session==owner,"EOF retains session ownership until queue/recovery decision");
    await player.Command("quit");await Within(quitStopped.Task);
    Check(stopped==1 && failed==0 && player.Session==null,"orderly idle exit cancels retained recovery ownership without another end-file");
    player.Dispose();job.Kill();await stdout;await stderr;
   }
  }
 }
}
