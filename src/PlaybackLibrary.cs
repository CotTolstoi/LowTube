using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
namespace LowTube {
    public sealed class ResumePoint { public string Key="",Title="";public double Position,Duration;public long Updated; }
    public static class ResumeRules {
        public static string Key(string url){
            Uri uri;if(!Uri.TryCreate(url,UriKind.Absolute,out uri))return "";
            string host=uri.Host.ToLowerInvariant();string id="";
            if(host=="youtu.be")id=uri.AbsolutePath.Trim('/');
            else if(host=="youtube.com" || host.EndsWith(".youtube.com",StringComparison.Ordinal)){
                var m=Regex.Match(uri.Query,@"(?:\?|&)v=([A-Za-z0-9_-]{11})(?:&|$)");
                if(m.Success)id=m.Groups[1].Value;
                else {m=Regex.Match(uri.AbsolutePath,@"^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})/?$");if(m.Success)id=m.Groups[1].Value;}
            }
            return Regex.IsMatch(id,@"^[A-Za-z0-9_-]{11}$")?id:"";
        }
        public static bool Usable(double position,double duration){return !Double.IsNaN(position) && !Double.IsInfinity(position) && position>=5 && (duration<=0 || position<duration-Math.Min(10,duration*0.02));}
        public static void Record(List<ResumePoint> points,string url,string title,double position,double duration,bool live){
            string key=Key(url);if(key.Length==0 || live || Double.IsNaN(position) || Double.IsInfinity(position) || position<0)return;
            points.RemoveAll(p=>p==null || p.Key==key);
            if(Usable(position,duration))points.Insert(0,new ResumePoint{Key=key,Title=title,Position=position,Duration=duration,Updated=DateTime.UtcNow.Ticks});
            if(points.Count>1000)points.RemoveRange(1000,points.Count-1000);
        }
        public static double Position(List<ResumePoint> points,string url,bool live){var p=points.FirstOrDefault(x=>x!=null && x.Key==Key(url));return !live && p!=null && Usable(p.Position,p.Duration)?p.Position:0;}
    }
    public sealed class WatchEntry {
        public string Id=Guid.NewGuid().ToString("N");public Video Video=new Video();public int Page;public int PageSize=50;public bool Account;public string Error="",Origin="";
        public bool Playlist {get{return Video!=null && (Video.Kind=="playlist" || ContentFeeds.IsPlaylist(Video.Url));}}
    }
    public sealed class PlaylistBatch {
        public List<Video> Items=new List<Video>();public bool HasMore;
        public static PlaylistBatch Parse(Dictionary<string,object> root,int page,int size){
            object raw;var entries=root.TryGetValue("entries",out raw)?raw as object[]:null;
            if(entries==null)throw new InvalidOperationException(L.T("Playlist page has no playable videos."));
            var result=new PlaylistBatch();
            foreach(var item in entries){var d=item as Dictionary<string,object>;if(d==null)continue;var v=Video.From(d);try{v.Url=Rules.YouTubeUrl(v.Url);if(v.Kind!="playlist" && ResumeRules.Key(v.Url).Length>0)result.Items.Add(v);}catch(ArgumentException){}}
            int total;bool known=Int32.TryParse(Json.Str(root,"playlist_count"),out total) && total>=0;
            result.HasMore=known?(long)(page+1)*size<total:entries.Length>=size;return result;
        }
    }
    public static class QueueRules {
        public static string PlaylistUrl(string value){
            try{var uri=new Uri(Rules.YouTubeUrl(value));var match=Regex.Match(uri.Query,@"(?:\?|&)list=([A-Za-z0-9_-]{1,256})(?:&|$)");return match.Success?"https://www.youtube.com/playlist?list="+match.Groups[1].Value:"";}catch(ArgumentException){return "";}
        }

        public static void Move<T>(List<T> rows,int from,int to){if(from<0 || from>=rows.Count || to<0 || to>=rows.Count || from==to)return;var item=rows[from];rows.RemoveAt(from);rows.Insert(to,item);}
        public static List<WatchEntry> Clean(IEnumerable<WatchEntry> rows){return (rows??new List<WatchEntry>()).Where(x=>x!=null && x.Video!=null && !String.IsNullOrWhiteSpace(x.Video.Url)).Take(5000).ToList();}
    }
}
