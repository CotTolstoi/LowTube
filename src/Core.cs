using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;


namespace LowTube {
    public static class JavaScriptRuntime {
        public static string Normalize(string value) {
            switch((value??"").Trim().ToLowerInvariant()) {
                case "node22": return "node22";
                case "deno": return "deno";
                case "quickjs-classic": return "quickjs-classic";
                case "quickjs": return "quickjs";
                default: return "node22";
            }
        }
        public static string Profile { get { return Normalize(Environment.GetEnvironmentVariable("LOWTUBE_JS_RUNTIME")); } }
        public static string Argument {
            get {
                string profile=Profile;
                if(profile=="quickjs")return "quickjs:"+Paths.Tool("qjs");
                string exe=profile=="node22"?"node.exe":profile=="deno"?"deno.exe":"qjs.exe";
                return (profile=="node22"?"node":profile=="deno"?"deno":"quickjs")+":"+Path.Combine(Paths.Root,"tools","js",profile,exe);
            }
        }
    }

    public class Settings {
        public string Language = "en";
        public string Theme = "light";
        public int FeedPreset = 1;
        public int PageSize = 25;
        public EqualizerSettings Equalizer=new EqualizerSettings();
        public string DiscoveryLanguage = "any";
        public string DiscoveryTopic = "science and technology";
        public int Height = 720;
        public bool H264 = true;
        public bool Fps30 = true;
        public bool DownloadFps30 = false;
        public int DownloadLimitKiB = 0;
        public bool AutoRecover = true, PreloadNext = true;
        public int BufferSeconds = 60;
        public int BufferMiB = 0; // 0 migrates the old preset memory limit
        public int BackBufferMiB = 4;
        public int BufferStartSeconds = 3;
        public bool SafeSeeking = true;
        public bool PlayerOnTop = false;
        public bool AboveFullscreen = false, ResumePlayback = true, AutoNext = true;
        public bool PlayerBorderless = false;
        public bool CardView = true;
        public string Decoder = "auto";
        public string Renderer = "d3d11";
        public bool D3D11Flip=false,D3D11ZeroCopy=false,VideoDeband=false,VideoInterpolation=false,VideoDeinterlace=false,AudioPitchCorrection=true;
        public int DecodeThreads=2;
        public string VideoScale="bilinear",VideoSync="audio",FrameDrop="vo";

