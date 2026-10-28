using System;
using System.Collections.Generic;
using System.Linq;
namespace LowTube {
 public static class PlayerTuning {
  public static readonly string[] Scales={"bilinear","bicubic","lanczos"},Syncs={"audio","display-resample","display-vdrop"},Drops={"vo","no","decoder","decoder+vo"};
  public static string Pick(string s,string[] allowed,string fallback){return allowed.Contains(s)?s:fallback;}
  public static string ProcessKey(Settings s){return Rules.CompatibleRenderer(s.Decoder,s.Renderer)+(Rules.CompatibleRenderer(s.Decoder,s.Renderer)=="d3d11"?"/flip="+s.D3D11Flip:"");}
  public static List<string> Arguments(Settings s){return new List<string>{
   "--vd-lavc-threads="+Math.Max(0,Math.Min(16,s.DecodeThreads)),
   "--scale="+Pick(s.VideoScale,Scales,"bilinear"),"--cscale="+Pick(s.VideoScale,Scales,"bilinear"),"--dscale="+Pick(s.VideoScale,Scales,"bilinear"),
   "--video-sync="+Pick(s.VideoSync,Syncs,"audio"),"--framedrop="+Pick(s.FrameDrop,Drops,"vo"),
   "--deband="+(s.VideoDeband?"yes":"no"),"--interpolation="+(s.VideoInterpolation?"yes":"no"),"--deinterlace="+(s.VideoDeinterlace?"yes":"no"),
   "--audio-pitch-correction="+(s.AudioPitchCorrection?"yes":"no"),"--d3d11va-zero-copy="+(s.D3D11ZeroCopy?"yes":"no")};}
  public static string Signature(Settings s){return ProcessKey(s)+String.Join("|",Arguments(s));}
 }
}
