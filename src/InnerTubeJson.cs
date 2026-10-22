using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;

namespace LowTube {
    // YouTube's catalogue has several renderer generations. Keep them out of the UI
    // and of the stream resolver. Never interpret navigation/menu links as entries.
    public sealed class InnerTubePage {
        public readonly List<Video> Items=new List<Video>(); // null = unavailable playlist slot
        public readonly List<LocalChannel> Channels=new List<LocalChannel>();
        public string Continuation="";
        public bool Recognized;
    }
    public static class InnerTubeJson {
        static readonly HashSet<string> containers=new HashSet<string>{"richGridRenderer","sectionListRenderer","itemSectionRenderer","gridRenderer","playlistVideoListRenderer","playlistPanelRenderer","richGridContinuation","sectionListContinuation","itemSectionContinuation","gridContinuation","playlistVideoListContinuation","messageRenderer","backgroundPromoRenderer"};
        public static Dictionary<string,object> Map(object value){return value as Dictionary<string,object>??new Dictionary<string,object>();}
        public static object At(object value,params string[] path){foreach(string key in path){var map=value as Dictionary<string,object>;object next;if(map==null || !map.TryGetValue(key,out next))return null;value=next;}return value;}
        public static string StringAt(object value,params string[] path){return Convert.ToString(At(value,path),CultureInfo.InvariantCulture)??"";}
        public static string Text(object value){
            if(value is string)return (string)value;
            var map=Map(value);string simple=Json.Str(map,"simpleText",Json.Str(map,"content"));
            return simple.Length>0?simple:String.Join("",Json.Items(map,"runs").Select(x=>Json.Str(x,"text")));
        }
        // Balanced extraction, not a greedy regex: strings may contain braces and escapes.
        public static Dictionary<string,object> Embedded(string html,string marker){
            int offset=0;
            while(offset<html.Length){
                int start=html.IndexOf(marker,offset,StringComparison.Ordinal);if(start<0)break;
                offset=start+marker.Length;start=offset;while(start<html.Length && Char.IsWhiteSpace(html[start]))start++;
                // The same name also occurs in JS function definitions/calls. Only
                // a literal object directly after the marker is JSON configuration.
                if(start>=html.Length || html[start]!='{')continue;
                int depth=0;bool quoted=false,escaped=false;
                for(int i=start;i<html.Length;i++){
                    char c=html[i];if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
                    if(c=='"')quoted=true;else if(c=='{')depth++;else if(c=='}' && --depth==0){
                        try{return Json.Object(html.Substring(start,i-start+1));}catch(ArgumentException){offset=i+1;break;}
                    }
                }
            }
            return new Dictionary<string,object>();
        }
        public static InnerTubePage Parse(Dictionary<string,object> root){
            var page=new InnerTubePage();
            var contents=Map(At(root,"contents"));bool selected=false;
            foreach(string layout in new[]{"twoColumnBrowseResultsRenderer","singleColumnBrowseResultsRenderer"}){
                foreach(var tab in Json.Items(Map(At(contents,layout)),"tabs")){
                    var renderer=Map(At(tab,"tabRenderer"));
                    if(Json.Str(renderer,"selected").Equals("true",StringComparison.OrdinalIgnoreCase)){
                        Scan(At(renderer,"content"),page,0);selected=true;break;
                    }
                }
            }
            if(!selected){
                object primary=At(contents,"twoColumnSearchResultsRenderer","primaryContents");
                if(primary!=null)Scan(primary,page,0);
                else if(contents.ContainsKey("sectionListRenderer") || contents.ContainsKey("richGridRenderer"))Scan(contents,page,0);
            }
            foreach(string commands in new[]{"onResponseReceivedActions","onResponseReceivedEndpoints","onResponseReceivedCommands"})
                foreach(var action in Json.Items(root,commands))foreach(string command in new[]{"appendContinuationItemsAction","reloadContinuationItemsCommand"}){
                    var items=At(action,command,"continuationItems") as object[];if(items!=null){if(items.Length==0)page.Recognized=true;Scan(items,page,0);}
                }
            object continuation=At(root,"continuationContents");if(continuation!=null)Scan(continuation,page,0);
            return page;
        }
        static void Scan(object value,InnerTubePage page,int depth){
            if(depth>100)return;
            var array=value as object[];if(array!=null){foreach(var item in array)Scan(item,page,depth+1);return;}
            var map=value as Dictionary<string,object>;if(map==null)return;
            foreach(var pair in map){
                string key=pair.Key;var r=pair.Value as Dictionary<string,object>;
                if(containers.Contains(key))page.Recognized=true;
                if(key=="continuationItemRenderer"){
                    page.Recognized=true;
                    string token=StringAt(r,"continuationEndpoint","continuationCommand","token");
                    if(token.Length==0)token=StringAt(r,"button","buttonRenderer","command","continuationCommand","token");
                    if(token.Length==0)throw InnerTubeException.InvalidResponse();page.Continuation=token;continue;
                }
                if(key=="nextContinuationData"){string token=Json.Str(r,"continuation");if(token.Length==0)throw InnerTubeException.InvalidResponse();page.Continuation=token;continue;}
                if(key=="channelRenderer" || key=="gridChannelRenderer"){
                    page.Recognized=true;
                    string id=Json.Str(r,"channelId");if(LocalFeeds.ValidChannelId(id))page.Channels.Add(new LocalChannel{Id=id,Title=Text(At(r,"title"))});continue;
                }
                if(new[]{"videoRenderer","gridVideoRenderer","playlistVideoRenderer","playlistPanelVideoRenderer","reelItemRenderer"}.Contains(key)){
                    page.Recognized=true;
                    Video video=LegacyVideo(r,key);if(video!=null || key=="playlistVideoRenderer")page.Items.Add(video);continue;
                }
                if(key=="playlistRenderer" || key=="gridPlaylistRenderer"){
                    page.Recognized=true;
                    string id=Json.Str(r,"playlistId");if(ValidPlaylist(id))page.Items.Add(new Video{Id=id,Title=Text(At(r,"title")),Kind="playlist",Url="https://www.youtube.com/playlist?list="+id,Duration="—",Channel=Text(At(r,"shortBylineText"))});continue;
                }
                if(key=="lockupViewModel"){page.Recognized=true;Video video=Lockup(r);if(video!=null)page.Items.Add(video);continue;}
                if(key=="shortsLockupViewModel"){
                    page.Recognized=true;
                    string id=StringAt(r,"onTap","innertubeCommand","reelWatchEndpoint","videoId");
                    if(ValidVideo(id))page.Items.Add(new Video{Id=id,Title=Text(At(r,"overlayMetadata","primaryText")),Kind="shorts",Url="https://www.youtube.com/shorts/"+id,Duration="—"});continue;
                }
                // Sidebars, ads, carousels linking to other lists, menus and headers
                // must not contribute entries or steal the active continuation token.
                if(key=="menu" || key=="navigationEndpoint" || key=="header" || key=="sidebar" || key=="secondaryContents" || key=="engagementPanels" || key=="frameworkUpdates" || key.IndexOf("promoted",StringComparison.OrdinalIgnoreCase)>=0 || key.IndexOf("adSlot",StringComparison.OrdinalIgnoreCase)>=0)continue;
                Scan(pair.Value,page,depth+1);
            }
        }
        static bool ValidVideo(string id){return Regex.IsMatch(id??"",@"^[A-Za-z0-9_-]{11}$");}
        static bool ValidPlaylist(string id){return Regex.IsMatch(id??"",@"^[A-Za-z0-9_-]{1,256}$");}
        static Video LegacyVideo(Dictionary<string,object> r,string renderer){
            string id=Json.Str(r,"videoId");if(!ValidVideo(id) || Json.Str(r,"isPlayable").Equals("false",StringComparison.OrdinalIgnoreCase))return null;
            var v=new Video{Id=id,Title=Text(At(r,"title")),Url="https://www.youtube.com/watch?v="+id,Duration=Text(At(r,"lengthText"))};
            if(v.Title=="[Deleted video]" || v.Title=="[Private video]")return null;
            var byline=At(r,"ownerText")??At(r,"shortBylineText")??At(r,"longBylineText");v.Channel=Text(byline);
            foreach(var run in Json.Items(Map(byline),"runs")){
                string channel=StringAt(run,"navigationEndpoint","browseEndpoint","browseId");if(LocalFeeds.ValidChannelId(channel)){v.ChannelId=channel;break;}
            }
            v.Date=Text(At(r,"publishedTimeText"));
            if(renderer=="reelItemRenderer" || StringAt(r,"navigationEndpoint","commandMetadata","webCommandMetadata","url").StartsWith("/shorts/",StringComparison.Ordinal))v.Kind="shorts";
            string flags=Json.Serializer().Serialize(new object[]{At(r,"badges"),At(r,"thumbnailOverlays")});
            if(At(r,"upcomingEventData")!=null)v.LiveStatus="is_upcoming";
            else if(flags.Contains("BADGE_STYLE_TYPE_LIVE_NOW") || flags.Contains("\"style\":\"LIVE\""))v.LiveStatus="is_live";
            else if(v.Date.IndexOf("streamed",StringComparison.OrdinalIgnoreCase)>=0)v.LiveStatus="was_live";
            if(v.LiveStatus.Length>0)v.Kind="live";
            if(v.Duration.Length==0){long seconds;if(Int64.TryParse(Json.Str(r,"lengthSeconds"),out seconds) && seconds>0 && seconds<864000)v.Duration=TimeSpan.FromSeconds(seconds).ToString(seconds>=3600?@"h\:mm\:ss":@"m\:ss");else v.Duration="—";}
            return v;
        }
        static Video Lockup(Dictionary<string,object> r){
            string type=Json.Str(r,"contentType"),id=Json.Str(r,"contentId");bool playlist=type=="LOCKUP_CONTENT_TYPE_PLAYLIST" || type=="LOCKUP_CONTENT_TYPE_PODCAST";
            if((!playlist && (type!="LOCKUP_CONTENT_TYPE_VIDEO" || !ValidVideo(id))) || (playlist && !ValidPlaylist(id)))return null;
            var md=Map(At(r,"metadata","lockupMetadataViewModel"));
            var v=new Video{Id=id,Title=Text(At(md,"title")),Kind=playlist?"playlist":"video",Url=playlist?"https://www.youtube.com/playlist?list="+id:"https://www.youtube.com/watch?v="+id,Duration="—"};
            foreach(var row in Json.Items(Map(At(md,"metadata","contentMetadataViewModel")),"metadataRows"))foreach(var part in Json.Items(row,"metadataParts")){
                var text=Map(At(part,"text"));string channel="";
                foreach(var run in Json.Items(text,"commandRuns")){channel=StringAt(run,"onTap","innertubeCommand","browseEndpoint","browseId");if(LocalFeeds.ValidChannelId(channel))break;}
                if(LocalFeeds.ValidChannelId(channel)){v.ChannelId=channel;v.Channel=Text(text);}else if(Text(text).Length>0)v.Date=Text(text);
            }
            FindBadges(At(r,"contentImage"),v,0);
            if(!playlist && v.Date.IndexOf("streamed",StringComparison.OrdinalIgnoreCase)>=0){v.LiveStatus="was_live";v.Kind="live";}
            return v;
        }
        static void FindBadges(object value,Video video,int depth){
            if(depth>25)return;var array=value as object[];if(array!=null){foreach(var item in array)FindBadges(item,video,depth+1);return;}
            var map=value as Dictionary<string,object>;if(map==null)return;
            foreach(var item in map){
                if(item.Key=="thumbnailBadgeViewModel"){
                    var badge=Map(item.Value);string text=Text(At(badge,"text"));
                    if(Regex.IsMatch(text,@"^\d+:\d{2}(?::\d{2})?$"))video.Duration=text;
                    string style=Json.Str(badge,"badgeStyle");
                    if(style.IndexOf("LIVE",StringComparison.Ordinal)>=0){video.LiveStatus="is_live";video.Kind="live";}
                    if(text.Equals("UPCOMING",StringComparison.OrdinalIgnoreCase)){video.LiveStatus="is_upcoming";video.Kind="live";}
                }else FindBadges(item.Value,video,depth+1);
            }
        }
        public static Dictionary<string,object> Metadata(Dictionary<string,object> player){
            var details=Map(At(player,"videoDetails"));string id=Json.Str(details,"videoId");
            if(!ValidVideo(id))throw new InvalidOperationException(L.T("InnerTube returned an unsupported response. Retry later."));
            var micro=Map(At(player,"microformat","playerMicroformatRenderer"));
            var result=new Dictionary<string,object>{{"id",id},{"title",Json.Str(details,"title")},{"channel",Json.Str(details,"author")},{"channel_id",Json.Str(details,"channelId")},{"duration",Json.Str(details,"lengthSeconds")},{"webpage_url","https://www.youtube.com/watch?v="+id}};
            string date=Json.Str(micro,"publishDate",Json.Str(micro,"uploadDate"));DateTimeOffset parsed;
            if(DateTimeOffset.TryParse(date,CultureInfo.InvariantCulture,DateTimeStyles.AssumeUniversal,out parsed))result["timestamp"]=(parsed.UtcDateTime-new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc)).TotalSeconds;
            var live=Map(At(micro,"liveBroadcastDetails"));
            if(Json.Str(details,"isLive").Equals("true",StringComparison.OrdinalIgnoreCase) || Json.Str(live,"isLiveNow").Equals("true",StringComparison.OrdinalIgnoreCase))result["live_status"]="is_live";
            else if(Json.Str(details,"isUpcoming").Equals("true",StringComparison.OrdinalIgnoreCase))result["live_status"]="is_upcoming";
            else if(live.Count>0)result["live_status"]="was_live";
            return result;
        }
    }
}
