using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;

namespace LowTube {
    public sealed class LocalChannel {
        public string Id="",Title="";
        public override string ToString(){return Title+"  ["+Id+"]";}
    }
    public static class LocalFeeds {
        public static string StorePath { get { return Path.Combine(Paths.Data,"subscriptions.json"); } }
        public static bool ValidChannelId(string id){return Regex.IsMatch(id??"",@"^UC[A-Za-z0-9_-]{22}$");}
        public static List<LocalChannel> Normalize(IEnumerable<LocalChannel> channels){
            return (channels??Enumerable.Empty<LocalChannel>()).Where(c=>c!=null && ValidChannelId(c.Id)).GroupBy(c=>c.Id).Select(g=>g.First()).Take(100)
                .Select(c=>new LocalChannel{Id=c.Id,Title=String.IsNullOrWhiteSpace(c.Title)?c.Id:c.Title.Substring(0,Math.Min(250,c.Title.Length))}).ToList();
        }
        public static List<LocalChannel> Load(){return Normalize(Json.Load(StorePath,new List<LocalChannel>()));}
        public static void Save(List<LocalChannel> channels){Json.Save(StorePath,Normalize(channels));}
        public static bool Add(List<LocalChannel> channels,LocalChannel channel){
            if(!ValidChannelId(channel.Id))throw new ArgumentException(L.T("Invalid channel ID."));
            if(channels.Any(c=>c.Id==channel.Id))return false;
            if(channels.Count>=100)throw new InvalidOperationException(L.T("The local subscription limit is 100 channels."));
            channels.Add(channel);return true;
        }
        public static string ChannelInput(string value){
            value=(value??"").Trim();
            if(ValidChannelId(value))return "https://www.youtube.com/channel/"+value+"/videos";
            if(value.StartsWith("@",StringComparison.Ordinal) && Regex.IsMatch(value,@"^@[^/?#\s]{1,100}$"))
                return "https://www.youtube.com/@"+Uri.EscapeDataString(value.Substring(1))+"/videos";
            return Rules.YouTubeUrl(value);
        }
        public static readonly string[] LanguageCodes={"any","en","uk","ru","pl","de","fr","es","it","pt","ja","ko"};
        public static readonly string[] LanguageNames={"Any language","English","Українська","Русский","Polski","Deutsch","Français","Español","Italiano","Português","日本語","한국어"};
        static readonly string[] SearchLanguageTerms={"","in English","українською","на русском","po polsku","auf Deutsch","en français","en español","in italiano","em português","日本語","한국어"};
        public static string LanguageCode(string code){return LanguageCodes.Contains(code)?code:"any";}
        public static string DiscoveryTarget(string topic,string language="any"){
            string suffix=SearchLanguageTerms[Array.IndexOf(LanguageCodes,LanguageCode(language))];
            topic=(topic??"").Trim();if(topic.Length==0)topic="science and technology";
            if(topic.Length>200)topic=topic.Substring(0,200);
            // Explicit search prefix: a topic cannot become an extractor command or URL.
            return "ytsearch100:"+topic+(suffix.Length==0?"":" "+suffix);
        }
        public static List<Video> Parse(string xml,LocalChannel channel){
            if(!ValidChannelId(channel.Id))throw new ArgumentException(L.T("Invalid channel ID."));
            var safe=new XmlReaderSettings{DtdProcessing=DtdProcessing.Prohibit,XmlResolver=null,MaxCharactersInDocument=2*1024*1024};
            var doc=new XmlDocument{XmlResolver=null};
            using(var reader=XmlReader.Create(new StringReader(xml.TrimStart('\uFEFF')),safe))doc.Load(reader);
            var ns=new XmlNamespaceManager(doc.NameTable);ns.AddNamespace("a","http://www.w3.org/2005/Atom");ns.AddNamespace("yt","http://www.youtube.com/xml/schemas/2015");
            var feed=doc.SelectSingleNode("/a:feed",ns);if(feed==null)throw new InvalidDataException(L.T("Invalid channel feed."));
            var feedId=feed.SelectSingleNode("yt:channelId",ns);
            if(feedId==null || (feedId.InnerText!=channel.Id && "UC"+feedId.InnerText!=channel.Id))throw new InvalidDataException(L.T("Invalid channel feed."));
            var videos=new List<Video>();
            foreach(XmlNode item in feed.SelectNodes("a:entry",ns)) {
                string id=Text(item,"yt:videoId",ns);if(!Regex.IsMatch(id,@"^[A-Za-z0-9_-]{11}$"))continue;
                string published=Text(item,"a:published",ns);DateTimeOffset time;
                string date=DateTimeOffset.TryParse(published,CultureInfo.InvariantCulture,DateTimeStyles.None,out time)?time.LocalDateTime.ToString("dd.MM.yyyy HH:mm"):"";
                videos.Add(new Video{Id=id,Title=Text(item,"a:title",ns),Channel=channel.Title,ChannelId=channel.Id,Published=published,
                    Url="https://www.youtube.com/watch?v="+id,Date=date,Duration="—"});
            }
            return Merge(videos).Take(30).ToList();
        }
        static string Text(XmlNode node,string xpath,XmlNamespaceManager ns){var child=node.SelectSingleNode(xpath,ns);return child==null?"":child.InnerText;}
        static long TimeKey(Video video){DateTimeOffset date;return DateTimeOffset.TryParse(video.Published,CultureInfo.InvariantCulture,DateTimeStyles.None,out date)?date.UtcDateTime.Ticks:0;}
        public static List<Video> Merge(IEnumerable<Video> videos){
            return videos.Where(v=>v!=null && Regex.IsMatch(v.Id??"",@"^[A-Za-z0-9_-]{11}$")).GroupBy(v=>v.Id).Select(g=>g.OrderByDescending(TimeKey).First())
                .OrderByDescending(TimeKey).ThenBy(v=>v.Id,StringComparer.Ordinal).Take(3000).ToList();
        }
        public static async Task<List<Video>> Refresh(List<LocalChannel> channels,CancellationToken ct,Action<string> progress,bool allChannels=false){
            using(var gate=new SemaphoreSlim(2)) {
                var selected=allChannels?channels.Where(c=>c!=null && ValidChannelId(c.Id)).GroupBy(c=>c.Id).Select(g=>g.First()).ToList():Normalize(channels);
                var tasks=selected.Select(async channel=>{
                    await gate.WaitAsync(ct);
                    try{return await Fetch(channel,ct,progress);}finally{gate.Release();}
                }).ToArray();
                var lists=await Task.WhenAll(tasks);ct.ThrowIfCancellationRequested();return Merge(lists.SelectMany(x=>x));
            }
        }
        static async Task<List<Video>> Fetch(LocalChannel channel,CancellationToken ct,Action<string> progress){
            string cache=Path.Combine(Paths.Data,"feeds",channel.Id+".json");
            try {
                ct.ThrowIfCancellationRequested();
                using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct)) {
                    timeout.CancelAfter(15000);
                    var request=(HttpWebRequest)WebRequest.Create("https://www.youtube.com/feeds/videos.xml?channel_id="+channel.Id);
                    request.UserAgent="LowTube/0.5";request.AllowAutoRedirect=false;request.Timeout=15000;request.ReadWriteTimeout=15000;
                    using(timeout.Token.Register(delegate{request.Abort();}))
                    using(var response=(HttpWebResponse)await request.GetResponseAsync())
                    using(var input=response.GetResponseStream())
                    using(var bytes=new MemoryStream()) {
                        const int limit=2*1024*1024;if(response.ContentLength>limit)throw new InvalidDataException(L.T("Invalid channel feed."));
                        byte[] buffer=new byte[8192];int count;
                        while((count=await input.ReadAsync(buffer,0,buffer.Length,timeout.Token))>0){
                            if(bytes.Length+count>limit)throw new InvalidDataException(L.T("Invalid channel feed."));bytes.Write(buffer,0,count);
                        }
                        var videos=Parse(Encoding.UTF8.GetString(bytes.ToArray()),channel);ct.ThrowIfCancellationRequested();
                        Json.Save(cache,videos);return videos;
                    }
                }
            }catch(Exception e){
                ct.ThrowIfCancellationRequested();
                if(!(e is WebException) && !(e is IOException) && !(e is XmlException) && !(e is OperationCanceledException))throw;
                var saved=Json.Load(cache,new List<Video>())??new List<Video>();
                saved=saved.Where(v=>v!=null && v.ChannelId==channel.Id).ToList();
                progress(L.T(saved.Count>0?"{0}: feed unavailable; showing cached videos.":"{0}: feed unavailable; no cached videos.",channel.Title));
                return saved;
            }
        }
    }
}
