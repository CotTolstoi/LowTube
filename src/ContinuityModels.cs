using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
namespace LowTube {
    public sealed class WatchedVideo { public string Key="";public double Position,Duration;public bool Complete;public long Updated; }
    public static class WatchedRules {
        public static double Fraction(WatchedVideo value){return value==null?0:value.Complete?1:value.Duration>0?Math.Max(0,Math.Min(1,value.Position/value.Duration)):0;}
        public static void Record(Dictionary<string,WatchedVideo> values,string url,double position,double duration,bool live,bool complete=false){
            string key=ResumeRules.Key(url);if(live || key.Length==0 || Double.IsNaN(position) || Double.IsInfinity(position) || position<0 || Double.IsNaN(duration) || Double.IsInfinity(duration))return;
            WatchedVideo previous;values.TryGetValue(key,out previous);
            values[key]=new WatchedVideo{Key=key,Position=position,Duration=duration>0?duration:previous==null?0:previous.Duration,Complete=complete,Updated=DateTime.UtcNow.Ticks};
            if(values.Count>5000)values.Remove(values.Values.OrderBy(x=>x.Updated).First().Key);
        }
    }
    public sealed class LocalPlaylist { public string Id=Guid.NewGuid().ToString("N"),Name="";public List<Video> Items=new List<Video>(); }
    public static class LocalPlaylistRules {
        public static string Name(string name){name=(name??"").Trim();if(name.Length==0 || name.Length>100 || name.Any(Char.IsControl))throw new ArgumentException(L.T("Use a playlist name of 1–100 characters."));return name;}
        public static List<LocalPlaylist> Clean(IEnumerable<LocalPlaylist> source){
            var result=new List<LocalPlaylist>();foreach(var p in (source??new List<LocalPlaylist>()).Where(x=>x!=null).Take(200)){
                try{p.Name=Name(p.Name);}catch(ArgumentException){continue;}
                if(String.IsNullOrEmpty(p.Id) || result.Any(x=>x.Id==p.Id))p.Id=Guid.NewGuid().ToString("N");
                p.Items=(p.Items??new List<Video>()).Where(x=>x!=null && ResumeRules.Key(x.Url).Length>0).Take(1000).ToList();result.Add(p);
            }return result;
        }
    }
    public sealed class RecoveryBudget {
        public int Attempts {get;private set;}
        public void Reset(){Attempts=0;}
        public bool Take(){if(Attempts>=2)return false;Attempts++;return true;}
        public static bool Premature(double position,double duration,bool live){return !live && position>=0 && duration>30 && duration-position>15;}
    }
    // Only explicit telemetry enters this report; never include raw extractor output,
    // settings.json, browser profiles, URLs, titles, cookies or authentication data.
    public static class DiagnosticReport {
        public static string Atom(object value){string s=Convert.ToString(value,CultureInfo.InvariantCulture)??"";return s.Length<=120 && Regex.IsMatch(s,@"^[A-Za-z0-9 ._:+()=,%\-]*$")?s:"unavailable";}
        public static string Build(Settings s,Dictionary<string,object> snapshot,IEnumerable<string> journal,int queue,int downloads,string versions){
            var b=new StringBuilder();b.AppendLine("LowTube 0.11.0 diagnostics (no URLs, titles, cookies or personal paths)");
            b.AppendLine("UTC: "+DateTime.UtcNow.ToString("O"));b.AppendLine("OS: "+Atom(Environment.OSVersion.Version));
            b.AppendLine("64-bit OS/process: "+Environment.Is64BitOperatingSystem+" / "+Environment.Is64BitProcess);b.AppendLine("CLR: "+Environment.Version+"; logical CPUs: "+Environment.ProcessorCount);
            b.AppendLine("Managed memory MiB: "+(GC.GetTotalMemory(false)/1048576.0).ToString("0.0",CultureInfo.InvariantCulture));
            b.AppendLine("\r\nSettings (allowlist)");
            foreach(string name in new[]{"Height","H264","Fps30","Decoder","Renderer","BufferSeconds","BufferMiB","BackBufferMiB","BufferStartSeconds","SafeSeeking","D3D11Flip","D3D11ZeroCopy","DecodeThreads","VideoScale","VideoSync","FrameDrop","AutoRecover","PreloadNext","AutoNext","DownloadLimitKiB"}){
                var field=typeof(Settings).GetField(name);if(field!=null)b.AppendLine(name+": "+Atom(field.GetValue(s)));
            }
            b.AppendLine("Queue entries: "+queue+"; download jobs: "+downloads);
            b.AppendLine("\r\nPlayback snapshot\r\n"+PlaybackTrace.Filter(Json.Serializer().Serialize(snapshot)));
            b.AppendLine("\r\nComponents\r\n"+versions);
            b.AppendLine("\r\nContinuity events (current run, newest 200)");foreach(var line in journal.Take(200))b.AppendLine(Atom(line));
            b.AppendLine("\r\nRaw logs and account data are intentionally excluded.");return b.ToString();
        }
    }
}
