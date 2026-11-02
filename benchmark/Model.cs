using System;
using System.Collections.Generic;
using System.Linq;
using System.Globalization;
namespace LowTube {
 public sealed class BenchCase {
  public string Name;public Settings Options;
  public BenchCase(string name,Settings options){Name=name;Options=options;}
 }
 public static class BenchPlan {
  public static Settings Clone(Settings s){return Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(s));}
  public static List<BenchCase> Base(Settings user){
   var result=new List<BenchCase>();
   var clean=new Settings{Equalizer=new EqualizerSettings(),Height=1080,H264=false,Fps30=false,PlayerOnTop=false,SafeSeeking=false};
   foreach(string dec in new[]{"no","dxva2","dxva2-copy","d3d11va","d3d11va-copy","nvdec","nvdec-copy","auto","auto-copy"}){
    foreach(string renderer in new[]{"d3d11","opengl"}){
     if(Rules.CompatibleRenderer(dec,renderer)!=renderer)continue;
     var s=Clone(clean);s.Decoder=dec;s.Renderer=renderer;result.Add(new BenchCase(dec+" / "+renderer,s));
    }
   }
   var current=Clone(user);current.Equalizer=new EqualizerSettings();current.PlayerOnTop=false;current.PlayerBorderless=false;current.SafeSeeking=false;
   result.Insert(0,new BenchCase("Current LowTube settings (EQ disabled)",current));return result;
  }
  public static List<BenchCase> Advanced(Settings source){
   var result=new List<BenchCase>();
   Action<string,Action<Settings>> add=delegate(string name,Action<Settings> change){var s=Clone(source);change(s);result.Add(new BenchCase(name,s));};
   if(source.Renderer=="d3d11"){
    add("D3D11 flip OFF",s=>s.D3D11Flip=false);add("D3D11 flip ON",s=>s.D3D11Flip=true);
    if(source.Decoder!="no"){add("D3D11 zero-copy OFF",s=>s.D3D11ZeroCopy=false);add("D3D11 zero-copy ON",s=>s.D3D11ZeroCopy=true);}
   }
   foreach(int n in new[]{0,1,2,4,8,16}){int v=n;add("CPU decoder threads="+n,s=>s.DecodeThreads=v);}
   foreach(string x in PlayerTuning.Scales){string v=x;add("Scaling="+x,s=>s.VideoScale=v);}
   foreach(string x in PlayerTuning.Drops){string v=x;add("Frame drop="+x,s=>s.FrameDrop=v);}
   foreach(string x in PlayerTuning.Syncs){string v=x;add("Synchronization="+x,s=>s.VideoSync=v);}
   add("Interpolation ON + display-resample",s=>{s.VideoInterpolation=true;s.VideoSync="display-resample";});
   add("Debanding ON",s=>s.VideoDeband=true);add("Deinterlacing ON",s=>s.VideoDeinterlace=true);
   // Test playback-speed pitch processing at the same 1x speed: its effect may be nil.
   add("Audio pitch correction OFF (1x)",s=>s.AudioPitchCorrection=false);
   var seen=new HashSet<string>{PlayerTuning.Signature(source)};
   return result.Where(c=>seen.Add(PlayerTuning.Signature(c.Options))).ToList();
  }
  public static string FormatKey(Dictionary<string,object> f){return String.Join("|",new[]{NormalizeCodec(Json.Str(f,"vcodec")),Json.Str(f,"width"),Json.Str(f,"height"),Json.Str(f,"fps"),Json.Str(f,"dynamic_range"),Json.Str(f,"acodec")=="none"?"separate audio":"combined audio"});}
  public static string NormalizeCodec(string codec){
   // Only profile 0 / 8-bit VP9 is equivalent to yt-dlp's short vp9 spelling.
   if(codec=="vp9" || (codec.StartsWith("vp09.00.",StringComparison.Ordinal) && codec.Split('.').Length>=4 && codec.Split('.')[3]=="08"))return "vp9-profile0-8bit";
   return codec;
  }
  public static string NormalizeKey(string key){var parts=key.Split('|');if(parts.Length>0)parts[0]=NormalizeCodec(parts[0]);return String.Join("|",parts);}
  public static bool EligibleFormat(Dictionary<string,object> f){return VideoFormat(f) && Rules.DownloadTier(f)>=480;}
  public static bool VideoFormat(Dictionary<string,object> f){string c=Json.Str(f,"vcodec");return c!="" && c!="none" && c!="images" && Rules.Number(f,"height")>0 && Json.Str(f,"url")!="" && !Object.Equals(Get(f,"has_drm"),true);}
  public static object Get(Dictionary<string,object> d,string key){object v;return d.TryGetValue(key,out v)?v:null;}
  public static string Status(string requested,string actual){
   if(String.IsNullOrEmpty(actual) || actual=="unknown")return "UNKNOWN";
   if(requested=="no")return actual=="no"?"SOFTWARE":"UNEXPECTED_HARDWARE";
   if(requested=="auto" || requested=="auto-copy")return actual=="no"?"AUTO_SOFTWARE":"AUTO_HARDWARE";
   return requested==actual?"HARDWARE":"FALLBACK (requested "+requested+", actual "+actual+")";
  }
 }
}
