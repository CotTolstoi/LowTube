using System;
using System.Collections.Generic;
using System.Linq;
namespace LowTube {
    // Choose the nearest available height below the cap; yt-dlp order breaks ties.
    public static class FormatPicker {
        static double Number(Dictionary<string,object> f,string key,double missing){double n;return Double.TryParse(Json.Str(f,key),System.Globalization.NumberStyles.Any,System.Globalization.CultureInfo.InvariantCulture,out n) && !Double.IsNaN(n) && !Double.IsInfinity(n)?n:missing;}
        static bool Usable(Dictionary<string,object> f){
            Uri u;return !String.Equals(Json.Str(f,"has_drm"),"True",StringComparison.OrdinalIgnoreCase) && Uri.TryCreate(Json.Str(f,"url"),UriKind.Absolute,out u) && (u.Scheme=="https" || u.Scheme=="http") && String.IsNullOrEmpty(u.UserInfo);
        }
        static bool Audio(Dictionary<string,object> f){string codec=Json.Str(f,"acodec");return codec.Length>0 && codec!="none";}
        static bool Video(Dictionary<string,object> f){string codec=Json.Str(f,"vcodec");return codec.Length>0 && codec!="none" && codec!="images";}
        public static Dictionary<string,object> Select(Dictionary<string,object> root,Settings settings,bool audio){
            var all=Json.Items(root,"formats").Where(Usable).Reverse().ToList();
            if(all.Count==0 && Usable(root))all.Add(root);
            var audios=all.Where(f=>!Video(f) && Audio(f)).ToList();
            var sound=audios.FirstOrDefault(f=>Json.Str(f,"ext")=="m4a")??audios.FirstOrDefault();
            var result=new Dictionary<string,object>(root);result.Remove("requested_downloads");
            if(audio){if(sound==null)sound=all.Where(Audio).OrderBy(f=>Number(f,"tbr",Double.MaxValue)).ThenBy(f=>Number(f,"height",Double.MaxValue)).FirstOrDefault();if(sound==null)throw new InvalidOperationException(L.T("A separate audio stream is unavailable."));result["requested_formats"]=new object[]{sound};return result;}
            Rules.Filter(settings.Height,settings.Fps30); // validates cap
            var candidates=all.Where(f=>Video(f) && Number(f,"height",0)>0 && Number(f,"height",Double.MaxValue)<=settings.Height && (!settings.Fps30 || Number(f,"fps",0)<=30) && (!settings.H264 || Json.Str(f,"vcodec").StartsWith("avc1",StringComparison.Ordinal)) && (Audio(f) || sound!=null))
                .OrderByDescending(f=>Number(f,"height",0)).ThenBy(f=>Audio(f)?1:0).ToList();
            var video=candidates.FirstOrDefault();
            object[] streams;
            if(video==null)throw new InvalidOperationException(L.T("No available stream fits the height, FPS and codec limits. Open Available formats; your limits were not changed."));
            if(!Audio(video))streams=new object[]{video,sound};else streams=new object[]{video};
            result["requested_formats"]=streams;result["format_id"]=String.Join("+",streams.Cast<Dictionary<string,object>>().Select(f=>Json.Str(f,"format_id")));
            foreach(string key in new[]{"width","height","fps","vcodec","acodec","url","ext"}){object value;if(video.TryGetValue(key,out value))result[key]=value;}
            return result;
        }
    }
    public sealed class FormatCache {
        sealed class Entry {public string Key;public DateTime Time;public int Bytes;public Dictionary<string,object> Root;}
        readonly List<Entry> items=new List<Entry>();readonly object gate=new object();
        public void Clear(){lock(gate)items.Clear();}
        public void Remove(string key){lock(gate)items.RemoveAll(x=>x.Key==key);}
        public Dictionary<string,object> Get(string key){lock(gate){items.RemoveAll(x=>DateTime.UtcNow-x.Time>=TimeSpan.FromMinutes(3));var hit=items.FirstOrDefault(x=>x.Key==key);return hit==null?null:hit.Root;}}
        public void Put(string key,Dictionary<string,object> root,int bytes){
            if(bytes>4*1024*1024 || String.Equals(Json.Str(root,"is_live"),"true",StringComparison.OrdinalIgnoreCase) || Json.Str(root,"live_status")=="is_live")return;
            lock(gate){items.RemoveAll(x=>x.Key==key || DateTime.UtcNow-x.Time>=TimeSpan.FromMinutes(3));while(items.Count>=4 || (items.Count>0 && items.Sum(x=>x.Bytes)+bytes>8*1024*1024))items.RemoveAt(0);items.Add(new Entry{Key=key,Root=root,Bytes=bytes,Time=DateTime.UtcNow});}
        }
    }
}
