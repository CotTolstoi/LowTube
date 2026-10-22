using System;
using System.Collections.Generic;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using LowTube;

// Experimental copy-back pipeline. Each GOP has its own decoder context.
// Accurate input seeking preserves decoder preroll (including open-GOP references).
namespace LowTubeHybrid {
 public sealed class Segment { public int First, Count; public double Start; }
 // Fixed frame pools keep memory independent of GOP duration.
 public sealed class FrameQueue : IDisposable {
  public readonly BlockingCollection<byte[]> Free=new BlockingCollection<byte[]>();
  public BlockingCollection<byte[]> Ready=new BlockingCollection<byte[]>();
  public FrameQueue(int count,int bytes){for(int i=0;i<count;i++)Free.Add(new byte[bytes]);}
  public void Dispose(){Free.Dispose();Ready.Dispose();}
 }
 public static class ResourceValue {
  public static T Read<T>(Func<T> read,T previous) where T:IComparable<T>{
   try{T value=read();return value.CompareTo(previous)>0?value:previous;}
   catch(InvalidOperationException){return previous;}
   catch(System.ComponentModel.Win32Exception){return previous;}
   catch(NotSupportedException){return previous;}
  }
 }
 public static class Program {
  static readonly CultureInfo CI=CultureInfo.InvariantCulture;
  static readonly CancellationTokenSource Stop=new CancellationTokenSource();
  static StreamWriter Report;
  static void Log(string s){lock(Report){Report.WriteLine(DateTime.UtcNow.ToString("O")+" | "+s);Report.Flush();}Console.WriteLine(s);}
  static string N(double v){return v.ToString("0.#########",CI);}
  [STAThread] public static void Main(string[] args){
   Application.EnableVisualStyles();string file=args.Length>0?args[0]:null;
   if(file==null)using(var d=new OpenFileDialog{Title="Hybrid experiment: select a downloaded H.264 video",Filter="Video|*.mp4;*.mkv;*.mov|All files|*.*"}){if(d.ShowDialog()!=DialogResult.OK)return;file=d.FileName;}
   string mode=args.Length>1?args[1]:"hybrid";
   if(!new[]{"hybrid","software","hardware"}.Contains(mode)){MessageBox.Show("Mode: hybrid, software or hardware");return;}
   string folder=Path.Combine(Paths.Data,"HybridReports");Directory.CreateDirectory(folder);
   string path=Path.Combine(folder,"hybrid_"+DateTime.Now.ToString("yyyyMMdd_HHmmss_fff")+".txt");
   using(Report=new StreamWriter(path,false,Encoding.UTF8)){
    Console.CancelKeyPress+=delegate(object s,ConsoleCancelEventArgs e){e.Cancel=true;Stop.Cancel();};
    Log("LowTube hybrid prototype 0.5; mode="+mode+"; drain="+args.Contains("--drain")+"; first 60 seconds; two bounded frame queues; payload <=64 MiB; no settings modified.");
    Log("Copy-back via FFmpeg stdout, ordered YUV420 frames, mpv video/audio presentation. Per-GOP process startup is included. This is NOT zero-copy and NOT a steady-state decoder benchmark.");
    try{Run(Path.GetFullPath(file),mode,args.Contains("--drain")).GetAwaiter().GetResult();Log("COMPLETED");}
    catch(Exception e){Stop.Cancel();Log("FAILED: "+Rules.Redact(e.ToString()));Environment.ExitCode=1;}
    Console.WriteLine("Report: "+path);if(!args.Contains("--batch")){Console.WriteLine("Press Enter to close.");Console.ReadLine();}
   }
  }
  static Process Launch(string tool,IEnumerable<string> args,ProcessJob job,bool input,Action<string> errors){
   var p=new Process{StartInfo=new ProcessStartInfo(Paths.Tool(tool),Rules.Arguments(args)){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,RedirectStandardInput=input}};
   p.ErrorDataReceived+=delegate(object s,DataReceivedEventArgs e){if(e.Data!=null)errors(e.Data);};
   try{p.Start();job.Attach(p);p.BeginErrorReadLine();return p;}catch{try{if(!p.HasExited)p.Kill();}catch{}p.Dispose();throw;}
  }
  static async Task<string> Probe(string file){
   using(var job=new ProcessJob())using(var p=Launch("ffprobe",new[]{"-v","error","-select_streams","v:0","-read_intervals","%+61","-show_frames","-show_streams","-show_entries","stream=codec_name,width,height,pix_fmt,avg_frame_rate,color_transfer,sample_aspect_ratio:frame=key_frame,best_effort_timestamp_time","-of","json",file},job,false,Log))
   using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(Stop.Token)){
    timeout.CancelAfter(TimeSpan.FromMinutes(3));using(timeout.Token.Register(delegate{try{p.Kill();}catch{}})){
     var reading=p.StandardOutput.ReadToEndAsync();int elapsed=0;while(await Task.WhenAny(reading,Task.Delay(10000))!=reading){elapsed+=10;Log("PROBE still scanning: "+elapsed+" seconds");}
     string text=await reading;await Task.Run(()=>p.WaitForExit());timeout.Token.ThrowIfCancellationRequested();if(p.ExitCode!=0)throw new IOException("ffprobe failed");return text;
    }
   }
  }
  public static List<Segment> Plan(double[] timestamps,bool[] keys,double fps){
   if(timestamps.Length==0 || !keys[0] || Math.Abs(timestamps[0])>0.002)throw new InvalidDataException("Requires a zero-start video beginning at a keyframe.");
   var result=new List<Segment>();
   for(int i=0;i<timestamps.Length && timestamps[i]<60-0.00001;i++){
    if(Math.Abs(timestamps[i]-i/fps)>0.002)throw new InvalidDataException("Variable frame rate or timestamp discontinuity: unsupported in prototype.");
    if(i==0 || keys[i])result.Add(new Segment{First=i,Start=timestamps[i]});result[result.Count-1].Count++;
   }
   return result;
  }
  static async Task Run(string file,string mode,bool drain){
   if(!File.Exists(file))throw new FileNotFoundException("Select an existing local video.");
   Log("PROBE started: scanning first 61 seconds (timeout 180s).");
   var probeClock=Stopwatch.StartNew();
   var root=Json.Object(await Probe(file));Log("PROBE completed in "+N(probeClock.Elapsed.TotalSeconds)+" seconds.");var info=Json.Items(root,"streams").First();
   if(Json.Str(info,"codec_name")!="h264" || Json.Str(info,"pix_fmt")!="yuv420p")throw new InvalidDataException("This prototype accepts H.264 8-bit YUV420 only.");
   string transfer=Json.Str(info,"color_transfer"),sar=Json.Str(info,"sample_aspect_ratio");
   if(transfer=="smpte2084" || transfer=="arib-std-b67" || (sar!="" && sar!="N/A" && sar!="1:1"))throw new InvalidDataException("HDR and non-square pixels are not supported in this prototype.");
   int width=(int)Rules.Number(info,"width"),height=(int)Rules.Number(info,"height");
   if(width<2 || height<2 || width%2!=0 || height%2!=0 || width>3840 || height>2160)throw new InvalidDataException("Unsupported dimensions.");
   string rate=Json.Str(info,"avg_frame_rate");var ratio=rate.Split('/');double fps=Double.Parse(ratio[0],CI)/Double.Parse(ratio[1],CI);
   if(Double.IsNaN(fps)||Double.IsInfinity(fps)||fps<1||fps>120)throw new InvalidDataException("Invalid frame rate.");
   var frames=Json.Items(root,"frames").ToArray();var plan=Plan(frames.Select(x=>Rules.Number(x,"best_effort_timestamp_time")).ToArray(),frames.Select(x=>Rules.Number(x,"key_frame")==1).ToArray(),fps);
   int frameBytes=checked(width*height*3/2),capacity=Math.Max(2,Math.Min(8,32*1024*1024/frameBytes));
   Log("SOURCE "+width+"x"+height+" fps="+rate+" GOPs="+plan.Count+" longest_gop_frames="+plan.Max(x=>x.Count)+" queue_frames_each="+capacity+" frame_payload_bytes="+(2L*capacity*frameBytes));
   var tasks=new Task[2];var queues=new[]{new FrameQueue(capacity,frameBytes),new FrameQueue(capacity,frameBytes)};var clock=Stopwatch.StartNew();
   using(var children=new ProcessJob()){
    Process player=null;
    try{
     for(int i=0;i<Math.Min(2,plan.Count);i++){int index=i;tasks[i]=Decode(file,plan[i],index,mode,frameBytes,queues[i],children);}
     byte[] first=await Take(queues[0],tasks[0]); // Start playback as soon as the first decoded frame arrives.
     var options=new[]{"--no-config","--load-scripts=no","--ytdl=no","--hwdec=no","--vo=gpu","--gpu-context=d3d11","--scale=bilinear","--dscale=bilinear","--cscale=bilinear","--deband=no","--interpolation=no","--video-sync=audio","--framedrop=vo","--cache=no","--demuxer-max-bytes=8MiB","--demuxer-readahead-secs=0","--input-default-bindings=no","--input-vo-keyboard=no","--osc=no","--force-window=yes","--geometry=960x540","--title=LowTube hybrid experiment - close window to stop","--audio-file="+file,"--audio-file-auto=no","--length=60","--script="+Path.Combine(Paths.Root,"hybrid","metrics.lua"),"--terminal=yes","--msg-level=all=warn,hybrid_metrics=info","--","-"};
     if(!drain){var launch=Stopwatch.StartNew();player=Launch("mpv",options,children,true,s=>Log("MPV "+s));Log("STAGE_MPV_LAUNCH wall_ms="+N(launch.Elapsed.TotalMilliseconds));
     player.OutputDataReceived+=delegate(object s,DataReceivedEventArgs e){if(e.Data!=null)Log("MPV "+e.Data);};player.BeginOutputReadLine();}
     using(var cancel=Stop.Token.Register(delegate{try{if(player!=null)player.Kill();}catch{}})){
      var stream=drain?Stream.Null:player.StandardInput.BaseStream;byte[] header=Encoding.ASCII.GetBytes("YUV4MPEG2 W"+width+" H"+height+" F"+rate.Replace('/',':')+" Ip A1:1 C420jpeg\n"),marker=Encoding.ASCII.GetBytes("FRAME\n");
      await stream.WriteAsync(header,0,header.Length,Stop.Token);
      for(int i=0;i<plan.Count;i++){
       Log("PRESENT gop="+i+" frames="+plan[i].Count+" elapsed="+N(clock.Elapsed.TotalSeconds));
       double waited=0,writeMs=0;
       for(int f=0;f<plan[i].Count;f++){
        var wait=Stopwatch.StartNew();byte[] data=(i==0 && f==0)?first:await Take(queues[i%2],tasks[i%2]);first=null;waited+=wait.Elapsed.TotalMilliseconds;
        var writing=Stopwatch.StartNew();
        try{if(!drain){await stream.WriteAsync(marker,0,marker.Length,Stop.Token);await stream.WriteAsync(data,0,frameBytes,Stop.Token);}}
        finally{writeMs+=writing.Elapsed.TotalMilliseconds;queues[i%2].Free.Add(data);}
       }
       await tasks[i%2];tasks[i%2]=null;
       Log("QUEUE gop="+i+" total_frame_wait_ms="+N(waited));
       Log("STAGE_CONSUMER gop="+i+" ready_wait_ms="+N(waited)+" mpv_write_ms="+N(writeMs)+" drain="+drain);
       if(i+2<plan.Count){int next=i+2;queues[i%2].Ready.Dispose();queues[i%2].Ready=new BlockingCollection<byte[]>();tasks[i%2]=Decode(file,plan[next],next,mode,frameBytes,queues[i%2],children);}

      }
      if(drain)Log("DRAIN_DONE frames="+plan.Sum(x=>x.Count)+" media_s="+N(plan.Sum(x=>x.Count)/fps)+" wall_s="+N(clock.Elapsed.TotalSeconds));
      else{player.StandardInput.Close();
      await Task.Run(()=>{if(!player.WaitForExit(30000))throw new TimeoutException("mpv did not finish within 30 seconds after EOF.");});
      if(player.ExitCode!=0)throw new IOException("mpv exit="+player.ExitCode);}
     }
    }finally{
     // Kill all producers before disposing the log; observe pending task errors.
     Stop.Cancel();children.Dispose();foreach(var t in tasks)if(t!=null)try{t.Wait(5000);}catch{}
     if(player!=null){try{player.WaitForExit(3000);}catch{}player.Dispose();}
     foreach(var q in queues)q.Dispose();
    }
   }
  }
  // Exited process counters may throw on Windows; diagnostics must never stop playback.
  static void ReadResources(Process process,ref double cpu,ref long peak){
   try{process.Refresh();}catch(InvalidOperationException){}catch(System.ComponentModel.Win32Exception){}
   cpu=ResourceValue.Read(()=>process.TotalProcessorTime.TotalSeconds,cpu);
   peak=ResourceValue.Read(()=>process.PeakWorkingSet64,peak);
  }
  static async Task<byte[]> Take(FrameQueue queue,Task producer){
   byte[] data=await Task.Run(()=>{byte[] frame;while(true){Stop.Token.ThrowIfCancellationRequested();if(queue.Ready.TryTake(out frame,100,Stop.Token))return frame;if(producer.IsCompleted)return null;}});
   if(data==null){await producer;throw new IOException("Decoder ended without expected frames.");}return data;
  }
  static async Task Decode(string file,Segment segment,int index,string mode,int frameBytes,FrameQueue queue,ProcessJob job){
   bool hardware=mode=="hardware" || (mode=="hybrid" && index%2==1);
   var args=new List<string>{"-hide_banner","-nostdin","-v","error","-xerror","-threads","1"};
   if(hardware)args.AddRange(new[]{"-hwaccel","d3d11va","-hwaccel_output_format","d3d11"});else args.AddRange(new[]{"-hwaccel","none"});
   args.AddRange(new[]{"-ss",N(segment.Start),"-accurate_seek","-i",file,"-map","0:v:0","-an","-sn","-dn","-vf",hardware?"hwdownload,format=nv12,format=yuv420p":"format=yuv420p","-frames:v",segment.Count.ToString(CI),"-fps_mode","passthrough","-threads","1","-f","rawvideo","-pix_fmt","yuv420p","pipe:1"});
   var errors=new StringBuilder();var clock=Stopwatch.StartNew();
   using(var p=Launch("ffmpeg",args,job,false,s=>{lock(errors){if(errors.Length<12000)errors.AppendLine(s);}}))
   using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(Stop.Token)){
    using(timeout.Token.Register(delegate{try{p.Kill();}catch{}})){
     long count=0;int delivered=0;double cpuSeconds=0;long peakBytes=0;double nextMetrics=0,launchMs=clock.Elapsed.TotalMilliseconds,freeMs=0,readMs=0,statsMs=0,firstMs=0,exitMs=0;
     try{
      Log("DECODE_START gop="+index+" requested="+(hardware?"d3d11va":"software")+" frames="+segment.Count);
      for(int f=0;f<segment.Count;f++){
       // Consumer backpressure must not consume the decoder-stall timeout.
       var stage=Stopwatch.StartNew();byte[] data=await Task.Run(()=>queue.Free.Take(Stop.Token));freeMs+=stage.Elapsed.TotalMilliseconds;int have=0;stage.Restart();
       timeout.CancelAfter(TimeSpan.FromSeconds(60));
       while(have<frameBytes){int n=await p.StandardOutput.BaseStream.ReadAsync(data,have,frameBytes-have,timeout.Token);if(n==0)break;have+=n;}
       readMs+=stage.Elapsed.TotalMilliseconds;if(f==0)firstMs=clock.Elapsed.TotalMilliseconds;
       timeout.CancelAfter(Timeout.Infinite);timeout.Token.ThrowIfCancellationRequested();
       if(have!=frameBytes)throw new IOException("GOP "+index+" ended before frame "+f+". "+errors);
       if(clock.Elapsed.TotalSeconds>=nextMetrics){stage.Restart();ReadResources(p,ref cpuSeconds,ref peakBytes);statsMs+=stage.Elapsed.TotalMilliseconds;nextMetrics=clock.Elapsed.TotalSeconds+1;}
       if(hardware && f==0)Log("HARDWARE_FRAMES_CONFIRMED gop="+index+" first_frame via mandatory hwdownload");
       count+=have;queue.Ready.Add(data,Stop.Token);delivered++;
      }
      timeout.CancelAfter(TimeSpan.FromSeconds(60));
      var ending=Stopwatch.StartNew();int extra=await Task.Run(()=>p.StandardOutput.BaseStream.ReadByte());await Task.Run(()=>p.WaitForExit());timeout.Token.ThrowIfCancellationRequested();exitMs=ending.Elapsed.TotalMilliseconds;
      if(p.ExitCode!=0 || extra!=-1)throw new IOException("GOP "+index+" decode failed/incorrect frame count. No silent fallback. "+errors);
      Log("STAGE_DECODER gop="+index+" launch_ms="+N(launchMs)+" first_frame_ms="+N(firstMs)+" free_buffer_wait_ms="+N(freeMs)+" decode_and_pipe_read_ms="+N(readMs)+" resource_stats_ms="+N(statsMs)+" eof_exit_wait_ms="+N(exitMs));
      ReadResources(p,ref cpuSeconds,ref peakBytes);
      Log("DECODE gop="+index+" requested="+(hardware?"d3d11va":"software")+" frames="+delivered+" bytes="+count+" wall_s_including_backpressure="+N(clock.Elapsed.TotalSeconds)+" cpu_s="+N(cpuSeconds)+" peak_working_set_observed="+peakBytes+" resource_sampling=best_effort");

     }finally{queue.Ready.CompleteAdding();try{if(!p.HasExited)p.Kill();}catch{}}
    }

   }
  }
 }
}
