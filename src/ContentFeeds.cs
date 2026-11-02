using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
namespace LowTube {
    public sealed class YouTubeSnapshot {
        public string SessionStamp="";
        public List<LocalChannel> Channels=new List<LocalChannel>();
        public List<Video> Subscriptions=new List<Video>();
    }
    public static class ContentFeeds {
        public static readonly string[] Kinds={"all","video","shorts","live","playlist"};
        public static readonly string[] Labels={"All content","Videos","Shorts","Live streams","Playlists"};
        public static string Store {get{return Path.Combine(Paths.Data,"youtube-feeds.json");}}
        static readonly object storeGate=new object();
        public static YouTubeSnapshot Load(){lock(storeGate){var data=Json.Load(Store,new YouTubeSnapshot())??new YouTubeSnapshot();return data.SessionStamp==SavedSession.Stamp?data:new YouTubeSnapshot{SessionStamp=SavedSession.Stamp};}}
        public static void Save(YouTubeSnapshot data){lock(storeGate)Json.Save(Store,data);}
        public static void RemoveLegacyRecommendations(){lock(storeGate){if(!File.Exists(Store))return;var data=Json.Load(Store,new YouTubeSnapshot())??new YouTubeSnapshot();Json.Save(Store,data);}}
        public static string Kind(Dictionary<string,object> d){
            string url=Json.Str(d,"webpage_url",Json.Str(d,"url")),live=Json.Str(d,"live_status"),id=Json.Str(d,"id");
            if(url.IndexOf("/shorts/",StringComparison.OrdinalIgnoreCase)>=0 || Json.Str(d,"original_url").IndexOf("/shorts/",StringComparison.OrdinalIgnoreCase)>=0)return "shorts";
            if(new[]{"is_live","is_upcoming","was_live","post_live"}.Contains(live) || Json.Str(d,"is_live").Equals("True",StringComparison.OrdinalIgnoreCase) || Json.Str(d,"was_live").Equals("True",StringComparison.OrdinalIgnoreCase))return "live";
            if(IsPlaylist(url) || (Json.Str(d,"_type")=="playlist" && !Regex.IsMatch(id,@"^[A-Za-z0-9_-]{11}$")))return "playlist";
            return "video";
        }
        public static string SearchTarget(string input,string kind){
            string target=Rules.Search(input,100);if(!target.StartsWith("ytsearch",StringComparison.Ordinal))return target;
            if(kind!="playlist" && kind!="live")return target;
            return "https://www.youtube.com/results?search_query="+Uri.EscapeDataString(input.Trim())+"&sp="+Uri.EscapeDataString(kind=="playlist"?"EgIQAw==":"EgJAAQ==");
        }
        public static bool IsPlaylist(string url){Uri uri;return Uri.TryCreate(url,UriKind.Absolute,out uri) && uri.AbsolutePath.TrimEnd('/').Equals("/playlist",StringComparison.OrdinalIgnoreCase);}
        public static IEnumerable<Video> Filter(IEnumerable<Video> rows,string kind,string channel){return rows.Where(x=>x!=null && (kind=="all" || x.Kind==kind) && (String.IsNullOrEmpty(channel) || x.ChannelId==channel));}
        public static string ChannelTarget(string id,string kind){
            if(!LocalFeeds.ValidChannelId(id))throw new ArgumentException(L.T("Invalid channel ID."));
            return "https://www.youtube.com/channel/"+id+"/"+(kind=="shorts"?"shorts":kind=="live"?"streams":kind=="playlist"?"playlists":"videos");
        }
        public static List<LocalChannel> Channels(Dictionary<string,object> root){return Json.Items(root,"entries").Select(x=>new LocalChannel{Id=Json.Str(x,"channel_id",Json.Str(x,"id")),Title=Json.Str(x,"channel",Json.Str(x,"title"))}).Where(x=>LocalFeeds.ValidChannelId(x.Id)).GroupBy(x=>x.Id).Select(x=>x.First()).ToList();}
    }
}
