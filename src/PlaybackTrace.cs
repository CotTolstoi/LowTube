using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
namespace LowTube {
    // Fixed property allowlist: never persist titles, paths, headers or URLs.
    public sealed class PlaybackTrace {
        public static bool Enabled {get{return Environment.GetEnvironmentVariable("LOWTUBE_PLAYBACK_DEBUG")=="1";}}
        public const string Properties="time-pos,duration,video-format,video-codec,video-params/w,video-params/h,video-params/pixelformat,container-fps,estimated-vf-fps,hwdec,hwdec-current,hwdec-interop,current-vo,gpu-context,audio-codec,audio-params/samplerate,frame-drop-count,decoder-frame-drop-count,mistimed-frame-count,vo-delayed-frame-count,avsync,total-avsync-change,demuxer-cache-duration,demuxer-cache-state/fw-bytes,demuxer-cache-state/underrun,cache-speed,paused-for-cache,cache-buffering-state,pause,seeking,speed,display-fps,estimated-display-fps,video-bitrate,audio-bitrate";
        readonly Stopwatch clock=Stopwatch.StartNew();
        double previousCpu,previousWall;int previousPid;bool initialized;int attempt;
        public void Begin(Settings s,bool audio){
            if(!Enabled)return;
            if(!initialized){initialized=true;try{Directory.CreateDirectory(Paths.Data);string path=Path.Combine(Paths.Data,"playback-debug.txt");if(File.Exists(path))File.Copy(path,Path.Combine(Paths.Data,"playback-debug.previous.txt"),true);File.WriteAllText(path,"LowTube 0.11.0 playback diagnostics; no URLs/titles/cookies; sample=2s; CPU 100%=one logical core\r\n");}catch(IOException){}catch(UnauthorizedAccessException){}}
            attempt++;previousPid=0;
            Event("load requested; decoder="+s.Decoder+"; renderer="+s.Renderer+"; audio="+audio+"; buffer-seconds="+Rules.CacheSeconds(s)+"; buffer-MiB="+Rules.CacheMiB(s));
        }
        public static string Filter(string json){
            var data=Json.Object(json);var b=new StringBuilder();
            foreach(string key in Properties.Split(',')){
                object v;if(!data.TryGetValue(key,out v))continue;
                string value=Convert.ToString(v,CultureInfo.InvariantCulture);
                // Diagnostic values are numeric, booleans, or short codec/backend names.
                if(value.Length>100 || !Regex.IsMatch(value,@"^[A-Za-z0-9 _.,()+:\-]*$"))value="unavailable";
                b.Append(key).Append('=').Append(value).Append("; ");
            }
            return b.ToString();
        }
        public void Sample(string json,Process process){
            if(!Enabled || !initialized || json.Length>16384)return;
            try{
                string line=Filter(json);process.Refresh();double cpu=process.TotalProcessorTime.TotalSeconds,wall=clock.Elapsed.TotalSeconds;
                if(previousPid==process.Id && wall>previousWall)line+="mpv-CPU="+((cpu-previousCpu)/(wall-previousWall)*100).ToString("0.0",CultureInfo.InvariantCulture)+"%; ";
                previousPid=process.Id;previousCpu=cpu;previousWall=wall;
                line+="mpv-RAM-MiB="+(process.WorkingSet64/1048576.0).ToString("0.0",CultureInfo.InvariantCulture);
                Event(line);
            }catch(Exception e){Event("sample unavailable: "+e.GetType().Name);}
        }
        public void Event(string value){
            if(!Enabled || !initialized)return;
            try{
                string path=Path.Combine(Paths.Data,"playback-debug.txt");
                if(File.Exists(path) && new FileInfo(path).Length>2*1024*1024){File.Copy(path,Path.Combine(Paths.Data,"playback-debug.part.txt"),true);File.WriteAllText(path,"Continuation; previous 2 MiB in playback-debug.part.txt\r\n");}
                File.AppendAllText(path,DateTime.UtcNow.ToString("HH:mm:ss.fff",CultureInfo.InvariantCulture)+" UTC | load #"+attempt+" | "+value+"\r\n",new UTF8Encoding(false));
            }catch(IOException){}catch(UnauthorizedAccessException){}
        }
    }
}
