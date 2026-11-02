using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using LowTube;
namespace LowTubeHybridCompare {
 public sealed class Measurement {
  public int Samples,WaitingSamples,HardwareSamples;public long ControlFrames;public double ControlMedia,ControlWall;public readonly Dictionary<string,double> Stages=new Dictionary<string,double>();public double First=-1,Last,FirstWall,LastWall,MaxAv,Drops;public bool Completed,Hardware;public int DecoderStarts,HardwareFrames;
  public void Read(string line,double wall){
   if(line=="COMPLETED" || line=="progress=end")Completed=true;
   if(line.StartsWith("frame=")){long n;if(Int64.TryParse(line.Substring(6),out n))ControlFrames=Math.Max(ControlFrames,n);}
   if(line.StartsWith("out_time_us=")){double n;if(Double.TryParse(line.Substring(12),NumberStyles.Any,CultureInfo.InvariantCulture,out n))ControlMedia=Math.Max(ControlMedia,n/1000000);}
   if(line.StartsWith("DRAIN_DONE ") || line.StartsWith("STAGE_")){
    foreach(string token in line.Split(' ')){var pair=token.Split('=');double n;if(pair.Length!=2 || !Double.TryParse(pair[1],NumberStyles.Any,CultureInfo.InvariantCulture,out n))continue;
     if(line.StartsWith("DRAIN_DONE ")){if(pair[0]=="media_s")ControlMedia=n;if(pair[0]=="wall_s")ControlWall=n;if(pair[0]=="frames")ControlFrames=(long)n;}
     else if(pair[0].EndsWith("_ms")){double previous;Stages.TryGetValue(pair[0],out previous);Stages[pair[0]]=previous+n;}
    }
   }
   if(line.StartsWith("DECODE_START "))DecoderStarts++;
   if(line.StartsWith("HARDWARE_FRAMES_CONFIRMED "))HardwareFrames++;
   int at=line.IndexOf("HYBRID_SAMPLE ",StringComparison.Ordinal);if(at<0)return;
   try{
    var d=Json.Object(line.Substring(at+14));object v;if(!d.TryGetValue("time-pos",out v) || v==null)return;
    double pos=Rules.Number(d,"time-pos");if(pos>60 || pos<0)return;
    if(First<0){First=pos;FirstWall=wall;}Last=pos;LastWall=wall;Samples++;MaxAv=Math.Max(MaxAv,Math.Abs(Rules.Number(d,"avsync")));Drops=Math.Max(Drops,Rules.Number(d,"frame-drop-count"));
    if(d.TryGetValue("paused-for-cache",out v) && Object.Equals(v,true))WaitingSamples++;
    if(Json.Str(d,"hwdec-current")=="d3d11va"){Hardware=true;HardwareSamples++;}
   }catch(ArgumentException){}
  }
  public string StageSummary(){return String.Join("; ",Stages.Select(x=>x.Key+"="+x.Value.ToString("F3",CultureInfo.InvariantCulture)));}
  public string ControlSummary(string name,bool ok,double wall){double duration=ControlWall>0?ControlWall:wall;return String.Format(CultureInfo.InvariantCulture,"{0}: status={1}; decoded_frames={2}; media_s={3:F3}; decode_wall_s={4:F3}; throughput={5:F3}x (no display/audio)",name,ok&&Completed&&ControlFrames>0?"COMPLETED":"FAILED_OR_INCOMPLETE",ControlFrames,ControlMedia,duration,duration>0?ControlMedia/duration:0);}
  public double Speed {get{return LastWall>FirstWall?(Last-First)/(LastWall-FirstWall):0;}}
  public string Status(bool ok,bool native,bool hardware,bool mixed){
   if(!ok || (!native && !Completed))return "FAILED";
   if(Samples<3 || Last<58.5)return "INCOMPLETE";
   if((hardware || mixed) && (native?HardwareSamples!=Samples:DecoderStarts==0 || HardwareFrames<(mixed?DecoderStarts/2:DecoderStarts)))return "UNVERIFIED_HARDWARE";
   if(Speed<0.95)return "SLOW_OR_STALLED";
   if(MaxAv>0.1)return "AV_DESYNC";
   return "COMPLETED"; // Completion is not a recommendation or a guarantee of smoothness.
  }
  public string Summary(string name,string status){return String.Format(CultureInfo.InvariantCulture,"{0}: status={1}; playback_speed={2:F3}x; measured_media={3:F3}s; measured_wall={4:F3}s; last_media_pos={5:F3}s; drops={6}; max_abs_AVsync={7:F6}s; samples={8}; waiting_samples={9}; hardware_gops_confirmed={10}; decoder_gops={11}",name,status,Speed,Last-First,LastWall-FirstWall,Last,Drops,MaxAv,Samples,WaitingSamples,HardwareFrames,DecoderStarts);}
 }
 public static class Program {
  static readonly CancellationTokenSource Stop=new CancellationTokenSource();static StreamWriter Writer;
  static void Log(string line){lock(Writer){Writer.WriteLine(DateTime.UtcNow.ToString("O")+" | "+line);Writer.Flush();}}
  [STAThread] public static void Main(string[] args){
   Application.EnableVisualStyles();string file=args.Length>0?args[0]:null;
   if(file==null)using(var d=new OpenFileDialog{Title="Automatic hybrid comparison: select one downloaded H.264 video",Filter="Video|*.mp4;*.mkv;*.mov|All files|*.*"}){if(d.ShowDialog()!=DialogResult.OK)return;file=d.FileName;}
   string dir=Path.Combine(Paths.Data,"HybridReports");Directory.CreateDirectory(dir);string report=Path.Combine(dir,"comparison_"+DateTime.Now.ToString("yyyyMMdd_HHmmss_fff")+".txt");
   using(Writer=new StreamWriter(report,false,Encoding.UTF8)){
    Console.CancelKeyPress+=delegate(object s,ConsoleCancelEventArgs e){e.Cancel=true;Stop.Cancel();};
    try{
     file=Path.GetFullPath(file);if(!File.Exists(file))throw new FileNotFoundException("Video not found.");
     Log("LowTube hybrid comparison 0.5: same local source, first 60s, fixed case order, one repetition. Native mpv CPU, native mpv D3D11VA, pipeline CPU, pipeline D3D11VA, pipeline hybrid. Additional controls: FFmpeg continuous null-output CPU/D3D11VA and pipeline drain CPU/D3D11VA/hybrid. No source transcoding or settings changes.");
     Log("METRICS: speed is media-time advance / observed wall-time between first and last samples <=60s. Includes stalls; excludes preceding probe/startup. Samples are asynchronous estimates. waiting_samples is NOT seconds. Whole-system CPU/GPU load and temperature are not measured. Native and pipeline rendering window=960x540, D3D11/bilinear, audio sync, frame dropping=vo, decoder threads=1. Pipeline startup/copy-back/color-metadata handling differs from native mpv. Later runs may benefit from OS file cache. Compare only matching sources and environments.");
     Log("STAGE METRICS: first_frame_ms includes launch/input seek/preroll; decode_and_pipe_read_ms combines decoding and stdout reads; free_buffer_wait_ms is producer backpressure; ready_wait_ms is consumer starvation; mpv_write_ms includes transport and player backpressure. Parallel stages overlap: do not add their times as total elapsed. Null controls retain hardware download/format conversion but omit raw stdout, queues, mpv and audio; drain controls retain stdout/queues/GOP launches but omit mpv/audio. Throughput controls are not playback speed.");
     Log("SOURCE bytes="+new FileInfo(file).Length);var summaries=new List<string>();
     foreach(string name in new[]{"native-software","native-d3d11va","null-software","null-hardware","drain-software","drain-hardware","drain-hybrid","pipeline-software","pipeline-hardware","pipeline-hybrid"}){
      Stop.Token.ThrowIfCancellationRequested();Console.WriteLine("Starting "+name+" (timeout 420s)...");summaries.Add(Run(file,name));Console.WriteLine(summaries.Last());
      if(name!="pipeline-hybrid")Stop.Token.WaitHandle.WaitOne(5000);
     }
     Log("SUMMARY");foreach(var row in summaries)Log(row);Log("ALL CASES PROCESSED. Failed/incomplete cases remain in summary; no automatic recommendation applied.");
    }catch(OperationCanceledException){Log("CANCELLED: partial results retained.");}catch(Exception e){Log("ERROR "+Rules.Redact(e.ToString()));Console.WriteLine(e.Message);}
    Console.WriteLine("Report: "+report+"\nPress Enter to close.");Console.ReadLine();
   }
  }
  static string Run(string file,string name){
   bool native=name.StartsWith("native-"),direct=name.StartsWith("null-"),drain=name.StartsWith("drain-"),hardware=name=="native-d3d11va"||name.EndsWith("-hardware");var data=new Measurement();var clock=Stopwatch.StartNew();string exe;List<string> args;
   if(direct){
    exe=Paths.Tool("ffmpeg");args=new List<string>{"-hide_banner","-nostdin","-v","error","-xerror","-threads","1","-hwaccel",hardware?"d3d11va":"none"};
    if(hardware)args.AddRange(new[]{"-hwaccel_output_format","d3d11"});
    args.AddRange(new[]{"-i",file,"-map","0:v:0","-an","-sn","-dn","-t","60","-vf",hardware?"hwdownload,format=nv12,format=yuv420p":"format=yuv420p","-threads","1","-fps_mode","passthrough","-progress","pipe:1","-nostats","-f","null","-"});
   }else if(native){
    exe=Paths.Tool("mpv");args=new List<string>{"--no-config","--load-scripts=no","--ytdl=no","--hwdec="+(hardware?"d3d11va":"no"),"--vo=gpu","--gpu-context=d3d11","--scale=bilinear","--dscale=bilinear","--cscale=bilinear","--deband=no","--interpolation=no","--video-sync=audio","--vd-lavc-threads=1","--framedrop=vo","--cache=no","--input-default-bindings=no","--input-vo-keyboard=no","--osc=no","--force-window=yes","--geometry=960x540","--length=60","--title=LowTube comparison - "+name,"--script="+Path.Combine(Paths.Root,"hybrid","metrics.lua"),"--terminal=yes","--msg-level=all=warn","--",file};
   }else{exe=Path.Combine(Paths.Root,"LowTube.Hybrid.exe");args=new List<string>{file,name.Substring(drain?6:9),"--batch"};if(drain)args.Add("--drain");}
   Log("BEGIN CASE "+name);bool timeout=false;int exit=-1;object gate=new object();
   Action<string> consume=line=>{if(line==null)return;lock(gate){Log(name+" | "+Rules.Redact(line));data.Read(line,clock.Elapsed.TotalSeconds);}};
   using(var job=new ProcessJob())using(var p=new Process{StartInfo=new ProcessStartInfo(exe,Rules.Arguments(args)){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true}}){
    p.OutputDataReceived+=delegate(object s,DataReceivedEventArgs e){consume(e.Data);};p.ErrorDataReceived+=delegate(object s,DataReceivedEventArgs e){consume(e.Data);};
    try{
     p.Start();job.Attach(p);p.BeginOutputReadLine();p.BeginErrorReadLine();
     while(!p.WaitForExit(200)){Stop.Token.ThrowIfCancellationRequested();if(clock.Elapsed.TotalSeconds>420){timeout=true;break;}}
     if(timeout){job.Dispose();p.WaitForExit(5000);}else{p.WaitForExit();exit=p.ExitCode;}
    }catch(OperationCanceledException){throw;}catch(Exception e){consume("CASE ERROR: "+Rules.Redact(e.Message));}
    finally{job.Dispose();try{p.WaitForExit(5000);}catch{}}
   }
   lock(gate){string status=timeout?"WATCHDOG_TIMEOUT":data.Status(exit==0,native,hardware,name=="pipeline-hybrid");string summary=direct||drain?data.ControlSummary(name,!timeout && exit==0 && (!drain || (!hardware && name!="drain-hybrid") || (data.DecoderStarts>0 && data.HardwareFrames==(name=="drain-hybrid"?data.DecoderStarts/2:data.DecoderStarts))),clock.Elapsed.TotalSeconds):data.Summary(name,status);if(data.Stages.Count>0)Log("STAGE_TOTALS "+name+" "+data.StageSummary());Log("END CASE total_wall_s="+clock.Elapsed.TotalSeconds.ToString("F3",CultureInfo.InvariantCulture)+" exit="+exit+" "+summary);return summary;}
  }
 }
}
