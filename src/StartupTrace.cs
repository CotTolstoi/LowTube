using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Threading;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Linq;
namespace LowTube {
    public static class StartupTrace {
        static readonly object gate=new object();
        static readonly List<string> lines=new List<string>();
        static DateTime processStart;
        static int nextAttempt;
        static bool pendingSave;
        static readonly Timer saver=new Timer(delegate{lock(gate)pendingSave=false;Save();},null,Timeout.Infinite,Timeout.Infinite);
        public sealed class Attempt {
            readonly int id=Interlocked.Increment(ref nextAttempt);
            readonly Stopwatch clock=Stopwatch.StartNew();
            public void Event(string phase){Mark("attempt #"+id+" | +"+clock.Elapsed.TotalSeconds.ToString("0.000",CultureInfo.InvariantCulture)+" s | "+phase);}
            public void Failure(Exception e){Event(e is OperationCanceledException?"CANCELLED":e is TimeoutException?"TIMEOUT":"FAILED: "+e.GetType().Name);}
        }
        // Only processes in this extractor's Windows job are inspected; no global
        // process scan, WMI, command lines or paths. Polling can miss very short jobs.
        public sealed class ProcessMonitor : IDisposable {
            [StructLayout(LayoutKind.Sequential)] struct Memory {
                public uint Size,PageFaults;
                public UIntPtr PeakWorkingSet,WorkingSet,PeakPagedPool,PagedPool,PeakNonPagedPool,NonPagedPool,Pagefile,PeakPagefile;
            }
            [DllImport("kernel32.dll")] static extern bool GetProcessTimes(IntPtr process,out long created,out long exited,out long kernel,out long user);
            [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process,out uint code);
            [DllImport("psapi.dll")] static extern bool GetProcessMemoryInfo(IntPtr process,ref Memory counters,uint size);
            sealed class Watched {
                public Process Process;public IntPtr Handle;public string Label;public int Id;
                public double Cpu,PreviousCpu,PreviousWall,PeakMiB,Lifetime;public bool Finished,HasBaseline;
            }
            readonly Attempt trace;readonly ProcessJob job;readonly int root;
            readonly CancellationTokenSource cancel=new CancellationTokenSource();
            readonly Dictionary<int,Watched> watched=new Dictionary<int,Watched>();
            readonly Stopwatch clock=Stopwatch.StartNew();readonly Task loop;
            bool enumerationWarning;int jsSeen;
            public ProcessMonitor(ProcessJob owner,int rootId,Attempt attempt){
                job=owner;root=rootId;trace=attempt;
                if(trace==null || Environment.OSVersion.Platform!=PlatformID.Win32NT){loop=Task.FromResult(0);return;}
                trace.Event("process sampling enabled: own extractor job only; poll=500 ms; report=2 s; CPU=100% means one logical core; logical CPUs="+Environment.ProcessorCount);
                loop=Task.Run(async delegate{
                    try{
                        double nextReport=2;
                        while(!cancel.IsCancellationRequested){
                            bool report=clock.Elapsed.TotalSeconds>=nextReport;if(report)nextReport=clock.Elapsed.TotalSeconds+2;
                            Sample(report);await Task.Delay(500,cancel.Token).ConfigureAwait(false);
                        }
                    }catch(OperationCanceledException){}catch(Exception e){trace.Event("process sampling unavailable: "+e.GetType().Name);}
                    finally{
                        try{Sample(true);foreach(var w in watched.Values)if(!w.Finished)Summary(w,"monitor ended; process still active or exit unobserved");trace.Event("process sampling finished; JavaScript processes observed="+jsSeen+" (sub-500 ms processes can be missed)");}
                        catch(Exception e){trace.Event("process sampling finalization unavailable: "+e.GetType().Name);}
                        foreach(var w in watched.Values)w.Process.Dispose();
                    }
                });
            }
            public static double CorePercent(double cpuSeconds,double elapsedSeconds){return elapsedSeconds>0?Math.Max(0,cpuSeconds)/elapsedSeconds*100:0;}
            public static string Label(string name,bool isRoot){
                if(isRoot)return "yt-dlp";
                if(String.Equals(name,"qjs",StringComparison.OrdinalIgnoreCase) || String.Equals(name,"quickjs",StringComparison.OrdinalIgnoreCase) || String.Equals(name,"qjs-windows-x86_64",StringComparison.OrdinalIgnoreCase))return "QuickJS";
                if(String.Equals(name,"node",StringComparison.OrdinalIgnoreCase))return "Node.js";
                if(String.Equals(name,"deno",StringComparison.OrdinalIgnoreCase))return "Deno";
                return "extractor helper";
            }
            static string N(double value){return value.ToString("0.000",CultureInfo.InvariantCulture);}
            void Sample(bool report){
                int[] ids=job.MemberProcessIds();
                if(ids==null){if(!enumerationWarning){enumerationWarning=true;trace.Event("child-process enumeration unavailable; root-only sampling");}ids=new[]{root};}
                foreach(int id in ids){
                    if(watched.ContainsKey(id))continue;
                    Process process=null;
                    try{
                        process=Process.GetProcessById(id);IntPtr pinned=process.Handle;
                        string label=Label(process.ProcessName,id==root);
                        var w=new Watched{Process=process,Handle=pinned,Label=label,Id=id,PreviousWall=clock.Elapsed.TotalSeconds};
                        watched.Add(id,w);process=null;if(label=="QuickJS" || label=="Node.js" || label=="Deno")jsSeen++;
                        trace.Event(label+" observed; PID="+id);
                    }catch(ArgumentException){}catch(InvalidOperationException){}catch(System.ComponentModel.Win32Exception){}
                    finally{if(process!=null)process.Dispose();}
                }
                foreach(var w in watched.Values){
                    if(w.Finished)continue;
                    long created,exited,kernel,user;
                    if(!GetProcessTimes(w.Handle,out created,out exited,out kernel,out user))continue;
                    w.Cpu=(kernel+user)/10000000.0;
                    w.Lifetime=Math.Max(0,((exited!=0?exited:DateTime.UtcNow.ToFileTimeUtc())-created)/10000000.0);
                    var memory=new Memory();memory.Size=(uint)Marshal.SizeOf(typeof(Memory));
                    double ram=-1;if(GetProcessMemoryInfo(w.Handle,ref memory,memory.Size)){ram=memory.WorkingSet.ToUInt64()/1048576.0;w.PeakMiB=Math.Max(w.PeakMiB,memory.PeakWorkingSet.ToUInt64()/1048576.0);}
                    if(exited!=0){uint code;string exit=GetExitCodeProcess(w.Handle,out code)?"exited; code="+code:"exited";Summary(w,exit);w.Finished=true;continue;}
                    if(!w.HasBaseline){w.PreviousCpu=w.Cpu;w.PreviousWall=clock.Elapsed.TotalSeconds;w.HasBaseline=true;continue;}
                    if(report){double wall=clock.Elapsed.TotalSeconds;trace.Event(w.Label+" sample; PID="+w.Id+"; CPU-total="+N(w.Cpu)+" s; CPU-interval="+N(CorePercent(w.Cpu-w.PreviousCpu,wall-w.PreviousWall))+"% of one core; RAM="+(ram<0?"unavailable":N(ram)+" MiB"));w.PreviousCpu=w.Cpu;w.PreviousWall=wall;}
                }
            }
            void Summary(Watched w,string state){trace.Event(w.Label+" "+state+"; PID="+w.Id+"; lifetime="+N(w.Lifetime)+" s; CPU-total="+N(w.Cpu)+" s; CPU-lifetime-average="+N(CorePercent(w.Cpu,w.Lifetime))+"% of one core; peak-RAM-observed="+N(w.PeakMiB)+" MiB");}
            public void Dispose(){
                cancel.Cancel();
                // Loop uses no UI context. Await at most one sampling iteration,
                // and leave playback unaffected even if diagnostics fail.
                try{if(loop.Wait(1500))cancel.Dispose();else if(trace!=null)trace.Event("process sampler shutdown delayed");}catch(AggregateException){}
            }
        }
        public static Attempt StartAttempt(string action,Settings s,bool audio){
            var a=new Attempt();a.Event(action+" requested; JS-runtime="+JavaScriptRuntime.Profile+"; audio="+audio+"; height<="+s.Height+"; H264="+s.H264+"; FPS30="+s.Fps30+"; session="+(s.AuthMedia?s.Auth:"none (media auth disabled)"));return a;
        }
        // Only fixed labels enter the report. Never retain raw yt-dlp debug text,
        // arguments, video IDs, paths, headers, cookies or signed stream URLs.
        public static string Stage(string line){
            string lower=line.ToLowerInvariant();
            if(lower.Contains("retrying"))return "yt-dlp: retry reported";
            if(lower.Contains("http error 403"))return "yt-dlp: HTTP 403";
            if(lower.Contains("http error 429"))return "yt-dlp: HTTP 429";
            if(lower.Contains("timed out"))return "yt-dlp: network timeout reported";
            if(lower.Contains("not a bot"))return "yt-dlp: bot confirmation requested";
            if(lower.Contains("extracting cookies"))return "yt-dlp: reading browser cookies";
            if(lower.Contains("cookies from firefox"))return "yt-dlp: Firefox cookies stage";
            if(lower.Contains("downloading") && lower.Contains("webpage"))return "yt-dlp: requesting webpage";
            if(lower.Contains("downloading") && lower.Contains("player api json"))return "yt-dlp: requesting player API";
            if(lower.Contains("downloading") && lower.Contains("player"))return "yt-dlp: requesting player resources";
            if(lower.Contains("running node:"))return "yt-dlp: Node.js launch command issued";
            if(lower.Contains("running deno:"))return "yt-dlp: Deno launch command issued";
            if(lower.Contains("running quickjs:"))return "yt-dlp: QuickJS launch command issued";
            if(lower.Contains("solving js challenges"))return "yt-dlp: JavaScript solving started";
            if(lower.Contains("[jsc") && lower.Contains("cache"))return "yt-dlp: JavaScript cache stage";
            if(lower.Contains("[jsc"))return "yt-dlp: JavaScript provider diagnostic";
            if(lower.Contains("downloading") && (lower.Contains("manifest") || lower.Contains("m3u8")))return "yt-dlp: requesting stream manifest";
            if(lower.StartsWith("[debug] yt-dlp version"))return "yt-dlp: Python/runtime initialized";
            if(lower.StartsWith("error:"))return "yt-dlp: error reported (see application log)";
            if(lower.StartsWith("warning:"))return "yt-dlp: warning reported (see application log)";
            return null;
        }
        public static void Begin(){try{processStart=Process.GetCurrentProcess().StartTime.ToUniversalTime();}catch{processStart=DateTime.UtcNow;}Mark("managed entry");
            TimeSpan launchTime;string raw=Environment.GetEnvironmentVariable("LOWTUBE_LAUNCH_TIME")??"";
            if(TimeSpan.TryParse(raw.Trim().Replace(',','.'),System.Globalization.CultureInfo.InvariantCulture,out launchTime) && launchTime>=TimeSpan.Zero && launchTime<TimeSpan.FromDays(1)){
                DateTime local=DateTime.Now,launch=local.Date+launchTime;if(launch>local)launch=launch.AddDays(-1);
                if(local-launch<TimeSpan.FromHours(1))Mark("START.cmd to managed entry: "+(local-launch).TotalSeconds.ToString("0.000",System.Globalization.CultureInfo.InvariantCulture)+" s");
            }
        }
        public static void Mark(string phase){lock(gate){lines.Add(DateTime.UtcNow.ToString("HH:mm:ss.fff")+" UTC | "+(DateTime.UtcNow-processStart).TotalSeconds.ToString("0.000",System.Globalization.CultureInfo.InvariantCulture)+" s | "+phase);if(lines.Count>600)lines.RemoveAt(0);if(!pendingSave){pendingSave=true;saver.Change(500,Timeout.Infinite);}}}
        public static string Report(){lock(gate)return "LowTube 0.12.2 — startup timings (no URLs or cookies)\r\n"+String.Join("\r\n",lines);}
        public static void Flush(){Save();}
        public static Task Save(){return Task.Run(delegate{try{lock(gate){Directory.CreateDirectory(Paths.Data);File.WriteAllText(Path.Combine(Paths.Data,"startup.txt"),Report(),new UTF8Encoding(false));}}catch(IOException){}catch(UnauthorizedAccessException){}});}
    }
}
