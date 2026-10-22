using System;
using System.Collections.Generic;
using LowTube;
public static class PlaybackSelectionTests {
 static int count;
 static void Check(bool ok,string name){if(!ok)throw new Exception(name);count++;Console.WriteLine("PASS: "+name);}
 static Dictionary<string,object> Video(string id,int height,bool combined=false,string codec="avc1",int fps=30){return new Dictionary<string,object>{{"format_id",id},{"height",height},{"vcodec",codec},{"acodec",combined?"aac":"none"},{"fps",fps},{"url","https://example.invalid/"+id}};}
 static Dictionary<string,object> Root(params Dictionary<string,object>[] items){return new Dictionary<string,object>{{"formats",items}};}
 static string Pick(Dictionary<string,object> root,Settings settings){return Json.Str(FormatPicker.Select(root,settings,false),"format_id");}
 public static int Main(){try{
  L.Set("en");var audio=new Dictionary<string,object>{{"format_id","a"},{"vcodec","none"},{"acodec","aac"},{"ext","m4a"},{"url","https://example.invalid/a"}};
  var root=Root(audio,Video("1080",1080),Video("360",360),Video("720",720),Video("480",480));
  var settings=new Settings{Height=1440,Fps30=true,H264=true};
  Check(Pick(root,settings)=="1080+a","unsupported 1440p chooses nearest lower 1080p despite format order");
  settings.Height=720;Check(Pick(root,settings)=="720+a","exact available resolution wins");
  settings.Height=2160;Check(Pick(root,settings)=="1080+a","unsupported 2160p falls back without exceeding available quality");
  settings.Height=1080;
  Check(Pick(Root(audio,Video("480",480),Video("720",720,true)),settings)=="720","higher combined stream beats lower separate video");
  Check(Pick(Root(audio,Video("720s",720),Video("720c",720,true)),settings)=="720s+a","same-height separate stream keeps separate-audio preference");
  Check(Pick(Root(Video("1080silent",1080),Video("360c",360,true)),settings)=="360c","without audio track fallback selects a playable combined stream");
  Check(Pick(Root(audio,Video("480",480),Video("720fps",720,false,"avc1",60),Video("1080av1",1080,false,"av01")),settings)=="480+a","lower fallback preserves H264 and 30 FPS caps");
  settings.H264=false;Check(Pick(Root(audio,Video("480",480),Video("1080av1",1080,false,"av01")),settings)=="1080av1+a","codec preference is retained when H264 limit is disabled");
  settings.H264=true;settings.Fps30=false;Check(Pick(Root(audio,Video("480",480),Video("720fps",720,false,"avc1",60)),settings)=="720fps+a","60 FPS remains available when cap is disabled");
  settings.Height=240;bool rejected=false;try{Pick(root,settings);}catch(InvalidOperationException){rejected=true;}Check(rejected,"no lower stream fails visibly rather than raising resolution");
  settings.Height=1080;var invalid=Video("invalid",1080);invalid["height"]=Double.NaN;
  var drm=Video("drm",1080);drm["has_drm"]=true;
  Check(Pick(Root(audio,Video("360",360),invalid,drm,Video("zero",0)),settings)=="360+a","invalid heights and DRM cannot displace lower playable stream");
  Check(settings.Height==1080 && settings.H264 && !settings.Fps30 && !root.ContainsKey("requested_formats"),"fallback preserves saved settings and cached metadata");
  Console.WriteLine("TOTAL: "+count+" playback selection checks passed");return 0;
 }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
