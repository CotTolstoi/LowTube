using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Threading;
using System.Collections;
using System.Globalization;

namespace LowTube {
    // Windows job object owns child processes, including ffmpeg and the JS runtime.
    public sealed class ProcessJob : IDisposable {
        IntPtr handle;
        [StructLayout(LayoutKind.Sequential)] struct Basic {
            public long PerProcessUserTimeLimit,PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize,MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass,SchedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)] struct Io {
            public ulong ReadOperationCount,WriteOperationCount,OtherOperationCount,ReadTransferCount,WriteTransferCount,OtherTransferCount;
        }
        [StructLayout(LayoutKind.Sequential)] struct Extended {
            public Basic BasicLimitInformation; public Io IoInfo;
            public UIntPtr ProcessMemoryLimit,JobMemoryLimit,PeakProcessMemoryUsed,PeakJobMemoryUsed;
        }
        [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
        [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job,int info,IntPtr data,uint length);
        [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
        [DllImport("kernel32.dll")] static extern bool TerminateJobObject(IntPtr job,uint code);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        public ProcessJob() {
            if(Environment.OSVersion.Platform!=PlatformID.Win32NT) return;
            handle=CreateJobObject(IntPtr.Zero,null);
            if(handle==IntPtr.Zero) throw new InvalidOperationException(L.T("Windows could not create a process group."));
            var info=new Extended(); info.BasicLimitInformation.LimitFlags=0x2000;
            int size=Marshal.SizeOf(typeof(Extended)); IntPtr ptr=Marshal.AllocHGlobal(size);
            try { Marshal.StructureToPtr(info,ptr,false); if(!SetInformationJobObject(handle,9,ptr,(uint)size)) { Dispose(); throw new InvalidOperationException(L.T("Could not configure the Windows process group.")); } }
            finally { Marshal.FreeHGlobal(ptr); }
        }
        public void Attach(Process p) {
            if(p.HasExited) return;
            if(handle!=IntPtr.Zero && !AssignProcessToJobObject(handle,p.Handle)) {
                if(p.HasExited) return;
                try { p.Kill(); } catch(InvalidOperationException) {}
                throw new InvalidOperationException(L.T("Could not attach to the Windows process group. Run LowTube outside a sandbox or another launcher."));
            }
        }
        [DllImport("kernel32.dll",SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int info,IntPtr data,uint length,out uint returned);
        public int[] MemberProcessIds(){
            if(handle==IntPtr.Zero)return null;
            for(int capacity=32;capacity<=4096;capacity*=2){
                int size=8+capacity*IntPtr.Size;IntPtr data=Marshal.AllocHGlobal(size);
                try{
                    uint returned;bool ok=QueryInformationJobObject(handle,3,data,(uint)size,out returned);
                    if(!ok){if(Marshal.GetLastWin32Error()==234)continue;return null;}
                    int assigned=Marshal.ReadInt32(data,0),count=Marshal.ReadInt32(data,4);
                    if(assigned>count)continue;
                    if(count<0 || count>capacity)return null;
                    var ids=new int[count];for(int i=0;i<count;i++)ids[i]=checked((int)Marshal.ReadIntPtr(data,8+i*IntPtr.Size).ToInt64());return ids;
                }finally{Marshal.FreeHGlobal(data);}
            }
            return null;
        }
        public void Kill() { if(handle!=IntPtr.Zero) TerminateJobObject(handle,1); }
        public void Dispose() { if(handle!=IntPtr.Zero) { CloseHandle(handle); handle=IntPtr.Zero; } }
    }
    public class Media {
        public string VideoUrl="", AudioUrl="", Title="", TlsCaFile="";
        public bool IsLive;
        public Dictionary<string,object> Headers=new Dictionary<string,object>();
        static string StreamUrl(string url) {
            Uri u;
            if(!Uri.TryCreate(url,UriKind.Absolute,out u) || (u.Scheme!="https" && u.Scheme!="http") || !String.IsNullOrEmpty(u.UserInfo))
                throw new InvalidDataException(L.T("YouTube did not return a usable HTTP stream. Update yt-dlp."));
            return url;
        }
        public static string PlayerUrl(string url,string protocol,bool live,bool certAvailable) {
            url=StreamUrl(url);var uri=new Uri(url);
            // Use FFmpeg's bounded HTTP requests only for direct YouTube CDN files.
            // Manifests and live streams retain their normal protocol handling.
            if(certAvailable && !live && (protocol=="http" || protocol=="https") && uri.Host.EndsWith(".googlevideo.com",StringComparison.OrdinalIgnoreCase) && uri.AbsolutePath.Equals("/videoplayback",StringComparison.Ordinal))return "lavf://"+url;
            return url;
        }
        public static Media From(Dictionary<string,object> root,bool audio) {
            var m=new Media { Title=Json.Str(root,"title","LowTube"),IsLive=Json.Str(root,"live_status")=="is_live" || Json.Str(root,"is_live").Equals("True",StringComparison.OrdinalIgnoreCase) };
            string ca=Path.Combine(Paths.Root,"tools","yt-dlp","_internal","certifi","cacert.pem");bool certAvailable=File.Exists(ca);
            object h; if(root.TryGetValue("http_headers",out h) && h is Dictionary<string,object>) m.Headers=(Dictionary<string,object>)h;
            var streams=Json.Items(root,"requested_formats").ToList();
            if(streams.Count==0) streams.Add(root);
            foreach(var stream in streams) {
                string url=Json.Str(stream,"url"); if(url.Length==0) continue;
                string vc=Json.Str(stream,"vcodec"),ac=Json.Str(stream,"acodec");
                if(audio) { if(ac.Length>0 && ac!="none") { m.VideoUrl=PlayerUrl(url,Json.Str(stream,"protocol"),m.IsLive,certAvailable); break; } }
                else if(vc!="none" && vc.Length>0) m.VideoUrl=PlayerUrl(url,Json.Str(stream,"protocol"),m.IsLive,certAvailable);
                else if(ac!="none") m.AudioUrl=PlayerUrl(url,Json.Str(stream,"protocol"),m.IsLive,certAvailable);
            }
            if(m.VideoUrl.Length==0) throw new InvalidDataException(audio?L.T("A separate audio stream is unavailable."):L.T("No video stream matches these settings. Check available formats or disable H.264 only. "));
            if(m.VideoUrl.StartsWith("lavf://",StringComparison.Ordinal) || m.AudioUrl.StartsWith("lavf://",StringComparison.Ordinal))m.TlsCaFile=ca;
            return m;
        }
    }
    public sealed class Player : IDisposable {
        readonly object lifecycle=new object();
        readonly PlaybackTrace playbackLog=new PlaybackTrace();
        Process process;
        ProcessJob job;
        string pipe;
        NamedPipeClientStream events;
        public event Action SettingsRequested;
        public event Action<string,string> QuickSettingRequested;
        public event Action<string,bool> PlaybackEnded;
        public event Action<Dictionary<string,object>> Progress;
        public event Action<string,string> PlaybackFailed;
        public event Action<string> PreparedStarted;
        public event Action Stopped;
        string nextOwner,nextTicket;
        TaskCompletionSource<bool> staged,cancelledNext;
        string cancellingTicket;
        public bool HasPrepared(string owner){return nextOwner==owner && nextTicket!=null;}
        readonly SemaphoreSlim gate=new SemaphoreSlim(1,1);
        string session,activeRenderer,replacingSession,activeEntry;
        bool playing,disposed;
        int stopGeneration;
        StartupTrace.Attempt activeTrace;
        Stopwatch mediaClock;
        bool loadObserved,playbackObserved,awaitingEntry;
        int diagnosticLines;
        TaskCompletionSource<bool> ready,loaded;
        public string Session { get { return session; } }
        public bool Running {get{return playing && Alive;}}

        public bool Alive { get { try { return process!=null && !process.HasExited; } catch(InvalidOperationException) { return false; } } }
        public static List<string> BuildArgs(Settings s,Media m,bool audio,string pipe,double position=0,bool paused=false,double volume=100,double speed=1) {
            if(!Rules.Decoders.Contains(s.Decoder) || !Rules.Renderers.Contains(s.Renderer)) throw new ArgumentException(L.T("Unsupported decoder or renderer."));
            string effectiveRenderer=Rules.CompatibleRenderer(s.Decoder,s.Renderer);
            var a=new List<string>{"--no-config","--load-scripts=no","--ytdl=no","--force-window="+(audio?"no":"yes"),"--keep-open=no","--input-ipc-server="+pipe,
                "--title=LowTube — "+m.Title,"--cache=yes","--cache-secs="+Rules.CacheSeconds(s),"--demuxer-max-bytes="+Rules.CacheMiB(s)+"MiB","--demuxer-max-back-bytes="+Rules.BackCacheMiB(s)+"MiB","--demuxer-donate-buffer=no","--cache-on-disk=no",
                "--cache-pause=yes","--cache-pause-initial=yes","--cache-pause-wait="+Rules.StartCacheSeconds(s),"--initial-audio-sync=yes","--stream-lavf-o=request_size=8388608,short_seek_size=8388608",
                "--hwdec="+(audio?"no":s.Decoder),"--msg-level=all=warn"};
            a.AddRange(PlayerTuning.Arguments(s));
            a.Add("--af="+AudioEqualizer.Filter(s.Equalizer));
            if(audio) a.AddRange(new[]{"--vid=no","--audio-display=no","--vo=null"});
            else {
                a.Add("--ontop="+(s.PlayerOnTop?"yes":"no"));
                a.Add("--border="+(s.PlayerBorderless?"no":"yes"));
                a.Add("--osc=no");
                a.Add("--script="+Path.Combine(Paths.Root,"player","lowtube_osc.lua"));
                a.Add("--script="+Path.Combine(Paths.Root,"player","lowtube.lua"));
                a.Add("--script-opts=lowtube-safe_seek="+(s.SafeSeeking?"yes":"no")+",lowtube-language="+L.Language);
                a.Add("--hr-seek="+(s.SafeSeeking?"yes":"default"));
                a.AddRange(new[]{"--vo=gpu","--gpu-context="+(effectiveRenderer=="opengl"?"win":"d3d11"),"--autofit=85%"});
                if(effectiveRenderer=="d3d11") a.Add("--d3d11-flip="+(s.D3D11Flip?"yes":"no"));
            }
            if(!audio && m.AudioUrl.Length>0) a.Add("--audio-file="+m.AudioUrl);
            if(m.TlsCaFile.Length>0){a.Add("--tls-verify=yes");a.Add("--tls-ca-file="+m.TlsCaFile);}
            // YouTube CDN generally needs UA and Referer; never forward account cookies to mpv.
            string ua=Json.Str(m.Headers,"User-Agent"),referer=Json.Str(m.Headers,"Referer");
            if(ua.Length>0 && !ua.Contains("\r") && !ua.Contains("\n")) a.Add("--user-agent="+ua);
            if(referer.Length>0 && !referer.Contains("\r") && !referer.Contains("\n")) a.Add("--referrer="+referer);
            if(Double.IsNaN(position) || Double.IsInfinity(position) || position<0)position=0;
            if(Double.IsNaN(volume) || Double.IsInfinity(volume))volume=100;
            if(Double.IsNaN(speed) || Double.IsInfinity(speed))speed=1;
            if(position>0)a.Add("--start="+position.ToString("0.###",CultureInfo.InvariantCulture));
            a.Add("--pause="+(paused?"yes":"no"));a.Add("--volume="+Math.Max(0,Math.Min(100,volume)).ToString("0.###",CultureInfo.InvariantCulture));
            a.Add("--speed="+Math.Max(0.25,Math.Min(2,speed)).ToString("0.###",CultureInfo.InvariantCulture));
            a.Add("--"); a.Add(m.VideoUrl); return a;
        }
        public async Task Prepare(Settings s,Action<string> progress){
            await gate.WaitAsync();try{await PrepareCore(s,progress);}finally{gate.Release();}
        }
        async Task PrepareCore(Settings s,Action<string> progress){
            if(disposed)throw new ObjectDisposedException("Player");
            if(Alive && activeRenderer==PlayerTuning.ProcessKey(s)){if(ready!=null)await ready.Task;return;}
            Shutdown();
            if(!File.Exists(Path.Combine(Paths.Root,"player","lowtube.lua")) || !File.Exists(Path.Combine(Paths.Root,"player","lowtube_osc.lua")))throw new FileNotFoundException(L.T("Missing player script. Extract the complete LowTube archive."));
            pipe="lowtube-"+Guid.NewGuid().ToString("N");string own=pipe;
            var args=BuildArgs(s,new Media{Title="LowTube",VideoUrl="unused"},false,@"\\.\pipe\"+pipe);
            args.RemoveRange(args.Count-2,2);
            args.RemoveAll(x=>x.StartsWith("--force-window=") || x.StartsWith("--pause="));
            args.AddRange(new[]{"--idle=yes","--force-window=no","--terminal=no","--audio-display=no","--gapless-audio=weak"});
            ready=new TaskCompletionSource<bool>();
            var info=new ProcessStartInfo(Paths.Tool("mpv"),Rules.Arguments(args)){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true,StandardErrorEncoding=Encoding.UTF8,StandardOutputEncoding=Encoding.UTF8,WorkingDirectory=Paths.Root};
            var p=new Process{StartInfo=info};var group=new ProcessJob();var clock=Stopwatch.StartNew();
            try{
                await Task.Run(delegate{p.Start();group.Attach(p);lock(lifecycle){if(disposed || pipe!=own){group.Dispose();p.Dispose();throw new OperationCanceledException();}process=p;job=group;}});
                activeRenderer=PlayerTuning.ProcessKey(s);
                Drain(p.StandardOutput,progress);Drain(p.StandardError,progress);Listen(own,p,clock,progress);
                if(await Task.WhenAny(ready.Task,Task.Delay(90000))!=ready.Task)throw new TimeoutException(L.T("mpv did not respond."));
                await ready.Task;
            }catch{group.Dispose();Shutdown();throw;}
        }
        static Dictionary<string,object> LoadOptions(Settings s,Media media,bool audio,double position,bool paused,double volume,double speed){
            var opts=new Dictionary<string,object>();
            foreach(string arg in BuildArgs(s,media,audio,"unused",position,paused,volume,speed)){
                if(!arg.StartsWith("--") || arg.IndexOf('=')<0)continue;
                int eq=arg.IndexOf('=');string key=arg.Substring(2,eq-2);
                if(new[]{"title","cache-secs","demuxer-max-bytes","demuxer-max-back-bytes","cache-pause-wait","hwdec","hr-seek","ontop","border","user-agent","referrer","tls-ca-file","tls-verify","start","pause","volume","speed","af","vd-lavc-threads","scale","cscale","dscale","video-sync","framedrop","deband","interpolation","deinterlace","audio-pitch-correction","d3d11va-zero-copy"}.Contains(key))opts[key]=arg.Substring(eq+1);
            }
            opts["vid"]=audio?"no":"auto";if(!media.IsLive || position>0)opts["start"]=Math.Max(0,position).ToString("0.###",CultureInfo.InvariantCulture);
            return opts;
        }
        public async Task StageNext(Settings s,Media media,bool audio,double position,string owner,string ticket){
            await gate.WaitAsync();try{
                if(!Running || session!=owner)throw new OperationCanceledException();
                if(activeRenderer!=PlayerTuning.ProcessKey(s))throw new InvalidOperationException("Prefetch requires the current rendering context.");
                var options=LoadOptions(s,media,audio,position,false,100,1);
                // Preserve current user controls at transition; cap the prefetched demuxer.
                options.Remove("pause");options.Remove("volume");options.Remove("speed");
                options["demuxer-max-bytes"]=Math.Min(16,Rules.CacheMiB(s))+"MiB";
                options["demuxer-max-back-bytes"]="0";
                nextOwner=owner;nextTicket=ticket;staged=new TaskCompletionSource<bool>();
                await Command("script-message","lowtube-stage",Json.Serializer().Serialize(new{session=ticket,owner=owner,url=media.VideoUrl,audio=media.AudioUrl,options=options,cache_mib=Rules.CacheMiB(s),back_mib=Rules.BackCacheMiB(s)}));
                if(await Task.WhenAny(staged.Task,Task.Delay(5000))!=staged.Task)throw new TimeoutException("Prefetch acknowledgement timed out.");
                await staged.Task;
            }finally{gate.Release();}
        }
        public async Task CancelNext(){
            if(nextTicket==null)return;
            await gate.WaitAsync();try{
                string ticket=nextTicket;if(ticket==null)return;
                if(Alive){
                    cancellingTicket=ticket;cancelledNext=new TaskCompletionSource<bool>();
                    await Command("script-message","lowtube-cancel-next",ticket);
                    if(await Task.WhenAny(cancelledNext.Task,Task.Delay(5000))!=cancelledNext.Task)throw new TimeoutException("Prefetch cancellation timed out.");
                    await cancelledNext.Task;
                }
                if(nextTicket==ticket){nextOwner=null;nextTicket=null;}
            }finally{cancellingTicket=null;gate.Release();}
        }
        public async Task Start(Settings s,Media media,bool audio,Action<string> onError,double position=0,bool paused=false,double volume=100,double speed=1,StartupTrace.Attempt trace=null){
            int generation=stopGeneration;bool opening=false;
            await gate.WaitAsync();try{Exception failure=null;try{
                if(generation!=stopGeneration)throw new OperationCanceledException();
                if(trace!=null)trace.Event(Alive && activeRenderer==PlayerTuning.ProcessKey(s)?"mpv reuse requested":"mpv process preparation required");
                await PrepareCore(s,onError);
                if(trace!=null){trace.Event("mpv IPC ready for this attempt; PID="+process.Id);trace.Event("buffer configuration: target="+Rules.CacheSeconds(s)+" s; initial="+Rules.StartCacheSeconds(s)+" s; cap="+Rules.CacheMiB(s)+" MiB per demuxer");}
                if(generation!=stopGeneration)throw new OperationCanceledException();
                nextOwner=null;nextTicket=null;if(staged!=null)staged.TrySetCanceled();
                var opts=LoadOptions(s,media,audio,position,paused,volume,speed);
                playbackLog.Begin(s,audio);
                playbackLog.Event("performance: "+PlayerTuning.Signature(s));
                activeTrace=trace;diagnosticLines=0;mediaClock=Stopwatch.StartNew();loadObserved=false;playbackObserved=false;
                if(trace!=null)trace.Event("mpv stream load requested; position="+position.ToString("0.###",CultureInfo.InvariantCulture)+"; paused="+paused);
                replacingSession=session;loaded=new TaskCompletionSource<bool>();playing=true;session=Guid.NewGuid().ToString("N");activeEntry=null;awaitingEntry=true;opening=true;
                string payload=Json.Serializer().Serialize(new{session=session,url=media.VideoUrl,audio=media.AudioUrl,options=opts,language=s.Language,safe_seek=s.SafeSeeking,debug=PlaybackTrace.Enabled});
                await Command("script-message","lowtube-load",payload);
                if(await Task.WhenAny(loaded.Task,Task.Delay(60000))!=loaded.Task)throw new TimeoutException(L.T("mpv did not respond."));
                await loaded.Task;
                if(!audio){await SyncMenu(s,false);await FocusWhenReady(generation);}
                if(generation!=stopGeneration)throw new OperationCanceledException();
            }catch(Exception e){failure=e;}
            if(failure!=null){
                // Finish cleanup under this gate; a delayed async Stop must not stop a later Start.
                if(opening){if(generation==stopGeneration)CancelPlayback();await StopCore();}
                System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(failure).Throw();
            }}finally{replacingSession=null;gate.Release();}
        }
        async void Listen(string own,Process p,Stopwatch clock,Action<string> progress){
            bool connected=false;
            try{
                // Cold HDD startup can be slow; connect off the UI thread, without a fixed delay.
                while(pipe==own && Alive && clock.Elapsed.TotalSeconds<90){
                    var client=new NamedPipeClientStream(".",own,PipeDirection.InOut,PipeOptions.Asynchronous);events=client;
                    try{await Task.Run(delegate{client.Connect(1200);});}
                    catch(TimeoutException){}
                    if(!client.IsConnected){client.Dispose();await Task.Delay(250);continue;}
                    if(pipe!=own){client.Dispose();return;}
                    connected=true;
                    if(ready!=null)ready.TrySetResult(true);
                    StartupTrace.Mark("mpv IPC ready: "+clock.Elapsed.TotalSeconds.ToString("0.0",CultureInfo.InvariantCulture)+" s");
                    using(client)using(var reader=new StreamReader(client,Encoding.UTF8,false,4096,true)){
                        string line;
                        while(pipe==own && (line=await reader.ReadLineAsync())!=null){
                            if(line.Length>1024*1024)continue;
                            var message=Json.Object(line);string kind=Json.Str(message,"event");
                            // A quit ends the entire process, including any pending replacement load.
                            // Handle it before entry filtering so it cannot become an IPC recovery failure.
                            if(kind=="end-file" && Json.Str(message,"reason")=="quit"){
                                if(activeTrace!=null)activeTrace.Event("mpv orderly exit; playback cancelled");
                                NativeStopped();continue;
                            }
                            if(kind=="start-file" && session!=null){activeEntry=Json.Str(message,"playlist_entry_id");awaitingEntry=false;}
                            // Replacement/Stop events from the previous playlist entry cannot finish a new load.
                            if((kind=="file-loaded" || kind=="playback-restart" || kind=="end-file") && (session==null || awaitingEntry))continue;
                            if(kind=="end-file" && !String.IsNullOrEmpty(activeEntry) && Json.Str(message,"playlist_entry_id").Length>0 && Json.Str(message,"playlist_entry_id")!=activeEntry)continue;
                            if(new[]{"file-loaded","seek","playback-restart","end-file","video-reconfig","audio-reconfig"}.Contains(kind))playbackLog.Event(kind);
                            if(kind=="file-loaded"){
                                loadObserved=true;if(activeTrace!=null)activeTrace.Event("mpv file-loaded (streams opened)");
                                if(loaded!=null)loaded.TrySetResult(true);
                            }
                            if(kind=="playback-restart" && loadObserved && !playbackObserved){
                                playbackObserved=true;
                                if(activeTrace!=null)activeTrace.Event("mpv playback-restart; load-to-ready="+mediaClock.Elapsed.TotalSeconds.ToString("0.000",CultureInfo.InvariantCulture)+" s (mpv readiness, not measured screen frame)");
                            }
                            if(kind=="end-file"){
                                string reason=Json.Str(message,"reason");
                                if(activeTrace!=null && new[]{"eof","stop","quit","error","redirect"}.Contains(reason))activeTrace.Event("mpv end-file: "+reason+(reason=="stop" && !loadObserved?" (may be previous stream during replacement)":""));
                                if(reason=="error"){playing=false;var failure=StreamOpenException.FromEvent(message);if(activeTrace!=null)activeTrace.Event("mpv stream error: "+failure.Reason);if(loaded!=null)loaded.TrySetException(failure);progress(failure.Message);if(PlaybackFailed!=null)PlaybackFailed(session,failure.Message);}
                                else if(reason=="eof" && (loaded==null || loaded.Task.IsCompleted)){playing=false;if(PlaybackEnded!=null)PlaybackEnded(session,HasPrepared(session));}
                                else if(reason=="stop" && (loaded==null || loaded.Task.IsCompleted))playing=false;
                            }
                            if(kind=="client-message"){
                                object raw;var args=message.TryGetValue("args",out raw)?raw as object[]:null;
                                if(args!=null && args.Length==2 && Object.Equals(args[0],"lowtube-next-cancelled") && Convert.ToString(args[1])==cancellingTicket && cancelledNext!=null)cancelledNext.TrySetResult(true);
                                if(args!=null && args.Length==2 && Object.Equals(args[0],"lowtube-stage-ready") && Convert.ToString(args[1])==nextTicket && staged!=null)staged.TrySetResult(true);
                                if(args!=null && args.Length==2 && Object.Equals(args[0],"lowtube-stage-error") && Convert.ToString(args[1])==nextTicket && staged!=null)staged.TrySetException(new IOException("Prefetch unavailable."));
                                if(args!=null && args.Length==2 && Object.Equals(args[0],"lowtube-next-start") && Convert.ToString(args[1])==nextTicket){
                                    session=nextTicket;nextOwner=null;nextTicket=null;playing=true;loaded=null;activeTrace=null;loadObserved=false;playbackObserved=false;
                                    if(PreparedStarted!=null)PreparedStarted(session);
                                }
                                if(args!=null && args.Length==2 && Object.Equals(args[0],"lowtube-progress") && Progress!=null)Progress(Json.Object(Convert.ToString(args[1])));
                                if(args!=null && args.Length==1 && Object.Equals(args[0],"lowtube-debug-mark"))playbackLog.Event("USER MARK F8");
                                if(args!=null && args.Length==2 && Object.Equals(args[0],"lowtube-debug"))playbackLog.Sample(Convert.ToString(args[1],CultureInfo.InvariantCulture),p);
                                if(args!=null && args.Length==2 && Object.Equals(args[0],"lowtube-stopped")){
                                    string stoppedSession=Convert.ToString(args[1]);
                                    if(stoppedSession.Length>0 && (stoppedSession==session || stoppedSession==replacingSession)){
                                        NativeStopped();
                                    }
                                }
                                if(args!=null && args.Length==1 && Object.Equals(args[0],"lowtube-load-error") && loaded!=null)loaded.TrySetException(new StreamOpenException("unknown"));
                                if(args!=null && args.Length==3 && Object.Equals(args[0],"lowtube-quick") && QuickSettingRequested!=null)QuickSettingRequested(Convert.ToString(args[1]),Convert.ToString(args[2]));
                                if(args!=null && args.Length==1 && Object.Equals(args[0],"lowtube-settings") && SettingsRequested!=null)SettingsRequested();
                            }
                        }
                    }
                    break;
                }
                if(pipe==own && !connected){progress(L.T("mpv controls did not become ready. Check Player diagnostics and the startup report."));await StartupTrace.Save();}
            }catch(IOException){}catch(ObjectDisposedException){}catch(Exception e){if(pipe==own)progress(Rules.Redact(e.Message));}
            if(pipe==own){
                // Windows JSON IPC closes on shutdown without forwarding that event.
                // A normal idle quit has no end-file either. Read its exit status before cleanup.
                bool normalExit=false;
                try{normalExit=await Task.Run(()=>p.WaitForExit(500) && p.ExitCode==0);}catch(InvalidOperationException){}
                if(pipe!=own)return;
                if(normalExit)NativeStopped();
                var failure=new IOException(L.T("mpv did not respond."));bool failed=playing;string failedSession=session;
                if(ready!=null)ready.TrySetException(failure);if(loaded!=null)loaded.TrySetException(failure);
                Shutdown();if(failed && PlaybackFailed!=null)PlaybackFailed(failedSession,failure.Message);
            }
        }
        async void Drain(StreamReader reader,Action<string> onError) {
            try { string line; while((line=await reader.ReadLineAsync())!=null) if(!String.IsNullOrWhiteSpace(line)){string diagnostic=StreamOpenRecovery.Diagnostic(line);var trace=activeTrace;if(diagnostic!=null && trace!=null && Interlocked.Increment(ref diagnosticLines)<=20)trace.Event("mpv diagnostic: "+diagnostic);onError(StreamOpenRecovery.SafeLog(line));} }
            catch(IOException) {} catch(ObjectDisposedException) {}
        }
        public async Task ApplyEqualizer(EqualizerSettings settings){
            await gate.WaitAsync();try{
                if(!Alive)return;
                object old=await Command("get_property","af");
                Exception failure=null;try{await Command("set_property","af",AudioEqualizer.MergeFilters(old,settings));}catch(Exception e){failure=e;}
                if(failure!=null){try{await Command("set_property","af",old);}catch(Exception){}throw failure;}
            }finally{gate.Release();}
        }
        public async Task<object> Command(params object[] command) {
            if(!Alive) throw new InvalidOperationException(L.T("Player is not running."));
            string name=pipe;
            return await Task.Run(delegate {
                using(var client=new NamedPipeClientStream(".",name,PipeDirection.InOut,PipeOptions.Asynchronous)) {
                    client.Connect(1500);
                    using(var writer=new StreamWriter(client,new UTF8Encoding(false),1024,true))
                    using(var reader=new StreamReader(client,Encoding.UTF8,false,1024,true)) {
                        writer.AutoFlush=true; writer.WriteLine(Json.Serializer().Serialize(new { command=command,request_id=1 }));
                        for(int i=0;i<20;i++) {
                            var task=reader.ReadLineAsync();
                            if(!task.Wait(1500)) throw new TimeoutException(L.T("mpv did not respond."));
                            string line=task.Result; if(line==null) break;
                            var reply=Json.Object(line);
                            if(Json.Str(reply,"request_id")!="1") continue;
                            if(Json.Str(reply,"error")!="success") throw new InvalidOperationException("mpv: "+Json.Str(reply,"error"));
                            object result; return reply.TryGetValue("data",out result)?result:null;
                        }
                        return null;
                    }
                }
            });
        }
        public Task SyncMenu(Settings s,bool busy){return Command("script-message","lowtube-menu-state",Json.Serializer().Serialize(new{height=s.Height,h264=s.H264,fps=s.Fps30,language=s.Language,busy=busy}));}
        [DllImport("user32.dll")]static extern bool ShowWindowAsync(IntPtr window,int command);
        [DllImport("user32.dll")]static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
        [DllImport("user32.dll")]static extern bool IsIconic(IntPtr window);
        [DllImport("user32.dll")]static extern bool AllowSetForegroundWindow(int processId);
        async Task FocusWhenReady(int generation){
            for(int i=0;i<15 && generation==stopGeneration && Running;i++){
                process.Refresh();if(process.MainWindowHandle!=IntPtr.Zero){FocusWindow();return;}await Task.Delay(100);
            }
        }
        [DllImport("user32.dll")]static extern bool SetForegroundWindow(IntPtr window);
        public void FocusWindow(){try{if(Running){process.Refresh();var window=process.MainWindowHandle;if(window!=IntPtr.Zero){AllowSetForegroundWindow(process.Id);if(IsIconic(window))ShowWindowAsync(window,9);else ShowWindowAsync(window,5);SetWindowPos(window,IntPtr.Zero,0,0,0,0,0x43);SetForegroundWindow(window);}}}catch(InvalidOperationException){}catch(DllNotFoundException){}}
        public void RaiseTopmost(){try{if(Running){process.Refresh();var w=process.MainWindowHandle;if(w!=IntPtr.Zero && !IsIconic(w))SetWindowPos(w,new IntPtr(-1),0,0,0,0,0x13);}}catch(InvalidOperationException){}catch(DllNotFoundException){}}
        void CancelPlayback(){
            if(playing && activeTrace!=null)activeTrace.Event("stop requested");
            Interlocked.Increment(ref stopGeneration);playing=false;session=null;replacingSession=null;nextOwner=null;nextTicket=null;if(staged!=null)staged.TrySetCanceled();
            if(loaded!=null)loaded.TrySetCanceled();
        }
        void NativeStopped(){
            if(session==null && replacingSession==null)return;
            CancelPlayback();if(Stopped!=null)Stopped();
        }
        async Task StopCore(){try{if(Alive)await Command("script-message","lowtube-stop");}catch(Exception){}}
        public async void Stop(){
            CancelPlayback();int generation=stopGeneration;
            await gate.WaitAsync();try{if(generation==stopGeneration && session==null)await StopCore();}finally{gate.Release();}
        }
        void Shutdown() {
            lock(lifecycle){
            playing=false;pipe=null;if(ready!=null)ready.TrySetCanceled();if(loaded!=null)loaded.TrySetCanceled();if(events!=null){events.Dispose();events=null;}
            if(job!=null) { job.Dispose(); job=null; }
            if(process!=null) { try { if(!process.HasExited) process.Kill(); } catch(InvalidOperationException){} process.Dispose(); process=null; }
        }
        }
        public void Dispose() { disposed=true;Shutdown(); }
    }
}