        public string Auth = "none";
        public string FirefoxProfile = "";
        public string BrowserName = "firefox";
        public string BrowserProfile = "";
        public string CookiesFile = "";
        public bool AuthMedia = false;
        public string DownloadFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "LowTube");
        public string DownloadFormat = "MP4 (H.264 + AAC)";
        public int DownloadHeight = 720;
        public bool DownloadH264 = true;
        public string AudioDownloadFormat = "M4A";
        public int AudioBitrate = 192;
    }
    public class DownloadSelection {
        public string Kind, FormatId;
        public bool Combined;
        public int Height, Bitrate=192;
        public bool AudioOnly { get { return Kind=="M4A" || Kind=="MP3" || Kind=="Opus"; } }
        public string Selector() {
            if(!Regex.IsMatch(FormatId??"",@"^[A-Za-z0-9_.-]+$")) throw new ArgumentException(L.T("Invalid format ID."));
            if(AudioOnly || Combined) return FormatId;
            if(Kind=="MP4") return FormatId+"+ba[ext=m4a]";
            if(Kind=="WebM") return FormatId+"+ba[ext=webm]";
            if(Kind=="MKV") return FormatId+"+ba[ext=m4a]/"+FormatId+"+ba";
            throw new ArgumentException(L.T("Unknown download format."));
        }
    }
    public class Video {
        public string Id = "";
        public string Title = "";
        public string Channel = "";
        public string ChannelId = "";
        public string Published = "";
        public string Kind = "video";
        public string LiveStatus = "";
        public string Url = "";
        public string Duration = "";
        public string Date = "";
        public static Video From(Dictionary<string, object> d) {
            string id = Json.Str(d, "id");
            string kind=ContentFeeds.Kind(d);
            string url = Json.Str(d, "webpage_url");
            if (String.IsNullOrEmpty(url)) url = Json.Str(d, "url");
            if (kind!="playlist" && Regex.IsMatch(id, @"^[A-Za-z0-9_-]{11}$")) url = "https://www.youtube.com/watch?v=" + id;
            double seconds;
            string duration = Double.TryParse(Json.Str(d, "duration"), System.Globalization.NumberStyles.Any,
                System.Globalization.CultureInfo.InvariantCulture, out seconds) && seconds > 0
                ? TimeSpan.FromSeconds(seconds).ToString(seconds >= 3600 ? @"h\:mm\:ss" : @"m\:ss") : "—";
            var video=new Video { Kind=kind,LiveStatus=Json.Str(d,"live_status"), Id=id, Title=Json.Str(d,"title"), Channel=Json.Str(d,"channel",Json.Str(d,"uploader")), ChannelId=Json.Str(d,"channel_id"), Url=url, Duration=duration };
            FeedCache.SetDate(video,d);return video;
        }
    }
    public static class Json {
        public static JsonCodec Serializer() { return new JsonCodec(); }
        public static Dictionary<string, object> Object(string text) { return Serializer().Deserialize<Dictionary<string, object>>(text); }
        public static string Str(Dictionary<string, object> d, string key, string fallback="") {
            object value; return d != null && d.TryGetValue(key, out value) && value != null ? Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture) : fallback;
        }
        public static IEnumerable<Dictionary<string, object>> Items(Dictionary<string, object> d, string key) {
            object value;
            if (d == null || !d.TryGetValue(key, out value) || !(value is IEnumerable)) yield break;
            foreach(object o in (IEnumerable)value) { var item=o as Dictionary<string, object>; if(item!=null) yield return item; }
        }
        public static void Save(string path, object value) {
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string temp=path+".tmp";
            File.WriteAllText(temp, Serializer().Serialize(value), new UTF8Encoding(false));
            if(File.Exists(path)) File.Replace(temp,path,null); else File.Move(temp,path);
        }
        public static T Load<T>(string path, T fallback) {
            try { return File.Exists(path) && new FileInfo(path).Length<=32*1024*1024 ? Serializer().Deserialize<T>(File.ReadAllText(path,Encoding.UTF8)) : fallback; }
            catch(IOException) { return fallback; }
            catch(ArgumentException) { return fallback; }
            catch(InvalidOperationException) { return fallback; }
        }
    }
    public static class Paths {
        public static readonly string Root=AppDomain.CurrentDomain.BaseDirectory;
        public static readonly string Data=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"LowTube");
        public static string Tool(string name) {
            string direct=Path.Combine(Root,"tools",name+".exe");
            string nested=Path.Combine(Root,"tools",name=="ffprobe"?"ffmpeg":name,name+".exe");
            if(name=="yt-dlp" && File.Exists(nested))return nested;
            if(File.Exists(direct)) return direct;
            if(File.Exists(nested)) return nested;
            throw new FileNotFoundException(L.T("Not found: ")+name+L.T(".exe. Run SETUP.cmd in the LowTube folder."));
        }
    }
    public static class Rules {
        public static string ThumbnailUrl(string id) { return Regex.IsMatch(id??"",@"^[A-Za-z0-9_-]{11}$")?"https://i.ytimg.com/vi/"+id+"/hqdefault.jpg":""; }
        public static string KindCode(string value) { return new[]{"MP4","MKV","WebM","M4A","MP3","Opus"}.FirstOrDefault(x=>(value??"").StartsWith(x,StringComparison.Ordinal))??"MP4"; }
        public static double Number(Dictionary<string,object> d,string key) { double n;return Double.TryParse(Json.Str(d,key),System.Globalization.NumberStyles.Any,System.Globalization.CultureInfo.InvariantCulture,out n)?n:0; }
        public static bool Downloadable(Dictionary<string,object> f,string kind,bool fps30,bool h264) {
            if(Json.Str(f,"has_drm").Equals("true",StringComparison.OrdinalIgnoreCase) || String.IsNullOrEmpty(Json.Str(f,"url")))return false;
            if(!Regex.IsMatch(Json.Str(f,"format_id"),@"^[A-Za-z0-9_.-]+$"))return false;
            string vc=Json.Str(f,"vcodec"),ac=Json.Str(f,"acodec"),ext=Json.Str(f,"ext");
            if(kind=="M4A" || kind=="MP3" || kind=="Opus") return vc=="none" && ac.Length>0 && ac!="none" &&
                (kind=="MP3" || (kind=="M4A" && ext=="m4a") || (kind=="Opus" && ac.StartsWith("opus",StringComparison.Ordinal)));
            if(vc.Length==0 || vc=="none" || vc=="images" || ac.Length==0 || Number(f,"height")<=0 || (fps30 && Number(f,"fps")>30))return false;
            if(kind=="MP4")return ext=="mp4" && (!h264 || vc.StartsWith("avc1",StringComparison.Ordinal)) && (ac=="none" || ac.StartsWith("mp4a",StringComparison.Ordinal));
            if(kind=="WebM")return ext=="webm";
            return kind=="MKV" && (!h264 || vc.StartsWith("avc1",StringComparison.Ordinal));
        }
        public static bool HasDownloadAudio(IEnumerable<Dictionary<string,object>> formats,string kind) {
            return formats.Any(f=>Json.Str(f,"vcodec")=="none" && Json.Str(f,"acodec").Length>0 && Json.Str(f,"acodec")!="none" &&
                Json.Str(f,"url").Length>0 && !Json.Str(f,"has_drm").Equals("true",StringComparison.OrdinalIgnoreCase) &&
                (kind=="MKV" || (kind=="MP4" && Json.Str(f,"ext")=="m4a") || (kind=="WebM" && Json.Str(f,"ext")=="webm")));
        }
        public static readonly string[] Decoders={"auto","auto-copy","d3d11va","d3d11va-copy","dxva2","dxva2-copy","nvdec","nvdec-copy","no"};
        public static string CompatibleRenderer(string decoder,string renderer) {
            if(decoder=="dxva2" || decoder=="d3d11va")return "d3d11";
            if(decoder=="nvdec")return "opengl";
            return renderer;
        }
        public static int DownloadTier(Dictionary<string,object> f) {
            var note=Regex.Match(Json.Str(f,"format_note"),@"(?:^|\s)(144|240|360|480|720|1080|1440|2160|4320)p");
            int value;if(note.Success && Int32.TryParse(note.Groups[1].Value,out value))return value;
            // Preserve native aspect ratios, including ultrawide streams.
            double size=Math.Max(Number(f,"height"),Number(f,"width")*9.0/16.0);
            foreach(int tier in new[]{4320,2160,1440,1080,720,480,360,240,144})if(size>=tier-2)return tier;
            return (int)Number(f,"height");
        }
        public static readonly string[] Renderers={"d3d11","opengl"};
        public static string YouTubeUrl(string input) {
            string value=(input??"").Trim();
            if(Regex.IsMatch(value,@"^[A-Za-z0-9_-]{11}$")) return "https://www.youtube.com/watch?v="+value;
            Uri uri;
            if(!Uri.TryCreate(value,UriKind.Absolute,out uri) || (uri.Scheme!="https" && uri.Scheme!="http") || !String.IsNullOrEmpty(uri.UserInfo))
                throw new ArgumentException(L.T("Paste a YouTube URL or an 11-character video ID."));
            string host=uri.DnsSafeHost.ToLowerInvariant();
            if(host!="youtu.be" && host!="youtube.com" && !host.EndsWith(".youtube.com",StringComparison.Ordinal))
                throw new ArgumentException(L.T("Only youtube.com and youtu.be links are supported."));
            if(!uri.IsDefaultPort) throw new ArgumentException(L.T("Non-standard ports are not supported."));
            var safe=new UriBuilder(uri) { Scheme="https",Port=-1,Fragment="" };
            return safe.Uri.AbsoluteUri;
        }
        public static string Search(string input, int count) {
            string q=(input??"").Trim();
            if(q.Length==0) throw new ArgumentException(L.T("Enter a video title or URL."));
            if(q.StartsWith("http:",StringComparison.OrdinalIgnoreCase) || q.StartsWith("https:",StringComparison.OrdinalIgnoreCase)) return YouTubeUrl(q);
            return "ytsearch"+count+":"+q;
        }
        public static string Filter(int height, bool fps30) {
            if(!new[]{144,240,360,480,720,1080,1440,2160}.Contains(height)) throw new ArgumentException(L.T("Unsupported quality."));
            return "[height<="+height+"]"+(fps30 ? "[fps<=?30]" : "");
        }
        public static string VideoFormat(int height, bool fps30, bool h264) {
            string f=Filter(height,fps30)+(h264?"[vcodec^=avc1]":"");
            return "bv"+f+"+ba[ext=m4a]/bv"+f+"+ba/b"+f;
        }
        public static string PlaybackFormat(Settings s,bool audio) { return audio?"ba[ext=m4a]/ba":VideoFormat(s.Height,s.Fps30,s.H264); }
        public static int CacheSeconds(Settings s) { return s.BufferSeconds>=5 && s.BufferSeconds<=3600?s.BufferSeconds:60; }
        public static int CacheMiB(Settings s) { if(s.BufferMiB>=16 && s.BufferMiB<=1024)return s.BufferMiB;int seconds=CacheSeconds(s); return seconds==12?32:seconds==120?128:64; }
        public static int BackCacheMiB(Settings s){return s.BackBufferMiB>=0 && s.BackBufferMiB<=512?s.BackBufferMiB:4;}
        public static int StartCacheSeconds(Settings s){return Math.Min(CacheSeconds(s),s.BufferStartSeconds>=1 && s.BufferStartSeconds<=30?s.BufferStartSeconds:3);}
        public static string Dimensions(Dictionary<string,object> f) {
            string w=Json.Str(f,"width"),h=Json.Str(f,"height");
            return w.Length>0 && h.Length>0?w+"×"+h:Json.Str(f,"resolution",L.T("unknown"));
        }
        public static string SelectedFormat(Dictionary<string,object> root) {
            var streams=Json.Items(root,"requested_formats").ToList();
            if(streams.Count==0) streams.Add(root);
            return String.Join(" + ",streams.Select(f=>"ID "+Json.Str(f,"format_id","?")+": "+
                (Json.Str(f,"vcodec")=="none"?L.T(" audio ")+Json.Str(f,"acodec"):Dimensions(f)+" / "+Json.Str(f,"fps","?")+" FPS / "+Json.Str(f,"vcodec"))+
                " ["+Json.Str(f,"format_note",Json.Str(f,"ext"))+"]"));
        }
        // Windows CommandLineToArgvW / CRT quoting. Never invoke a shell for user input.
        public static string Quote(string value) {
            if(value==null) value="";
            var b=new StringBuilder("\""); int slashes=0;
            foreach(char c in value) {
                if(c=='\\') { slashes++; continue; }
                if(c=='\"') { b.Append('\\',slashes*2+1); b.Append(c); slashes=0; continue; }
                b.Append('\\',slashes); slashes=0; b.Append(c);
            }
            b.Append('\\',slashes*2); b.Append('"'); return b.ToString();
        }
        public static string Arguments(IEnumerable<string> values) { return String.Join(" ",values.Select(Quote)); }
        public static string Redact(string value) {
            if(Regex.IsMatch(value??"",@"(?i)(authorization\s*:|cookie\s*:)")) return L.T("[Private headers hidden]");
            return Regex.Replace(value??"",@"https?://[^\s\""<>]+",delegate(Match m) { Uri u; return Uri.TryCreate(m.Value,UriKind.Absolute,out u) ? u.GetLeftPart(UriPartial.Path)+" [URL]" : "[URL]"; });
        }
        public static List<string> DownloadArgs(Settings s,string kind,DownloadSelection selection=null) {
            var a=new List<string>{"--no-playlist","--newline","--progress","--no-overwrites","--windows-filenames","--trim-filenames","160","--concurrent-fragments","1",
                "--paths",s.DownloadFolder,"--output","%(title).96B [%(id)s] [%(format_id)s].%(ext)s",
                "--no-simulate","--print","before_dl:"+L.T("Selected: ")+"%(format_id)s | %(resolution)s | %(fps)s FPS | %(format_note)s"};
            if(s.DownloadLimitKiB>0)a.AddRange(new[]{"--limit-rate",Math.Min(1048576,s.DownloadLimitKiB).ToString(System.Globalization.CultureInfo.InvariantCulture)+"K"});
            string f=Filter(s.Height,s.DownloadFps30);
            if(kind.StartsWith("MP4")) a.AddRange(new[]{"-f","bv[ext=mp4][vcodec^=avc1]"+f+"+ba[ext=m4a]/b[ext=mp4][vcodec^=avc1]"+f,"--merge-output-format","mp4","--remux-video","mp4"});
            else if(kind.StartsWith("MKV")) a.AddRange(new[]{"-f",VideoFormat(s.Height,s.DownloadFps30,s.H264),"--merge-output-format","mkv","--remux-video","mkv"});
            else if(kind.StartsWith("WebM")) a.AddRange(new[]{"-f","bv[ext=webm]"+f+"+ba[ext=webm]/b[ext=webm]"+f,"--merge-output-format","webm","--remux-video","webm"});
            else if(kind.StartsWith("M4A")) a.AddRange(new[]{"-f","ba[ext=m4a]","-x","--audio-format","m4a"});
            else if(kind.StartsWith("MP3")) a.AddRange(new[]{"-f","ba","-x","--audio-format","mp3","--audio-quality","5"});
            else if(kind.StartsWith("Opus")) a.AddRange(new[]{"-f","ba[acodec=opus]","-x","--audio-format","opus"});
            else throw new ArgumentException(L.T("Unknown download format."));
            if(selection!=null) {
                if(selection.Kind!=kind)throw new ArgumentException(L.T("Download format mismatch."));
                a[a.IndexOf("-f")+1]=selection.Selector();
                if(kind=="MP3") {
                    if(!new[]{96,128,192,256,320}.Contains(selection.Bitrate))throw new ArgumentException(L.T("Invalid audio bitrate."));
                    a[a.IndexOf("--audio-quality")+1]=selection.Bitrate+"K";
                    a[a.IndexOf("--output")+1]="%(title).96B [%(id)s] [%(format_id)s-"+selection.Bitrate+"k].%(ext)s";
                }
            }
            return a;
        }
    }
    public class RunResult { public int Code; public string Output; public string Error; }
    public static class Runner {
        public static async Task<RunResult> Run(string exe,IEnumerable<string> args,CancellationToken cancel,int timeoutSeconds,bool capture,Action<string> progress,StartupTrace.Attempt trace=null) {
            cancel.ThrowIfCancellationRequested();
            var runClock=Stopwatch.StartNew();if(trace!=null)trace.Event("yt-dlp process launch requested");
            var psi=new ProcessStartInfo(exe,Rules.Arguments(args)) { UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,
                StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8,WorkingDirectory=Paths.Root };
            using(var p=new Process { StartInfo=psi }) using(var job=new ProcessJob()) {
                await Task.Run(delegate{cancel.ThrowIfCancellationRequested();p.Start();job.Attach(p);});
                if(trace!=null)trace.Event("yt-dlp process created; launch="+runClock.Elapsed.TotalSeconds.ToString("0.000",System.Globalization.CultureInfo.InvariantCulture)+" s");
                using(var monitor=new StartupTrace.ProcessMonitor(job,p.Id,trace))
                using(var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds)))
                using(var linked=CancellationTokenSource.CreateLinkedTokenSource(cancel,timeout.Token))
                    using(linked.Token.Register(delegate { job.Kill(); try { if(!p.HasExited) p.Kill(); } catch(InvalidOperationException) {} catch(System.ComponentModel.Win32Exception) {} })) {
                    Task<string> stdout=Read(p.StandardOutput,capture?32*1024*1024:16000,capture?null:progress,capture,trace,false);
                    Task<string> stderr=Read(p.StandardError,16000,progress,false,trace,true);
                    await Task.Run(delegate { p.WaitForExit(); });
                    string output=await stdout; string error=await stderr;
                    if(trace!=null)trace.Event("yt-dlp process exited; code="+p.ExitCode+"; elapsed="+runClock.Elapsed.TotalSeconds.ToString("0.000",System.Globalization.CultureInfo.InvariantCulture)+" s");
                    cancel.ThrowIfCancellationRequested();
                    if(timeout.IsCancellationRequested) throw new TimeoutException(L.T("The server did not respond in time. Retry or update components."));
                    return new RunResult { Code=p.ExitCode,Output=output,Error=Rules.Redact(error) };
                }
            }
        }
        static async Task<string> Read(StreamReader reader,int max,Action<string> progress,bool strict,StartupTrace.Attempt trace=null,bool stderr=false) {
            var b=new StringBuilder(); char[] buffer=new char[4096]; int count; bool overflow=false;bool first=true;string remainder="";int diagnosticLines=0;
            while((count=await reader.ReadAsync(buffer,0,buffer.Length))>0) {
                string text=new string(buffer,0,count);
                if(trace!=null){
                    if(first){first=false;trace.Event(stderr?"yt-dlp first stderr output":"yt-dlp first metadata output");}
                    if(stderr && diagnosticLines<100){
                        string[] rows=(remainder+text).Split('\n');remainder=rows[rows.Length-1];if(remainder.Length>8192)remainder="";
                        for(int i=0;i<rows.Length-1 && diagnosticLines<100;i++){string row=rows[i].Trim();if(progress!=null && (row.StartsWith("ERROR:") || row.StartsWith("WARNING:")))progress(Rules.Redact(row));string stage=StartupTrace.Stage(row);if(stage!=null){trace.Event(stage);diagnosticLines++;}}
                    }
                }
                if(progress!=null && !(stderr && trace!=null)) progress(Rules.Redact(text));
                if(strict) { if(b.Length+count>max) overflow=true; else if(!overflow) b.Append(text); }
                else { b.Append(text); if(b.Length>max) b.Remove(0,b.Length-max); }
            }
            if(trace!=null && stderr && remainder.Length>0){string stage=StartupTrace.Stage(remainder.Trim());if(stage!=null && diagnosticLines<100)trace.Event(stage);if(progress!=null && (remainder.StartsWith("ERROR:") || remainder.StartsWith("WARNING:")))progress(Rules.Redact(remainder));}
            if(overflow) throw new InvalidDataException(L.T("Response exceeds 32 MB. Request fewer results."));
            return b.ToString();
        }
    }
    public class Backend {
        readonly Settings settings;
        readonly StartupTrace.Attempt trace;
        readonly InnerTubeClient catalogue;
        static readonly FormatCache metadata=new FormatCache();
        public static void ClearMetadataCache(){metadata.Clear();InnerTubeClient.ClearCache();}
        string CacheKey(string url){return String.Join("\n",new[]{url,JavaScriptRuntime.Profile,settings.AuthMedia.ToString(),settings.Auth,settings.FirefoxProfile,settings.BrowserName,settings.BrowserProfile,settings.CookiesFile,SavedSession.Stamp,File.Exists(settings.CookiesFile)?File.GetLastWriteTimeUtc(settings.CookiesFile).Ticks.ToString():""});}
        public void InvalidateStreamCache(string url){metadata.Remove(CacheKey(Rules.YouTubeUrl(url)));}
        public Backend(Settings s,StartupTrace.Attempt attempt=null):this(s,attempt,null){}
        public Backend(Settings s,StartupTrace.Attempt attempt,InnerTubeClient catalogue) { settings=s;trace=attempt;this.catalogue=catalogue; }
        InnerTubeClient Catalogue(bool authenticated,bool discovery=false){return catalogue??InnerTubeClient.For(settings,authenticated,discovery);}
        List<string> Common(bool auth) {
            return ExtractorArguments(settings,auth,Path.GetDirectoryName(Paths.Tool("ffmpeg")),JavaScriptRuntime.Argument);
        }
        static List<string> ExtractorArguments(Settings settings,bool auth,string ffmpegDirectory,string javascript) {
            var a=new List<string>{"--ignore-config","--no-plugin-dirs","--encoding","utf-8","--no-colors","--socket-timeout","25","--retries","2","--extractor-retries","2","--no-js-runtimes","--js-runtimes",javascript,"--ffmpeg-location",ffmpegDirectory};
            if(auth && (settings.Auth=="firefox" || settings.Auth=="browser")) {
                a.AddRange(new[]{"--cookies-from-browser",BrowserSessions.Argument(BrowserSessions.Browser(settings),BrowserSessions.Profile(settings))});
            } else if(auth && settings.Auth=="file") {
                if(!File.Exists(settings.CookiesFile)) throw new FileNotFoundException(L.T("Choose an existing cookies.txt in account settings."));
                a.AddRange(new[]{"--cookies",settings.CookiesFile});
            }
            if(auth && settings.Auth=="saved")a.AddRange(new[]{"--cookies","LOWTUBE_SAVED_SESSION"});
            return a;
        }
        async Task<RunResult> Execute(List<string> a,CancellationToken ct,int timeout,bool capture,Action<string> progress,bool partial=false) {
            var clock=Stopwatch.StartNew();
            RunResult r;
            int cookieIndex=a.IndexOf("LOWTUBE_SAVED_SESSION");
            if(cookieIndex>=0){if(trace!=null)trace.Event("saved session preparation started");using(var lease=new SavedSession.Lease(true)){if(trace!=null)trace.Event("saved session ready");a[cookieIndex]=lease.FilePath;r=await Runner.Run(Paths.Tool("yt-dlp"),a,ct,timeout,capture,progress,trace);}}
            else r=await Runner.Run(Paths.Tool("yt-dlp"),a,ct,timeout,capture,progress,trace);
            StartupTrace.Mark("yt-dlp operation: "+clock.Elapsed.TotalSeconds.ToString("0.0",System.Globalization.CultureInfo.InvariantCulture)+" s");
            if(progress!=null)progress(L.T("yt-dlp operation: {0:0.0} s",clock.Elapsed.TotalSeconds));
            if(r.Code!=0 && (!partial || String.IsNullOrWhiteSpace(r.Output))) throw new InvalidOperationException(String.IsNullOrWhiteSpace(r.Error)?L.T("yt-dlp exited with code ")+r.Code:r.Error);
            return r;
        }
        public async Task<List<Video>> Browse(string target,int page,bool account,CancellationToken ct,Action<string> progress,bool guestOnly=false,int pageSize=0) {
            if(account && settings.Auth=="none") throw new InvalidOperationException(L.T("Connect a browser session in Account first."));
            int size=Paging.Size(pageSize==0?settings.PageSize:pageSize);
            return await Catalogue(!guestOnly && settings.Auth!="none" && (account || settings.AuthMedia),guestOnly).Browse(target,page,size,account,ct,progress,page==0);
        }
        public async Task<PlaylistBatch> PlaylistPage(string target,int page,int size,bool account,CancellationToken ct,Action<string> progress){
            return await Catalogue(settings.Auth!="none" && (account || settings.AuthMedia)).Playlist(Rules.YouTubeUrl(target),page,size,account,ct,progress);
        }
        public async Task<List<LocalChannel>> SessionChannels(CancellationToken ct,Action<string> progress){
            return await Catalogue(true).SessionChannels(ct,progress);
        }
        public async Task<Dictionary<string,List<Video>>> ChannelBatch(List<LocalChannel> channels,string kind,int page,bool account,CancellationToken ct,Action<string> progress,int count=25){
            count=Paging.BatchCount(count);var result=new Dictionary<string,List<Video>>();
            foreach(var channel in channels){
                ct.ThrowIfCancellationRequested();
                try{
                    // Channel cache pages are fixed at 25, independently of UI size.
                    var rows=new List<Video>();int remaining=count,offset=Math.Max(0,page);
                    while(remaining>0){int take=Math.Min(25,remaining);var part=await Browse(ContentFeeds.ChannelTarget(channel.Id,kind),offset,account,ct,progress,!account,25);rows.AddRange(part);if(part.Count<25)break;remaining-=take;offset++;}
                    foreach(var item in rows){item.ChannelId=channel.Id;item.Channel=channel.Title;item.Kind=kind;}result[channel.Id]=rows;
                }catch(OperationCanceledException){throw;}catch(InnerTubeException e){if(progress!=null)progress(L.T("Channel content unavailable: {0}",channel.Title)+" "+e.Message);if(e.StopsBatch)break;}catch(TimeoutException e){if(progress!=null)progress(e.Message);break;}catch(Exception e){if(progress!=null)progress(L.T("Channel content unavailable: {0}",channel.Title)+" "+Rules.Redact(e.Message));}
            }
            return result;
        }
        public static Dictionary<string,List<Video>> ParseChannelBatch(string output,List<LocalChannel> channels,string kind){
            var result=new Dictionary<string,List<Video>>();
            foreach(string line in output.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){
                Dictionary<string,object> root;try{root=Json.Object(line);}catch(ArgumentException){continue;}
                if(root==null)continue;string id=Json.Str(root,"channel_id",Json.Str(root,"id"));
                var channel=channels.FirstOrDefault(c=>c.Id==id);
                if(channel==null){string url=Json.Str(root,"webpage_url");channel=channels.FirstOrDefault(c=>url==ContentFeeds.ChannelTarget(c.Id,kind));}
                if(channel==null || !root.ContainsKey("entries"))continue;
                var rows=new List<Video>();foreach(var d in Json.Items(root,"entries")){var v=Video.From(d);try{v.Url=Rules.YouTubeUrl(v.Url);}catch(ArgumentException){continue;}v.ChannelId=channel.Id;v.Channel=channel.Title;v.Kind=kind;rows.Add(v);}result[channel.Id]=rows;
            }
            return result;
        }
        public async Task<List<Video>> ChannelContent(List<LocalChannel> channels,string kind,int page,bool account,CancellationToken ct,Action<string> progress){
            using(var gate=new SemaphoreSlim(2)){
                var tasks=channels.Select(async channel=>{await gate.WaitAsync(ct);try{
                    var rows=await Browse(ContentFeeds.ChannelTarget(channel.Id,kind),page,account,ct,progress,!account);
                    foreach(var item in rows){item.ChannelId=channel.Id;item.Channel=channel.Title;if(kind!="all")item.Kind=kind;}
                    return rows;
                }catch(OperationCanceledException){throw;}catch(Exception){if(progress!=null)progress(L.T("Channel content unavailable: {0}",channel.Title));return new List<Video>();}finally{gate.Release();}}).ToArray();
                var result=await Task.WhenAll(tasks);ct.ThrowIfCancellationRequested();return result.SelectMany(x=>x).GroupBy(x=>x.Url).Select(x=>x.First()).ToList();
            }
        }
        // Keep the five-argument method for benchmark builds compiled against 0.10.
        // An optional parameter on the six-argument method changes its CLR signature.
        public Task<Dictionary<string,object>> Resolve(string url,bool audio,CancellationToken ct,Action<string> progress,bool formatsOnly=false) {
            return Resolve(url,audio,ct,progress,formatsOnly,false);
        }
        public async Task<Dictionary<string,object>> Resolve(string url,bool audio,CancellationToken ct,Action<string> progress,bool formatsOnly,bool fresh) {
            ct.ThrowIfCancellationRequested();string key=CacheKey(Rules.YouTubeUrl(url));
            if(fresh)metadata.Remove(key);
            var root=fresh?null:metadata.Get(key);
            if(root!=null){if(trace!=null)trace.Event("format cache HIT (no yt-dlp process)");if(progress!=null)progress(L.T("Using recent stream metadata (memory cache)."));}
            else{
                if(trace!=null)trace.Event("format cache MISS (absent, expired or evicted)");
                var a=Common(settings.AuthMedia);if(trace!=null)a.Add("--verbose");
                a.AddRange(new[]{"--no-playlist","--dump-single-json","--skip-download","--ignore-no-formats-error","--",Rules.YouTubeUrl(url)});
                var r=await Execute(a,ct,240,true,progress);if(trace!=null)trace.Event("metadata parsing started");root=await Task.Run(()=>Json.Object(r.Output));ct.ThrowIfCancellationRequested();
                if(Json.Str(root,"live_status")=="is_upcoming")throw new InvalidOperationException(L.T("This live stream has not started yet."));if(trace!=null)trace.Event("metadata parsing complete");
                metadata.Put(key,root,Encoding.UTF8.GetByteCount(r.Output));
            }
            var selected=formatsOnly?root:FormatPicker.Select(root,settings,audio);if(trace!=null){trace.Event("format selection complete; height="+Rules.Number(selected,"height")+"; fps="+Rules.Number(selected,"fps"));foreach(var stream in Json.Items(selected,"requested_formats"))trace.Event("selected stream: "+StreamOpenRecovery.FormatLabel(stream));}return selected;
        }

        public async Task Download(string url,string kind,CancellationToken ct,Action<string> progress,DownloadSelection selection=null) {
            Directory.CreateDirectory(settings.DownloadFolder);
            var a=Common(settings.AuthMedia); a.AddRange(Rules.DownloadArgs(settings,kind,selection)); a.Add("--"); a.Add(Rules.YouTubeUrl(url));
            await Execute(a,ct,86400,false,progress);
        }
        public async Task<LocalChannel> ResolveChannel(string input,CancellationToken ct,Action<string> progress) {
            return await Catalogue(false).ResolveChannel(input,ct,progress);
        }
        public Task<Dictionary<string,object>> VideoMetadata(string url,CancellationToken ct,Action<string> progress){return Catalogue(settings.Auth!="none" && settings.AuthMedia).Metadata(url,ct,progress);}
    }
}
