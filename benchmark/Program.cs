using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace LowTube {
 public static class BenchmarkProgram {
  static CancellationTokenSource cancellation=new CancellationTokenSource();
  [STAThread]public static void Main(string[] args){
   if(args.Length==2 && args[0]=="--worker"){
    try{var job=Json.Load<BenchWorkerInput>(args[1],null);if(job==null)throw new InvalidDataException("Missing worker input");
     var result=BenchRunner.RunLocal(new BenchCase(job.Test.Name,job.Test.Options),job.File,job.Seconds,Console.WriteLine,CancellationToken.None).GetAwaiter().GetResult();Json.Save(job.Output,result);
    }catch(Exception e){Console.Error.WriteLine(Rules.Redact(e.Message));Environment.ExitCode=1;}return;
   }
   if(typeof(Player).Assembly.GetName().Version<new Version(0,9,1,0)){MessageBox.Show("Install the LowTube 0.9.1 player-menu patch first.","LowTube benchmark");return;}
   Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
   string[] urls;int seconds;bool resume;string legacy="";
   using(var form=new Form{Text="LowTube benchmark 1.2 — 480p+",Size=new Size(630,415),StartPosition=FormStartPosition.CenterScreen,Font=new Font("Segoe UI",10),MinimizeBox=false,MaximizeBox=false,FormBorderStyle=FormBorderStyle.FixedDialog}){
    var label=new Label{Text="Video URL (one per line for multiple videos):",Left=18,Top=15,Width=570};
    var input=new TextBox{Left=18,Top=44,Width=575,Height=90,Multiline=true,ScrollBars=ScrollBars.Vertical};
    var duration=new NumericUpDown{Left=225,Top=148,Width=90,Minimum=10,Maximum=120,Value=10};
    var info=new Label{Left=18,Top=182,Width=575,Height=65,Text="Automatic (480p and above): download clips → decoder tests → advanced settings → reports.\nMay take hours. Close LowTube and other busy apps; keep AC power connected.\nPress Ctrl+C in the console to stop and retain partial results."};
    var restore=new CheckBox{Text="Resume saved progress (same video and duration)",Left=18,Top=250,Width=570,Checked=true};
    var import=new Button{Text="Import completed groups from old report…",Left=18,Top=280,Width=390,Height=30};
    import.Click+=delegate{using(var dialog=new OpenFileDialog{Filter="Benchmark report (*.txt)|*.txt"})if(dialog.ShowDialog(form)==DialogResult.OK){legacy=dialog.FileName;import.Text=Path.GetFileName(legacy);}};
    var start=new Button{Text="Start automatic test",Left=355,Top=327,Width=238,Height=35,DialogResult=DialogResult.None};
    start.Click+=delegate{try{var parsed=input.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Select(x=>Rules.YouTubeUrl(x.Trim())).Distinct().ToArray();if(parsed.Length==0)throw new ArgumentException("Enter a YouTube video URL.");if(parsed.Any(x=>ContentFeeds.IsPlaylist(x)))throw new ArgumentException("Use individual video URLs, not playlists.");form.DialogResult=DialogResult.OK;}catch(Exception e){MessageBox.Show(form,e.Message,"URL",MessageBoxButtons.OK,MessageBoxIcon.Information);}};
    form.Controls.AddRange(new Control[]{label,input,new Label{Text="Seconds per measured case:",Left=18,Top=150,Width=205},duration,info,restore,import,start});form.AcceptButton=start;
    if(form.ShowDialog()!=DialogResult.OK)return;urls=input.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Select(x=>Rules.YouTubeUrl(x.Trim())).Distinct().ToArray();seconds=(int)duration.Value;resume=restore.Checked;
   }
   Console.CancelKeyPress+=delegate(object sender,ConsoleCancelEventArgs e){e.Cancel=true;cancellation.Cancel();Console.WriteLine("Stopping; saving partial results...");};
   try{RunAll(urls,seconds,resume,legacy,cancellation.Token).GetAwaiter().GetResult();}catch(OperationCanceledException){Console.WriteLine("Stopped. Partial reports retained.");}catch(Exception e){Console.WriteLine("ERROR: "+Rules.Redact(e.Message));}
   Console.WriteLine("Finished. Reports: "+Path.Combine(Paths.Data,"Benchmarks"));Console.WriteLine("Press Enter to close.");Console.ReadLine();
  }
  static async Task<BenchResult> RunSaved(BenchCheckpoint state,string path,string format,BenchCase test,string clip,int seconds,Action<string> log,CancellationToken ct){
   string key=format+"::"+test.Name+"::"+BenchCheckpoint.Key(Json.Serializer().Serialize(test.Options));BenchResult existing=state.Find(key);
   if(existing!=null){log("REUSED CASE "+existing);return existing;}
   state.Pending=key;Json.Save(path,state);
   BenchResult result;try{result=await BenchRunner.Run(test,clip,seconds,log,ct);}catch(OperationCanceledException){state.Pending="";Json.Save(path,state);throw;}
   state.Put(key,result);state.Pending="";Json.Save(path,state);await Task.Delay(1000,ct);return result;
  }
  static async Task RunAll(string[] urls,int seconds,bool resume,string legacy,CancellationToken ct){
   var settings=Json.Load(Path.Combine(Paths.Data,"settings.json"),new Settings());
   string directory=Path.Combine(Paths.Data,"Benchmarks");Directory.CreateDirectory(directory);
   foreach(string url in urls){
    ct.ThrowIfCancellationRequested();string id=BenchCheckpoint.VideoId(url);
    string checkpointPath=Path.Combine(directory,"progress_"+BenchCheckpoint.Key(id+"|"+seconds)+".json");
    string runtimeKey=BenchCheckpoint.Runtime();
    var state=resume?Json.Load<BenchCheckpoint>(checkpointPath,null):null;
    if(state==null || state.Version!=12 || state.Seconds!=seconds || state.Url!=id || state.RuntimeKey!=runtimeKey)state=new BenchCheckpoint{Url=id,Seconds=seconds,RuntimeKey=runtimeKey,Baseline=BenchCheckpoint.Clean(settings),Report=Path.Combine(directory,id+"_"+DateTime.Now.ToString("yyyyMMdd_HHmmss_fff")+".txt")};
    if(legacy.Length>0){try{state.ImportLegacy(legacy);}catch(Exception e){Console.WriteLine("Legacy import skipped: "+e.Message);}}
    string report=state.Report;Json.Save(checkpointPath,state);
    string work=Path.Combine(Path.GetTempPath(),"LowTube-benchmark-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(work);
    using(var writer=new StreamWriter(report,true,new UTF8Encoding(true))){writer.AutoFlush=true;Action<string> log=s=>{lock(writer)writer.WriteLine(DateTime.UtcNow.ToString("O")+" | "+s);};
     try{
      Console.WriteLine("Report: "+report);
      if(state.Pending.Length>0){log("INTERRUPTED CASE SKIPPED ON RESUME: "+state.Pending);state.Put(state.Pending,new BenchResult{Name=state.Pending,Status="INTERRUPTED"});state.Pending="";Json.Save(checkpointPath,state);}
      log("RESUME: "+state.Results.Count+" saved cases; "+state.CompletedFormats.Count+" completed groups. Legacy groups are skipped, not re-ranked with old OK labels.");log("LowTube benchmark 1.2; minimum quality=480p; URL="+url+"; LowTube assembly="+typeof(Player).Assembly.GetName().Version);
      log("METHODOLOGY: same downloaded clip per format; stream-copy, no transcoding. 960x540 target window, 1x speed, EQ disabled. Warm-up 3s, measurement "+seconds+"s. Each case is a fresh process. Advanced sweep changes one setting at a time on best valid base case, not an exhaustive Cartesian product. CPU%=share of all logical CPUs. Ready time is observed IPC readiness, NOT measured first screen frame. Ranking is provisional, one repetition. Temperature/power=N/A. Background apps can affect results. No settings are changed.");
      log(BenchMetrics.Hardware());
      foreach(string tool in new[]{"mpv","ffmpeg","yt-dlp"}){var version=await Runner.Run(Paths.Tool(tool),new[]{tool=="ffmpeg"?"-version":"--version"},ct,20,true,null);log("VERSION "+tool+": "+version.Output.Split('\n').FirstOrDefault());}
      Console.WriteLine("Reading available formats...");var root=await new Backend(settings).Resolve(url,false,ct,null,true);
      log("TITLE "+Json.Str(root,"title"));double duration=Rules.Number(root,"duration");string live=Json.Str(root,"live_status");
      if(live=="is_live" || live=="is_upcoming" || duration<seconds+12)throw new InvalidOperationException("Use a finished video at least "+(seconds+12)+" seconds long; live streams are not comparable in this test.");
      var all=Json.Items(root,"formats").ToList();
      foreach(var f in all)log("FORMAT "+Json.Serializer().Serialize(new{id=Json.Str(f,"format_id"),codec=Json.Str(f,"vcodec"),audio=Json.Str(f,"acodec"),width=Json.Str(f,"width"),height=Json.Str(f,"height"),fps=Json.Str(f,"fps"),range=Json.Str(f,"dynamic_range"),bitrate=Json.Str(f,"tbr"),protocol=Json.Str(f,"protocol"),drm=BenchPlan.Get(f,"has_drm")}));
      foreach(var f in all.Where(BenchPlan.VideoFormat).Where(f=>!BenchPlan.EligibleFormat(f)))log("SKIP FORMAT "+Json.Str(f,"format_id")+": below minimum quality 480p");
      var formats=all.Where(BenchPlan.EligibleFormat).GroupBy(BenchPlan.FormatKey).Select(g=>g.OrderByDescending(f=>Rules.Number(f,"tbr")).First()).OrderBy(f=>Rules.Number(f,"height")).ThenBy(f=>Rules.Number(f,"fps")).ToList();
      var audio=all.Where(f=>Json.Str(f,"vcodec")=="none" && Json.Str(f,"acodec")!="none" && Json.Str(f,"url")!="" && !Object.Equals(BenchPlan.Get(f,"has_drm"),true)).OrderByDescending(f=>Json.Str(f,"ext")=="m4a").ThenByDescending(f=>Rules.Number(f,"abr")).FirstOrDefault();
      var basic=BenchPlan.Base(state.Baseline);log("PLAN: "+formats.Count+" distinct codec/resolution/FPS/range/audio-layout groups; "+basic.Count+" base cases each; up to 25 advanced cases per group. All advertised formats listed above; duplicate transport/bitrate variants represented by highest bitrate. Formats below 480p and missing/DRM/image formats are not tested.");
      if(formats.Count==0)throw new InvalidOperationException("No usable video formats at 480p or higher.");
      Console.WriteLine("Formats: "+formats.Count+". Estimated playback time up to "+Math.Ceiling(formats.Count*(basic.Count+25)*(seconds+4)/3600.0)+" hours, plus downloads/timeouts.");
      double start=Math.Min(60,Math.Max(0,(duration-seconds-12)/3));
      for(int index=0;index<formats.Count;index++){
       ct.ThrowIfCancellationRequested();var f=formats[index];string formatKey=BenchPlan.FormatKey(f);if(state.CompletedFormats.Contains(formatKey)){log("SKIP COMPLETED FORMAT "+formatKey);continue;}string fid=Json.Str(f,"format_id"),selector=fid;
       if(!Regex.IsMatch(fid,@"^[A-Za-z0-9_.-]+$")){log("SKIP unsupported format identifier");continue;}
       if(Json.Str(f,"acodec")=="none"){if(audio==null){log("SKIP "+fid+": no audio stream for consistent A/V test");continue;}string aid=Json.Str(audio,"format_id");if(!Regex.IsMatch(aid,@"^[A-Za-z0-9_.-]+$"))continue;selector+="+"+aid;}
       string clip=Path.Combine(work,"clip.mkv");Console.WriteLine("FORMAT "+(index+1)+"/"+formats.Count+": "+fid+" "+BenchPlan.FormatKey(f));log("BEGIN FORMAT "+fid+" "+BenchPlan.FormatKey(f)+" selected="+selector+" requested segment="+start+".."+(start+seconds+12));
       try{
        var downloadClock=Stopwatch.StartNew();await BenchRunner.Download(settings,url,selector,clip,start,start+seconds+12,ct);log("CLIP DOWNLOAD seconds="+downloadClock.Elapsed.TotalSeconds.ToString("F3",CultureInfo.InvariantCulture)+" bytes="+new FileInfo(clip).Length+" (network preparation, not decoding performance)");
        var probe=await Runner.Run(Paths.Tool("ffprobe"),new[]{"-v","error","-show_entries","stream=codec_name,profile,width,height,pix_fmt,r_frame_rate,avg_frame_rate,color_transfer:format=duration,size","-of","json",clip},ct,20,true,null);
        if(probe.Code!=0)throw new InvalidOperationException("ffprobe could not validate the downloaded clip.");log("LOCAL MEDIA "+probe.Output);
        BenchCase best=null;BenchResult bestResult=null;var summary=new List<BenchResult>();
        foreach(var test in basic){ct.ThrowIfCancellationRequested();Console.WriteLine("  Base: "+test.Name);var r=await RunSaved(state,checkpointPath,formatKey,test,clip,seconds,log,ct);summary.Add(r);if(new[]{"OK","FRAME_DROPS","AV_DESYNC","SLOW_OR_STALLED"}.Contains(r.Status) && r.Samples>=3 && (bestResult==null || r.Score<bestResult.Score)){best=test;bestResult=r;}}
        if(best!=null){log("ADVANCED BASE "+best.Name+"; status="+bestResult.Status+" (a degraded base is not a recommendation)");foreach(var test in BenchPlan.Advanced(best.Options)){ct.ThrowIfCancellationRequested();Console.WriteLine("  Advanced: "+test.Name);summary.Add(await RunSaved(state,checkpointPath,formatKey,test,clip,seconds,log,ct));}}
        else log("ADVANCED SKIPPED: no valid base playback for this format.");
        log("FORMAT SUMMARY "+fid);foreach(var r in summary.OrderBy(x=>x.Valid?0:1).ThenBy(x=>x.Score))log(r.ToString());log("No automatic recommendation is applied; OK requires media speed 95–110%, A/V error <=100 ms, dropped frames <=1%. Drop counters are estimates and may overlap.");
        state.CompletedFormats.Add(formatKey);Json.Save(checkpointPath,state);
       }catch(OperationCanceledException){throw;}catch(Exception e){log("FORMAT FAILED "+fid+": "+Rules.Redact(e.Message));Console.WriteLine("  Failed; continuing to next format.");}
       finally{foreach(string file in Directory.GetFiles(work))try{File.Delete(file);}catch(IOException){}}
      }
      log("COMPLETED: all planned format groups processed.");
     }catch(OperationCanceledException){log("CANCELLED: partial results retained.");throw;}
     catch(Exception e){log("VIDEO FAILED: "+Rules.Redact(e.Message));Console.WriteLine("Video failed: "+Rules.Redact(e.Message));}
     finally{try{Directory.Delete(work,true);}catch(Exception){}log("REPORT CLOSED");}
    }
   }
  }
 }
}
