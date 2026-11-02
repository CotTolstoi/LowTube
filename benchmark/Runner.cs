using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
namespace LowTube {
 public static class BenchRunner {
  public static async Task Download(Settings settings,string url,string selector,string output,double start,double end,CancellationToken ct){
   var a=new List<string>{"--ignore-config","--no-plugin-dirs","--encoding","utf-8","--no-colors","--no-progress","--socket-timeout","20","--retries","1","--fragment-retries","1","--extractor-retries","1","--no-js-runtimes","--js-runtimes",JavaScriptRuntime.Argument,"--ffmpeg-location",Path.GetDirectoryName(Paths.Tool("ffmpeg")),"--no-playlist","--no-part","--force-overwrites","--download-sections","*"+start.ToString("0.###",CultureInfo.InvariantCulture)+"-"+end.ToString("0.###",CultureInfo.InvariantCulture),"--format",selector,"--merge-output-format","mkv","--remux-video","mkv","--output",Path.Combine(Path.GetDirectoryName(output),"clip.%(ext)s")};
   SavedSession.Lease lease=null;
   try{
    if(settings.AuthMedia){
     if(settings.Auth=="saved"){lease=new SavedSession.Lease(true);a.AddRange(new[]{"--cookies",lease.FilePath});}
     else if(settings.Auth=="file")a.AddRange(new[]{"--cookies",settings.CookiesFile});
     else if(settings.Auth=="firefox" || settings.Auth=="browser")a.AddRange(new[]{"--cookies-from-browser",BrowserSessions.Argument(BrowserSessions.Browser(settings),BrowserSessions.Profile(settings))});
    }
    a.Add("--");a.Add(url);var r=await Runner.Run(Paths.Tool("yt-dlp"),a,ct,300,false,null);if(r.Code!=0)throw new InvalidOperationException(r.Error);
    if(!File.Exists(output))throw new IOException("Downloaded clip was not created.");
   }finally{if(lease!=null)lease.Dispose();}
  }
  public static async Task<BenchResult> Run(BenchCase test,string file,int seconds,Action<string> log,CancellationToken ct){
   string dir=Path.Combine(Path.GetTempPath(),"LowTube-bench-worker-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(dir);
   string input=Path.Combine(dir,"input.json"),output=Path.Combine(dir,"result.json");
   Json.Save(input,new BenchWorkerInput{Test=new BenchCaseData{Name=test.Name,Options=BenchCheckpoint.Clean(test.Options)},File=file,Output=output,Seconds=seconds});
   string remainder="";object outputGate=new object();Action<string> forward=raw=>{lock(outputGate){remainder+=raw;int newline;while((newline=remainder.IndexOf('\n'))>=0){log(remainder.Substring(0,newline).TrimEnd('\r'));remainder=remainder.Substring(newline+1);}}};
   try{
    // A separate process owns mpv. Runner's job object kills the whole worker tree on timeout/cancel,
    // including when WMI, performance counters or IPC inside the worker stop responding.
    var run=await Runner.Run(typeof(BenchmarkProgram).Assembly.Location,new[]{"--worker",input},ct,seconds+60,false,forward);
    if(run.Code!=0 || !System.IO.File.Exists(output)){log("WORKER FAILED: "+run.Code);return new BenchResult{Name=test.Name,Status="WORKER_FAILED"};}
    return Json.Load(output,new BenchResult{Name=test.Name,Status="WORKER_RESULT_MISSING"});
   }catch(OperationCanceledException){if(ct.IsCancellationRequested)throw;log("WATCHDOG TIMEOUT: "+test.Name);return new BenchResult{Name=test.Name,Status="WATCHDOG_TIMEOUT"};}
   catch(TimeoutException){log("WATCHDOG TIMEOUT: "+test.Name);return new BenchResult{Name=test.Name,Status="WATCHDOG_TIMEOUT"};}
   catch(Exception e){log("WORKER ERROR: "+Rules.Redact(e.Message));return new BenchResult{Name=test.Name,Status="WORKER_FAILED"};}
   finally{try{Directory.Delete(dir,true);}catch(Exception){}}
  }
  public static async Task<BenchResult> RunLocal(BenchCase test,string file,int seconds,Action<string> log,CancellationToken ct){
   var result=new BenchResult{Name=test.Name};string pipe="lowtube-bench-"+Guid.NewGuid().ToString("N");
   var args=Player.BuildArgs(test.Options,new Media{Title="LowTube benchmark: "+test.Name,VideoUrl=file},false,@"\\.\pipe\"+pipe);
   args.RemoveRange(args.Count-2,2);args.RemoveAll(x=>x.StartsWith("--script=") || x.StartsWith("--script-opts=") || x.StartsWith("--autofit=") || x.StartsWith("--keep-open="));
   args.AddRange(new[]{"--script="+Path.Combine(Paths.Root,"benchmark","sample.lua"),"--keep-open=yes","--geometry=960x540","--autofit=960x540","--input-default-bindings=no","--input-vo-keyboard=no","--terminal=no","--",file});
   log("CASE "+test.Name+"\nOPTIONS "+Rules.Arguments(args.Where(x=>!x.StartsWith("--input-ipc-server=")).Take(args.Count-2)));
   var errors=new StringBuilder();var watch=Stopwatch.StartNew();
   using(var p=new Process{StartInfo=new ProcessStartInfo(Paths.Tool("mpv"),Rules.Arguments(args)){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true}})
   using(var group=new ProcessJob()){
    p.ErrorDataReceived+=delegate(object sender,DataReceivedEventArgs e){if(e.Data!=null)lock(errors){if(errors.Length<16000)errors.AppendLine(e.Data);}};
    p.OutputDataReceived+=delegate{};
    try{
     p.Start();group.Attach(p);p.BeginErrorReadLine();p.BeginOutputReadLine();
     using(var client=new NamedPipeClientStream(".",pipe,PipeDirection.InOut,PipeOptions.Asynchronous)){
      while(!client.IsConnected && watch.Elapsed.TotalSeconds<20){ct.ThrowIfCancellationRequested();if(p.HasExited)throw new IOException("mpv exited: "+p.ExitCode);try{client.Connect(300);}catch(TimeoutException){}await Task.Delay(50,ct);}
      if(!client.IsConnected)throw new TimeoutException("IPC startup timeout");
      using(var reader=new StreamReader(client,Encoding.UTF8,false,8192,true))
      using(var metrics=new BenchMetrics(p)){
       log("COUNTERS "+metrics.Availability);Task<string> pending=null;double ready=-1,measure=-1,firstPos=0,firstDrop=0,firstDecoderDrop=0;
       while(watch.Elapsed.TotalSeconds<seconds+35){
        ct.ThrowIfCancellationRequested();if(p.HasExited){result.Status="PROCESS_EXIT";break;}
        if(pending==null)pending=reader.ReadLineAsync();
        if(await Task.WhenAny(pending,Task.Delay(100,ct))!=pending)continue;
        string line=await pending;pending=null;if(line==null){result.Status="IPC_CLOSED";break;}if(line.Length>65536)continue;
        var message=Json.Object(line);var items=BenchPlan.Get(message,"args") as object[];
        if(items==null || items.Length!=2 || !Object.Equals(items[0],"lowtube-benchmark-sample"))continue;
        var data=Json.Object(Convert.ToString(items[1]));
        if(Object.Equals(BenchPlan.Get(data,"ended"),true)){result.Status="EARLY_END";break;}
        if(!Object.Equals(BenchPlan.Get(data,"ready"),true))continue;
        if(ready<0){ready=watch.Elapsed.TotalSeconds;result.ReadySeconds=ready;}
        var resources=metrics.Read(p);
        if(watch.Elapsed.TotalSeconds-ready<3)continue;
        if(measure<0){measure=watch.Elapsed.TotalSeconds;firstPos=Rules.Number(data,"time-pos");firstDrop=Rules.Number(data,"frame-drop-count");firstDecoderDrop=Rules.Number(data,"decoder-frame-drop-count");}
        result.Fps=Rules.Number(data,"container-fps");result.Samples++;result.Actual=Json.Str(data,"hwdec-current","unknown");
        double cpu=Rules.Number(resources,"mpv_cpu_percent_machine");result.Cpu+=cpu;result.PeakCpu=Math.Max(result.PeakCpu,cpu);result.Ram=Math.Max(result.Ram,Rules.Number(resources,"mpv_working_set_MiB"));
        result.Drops=Math.Max(0,Rules.Number(data,"frame-drop-count")-firstDrop);result.DecoderDrops=Math.Max(0,Rules.Number(data,"decoder-frame-drop-count")-firstDecoderDrop);result.AvSync=Math.Max(result.AvSync,Math.Abs(Rules.Number(data,"avsync")));result.Advance=Rules.Number(data,"time-pos")-firstPos;result.Elapsed=watch.Elapsed.TotalSeconds-measure;
        log("SAMPLE "+watch.Elapsed.TotalSeconds.ToString("F3",CultureInfo.InvariantCulture)+" "+Json.Serializer().Serialize(new{player=data,resources=resources}));
        if(BenchPlan.Status(test.Options.Decoder,result.Actual).StartsWith("FALLBACK")){result.Status="FALLBACK";break;}
        if(result.Elapsed>=seconds){result.Classify();break;}
       }
       if(result.Status=="FAILED")result.Status="TIMEOUT";
       if(result.Status=="OK" && result.Actual=="unknown")result.Status="UNVERIFIED_DECODER";
       if(result.Status=="OK" && BenchPlan.Status(test.Options.Decoder,result.Actual).StartsWith("FALLBACK"))result.Status="FALLBACK";
       log("DECODER "+BenchPlan.Status(test.Options.Decoder,result.Actual));
      }
     }
    }catch(OperationCanceledException){result.Status="CANCELLED";throw;}
    catch(Exception e){result.Status="FAILED: "+e.GetType().Name;log("ERROR "+Rules.Redact(e.Message));}
    finally{
     try{if(!p.HasExited){p.Kill();p.WaitForExit(2000);}}catch(Exception){}
     lock(errors){if(errors.Length>0)log("MPV STDERR "+Rules.Redact(errors.ToString()));}
     if(result.Samples>0)result.Cpu/=result.Samples;log("RESULT "+result+"\n");
    }
   }return result;
  }
 }
}
