using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
namespace LowTube {
    public sealed class FeedPreset {
        public int Batch,DelayMs,Minutes;
        public static readonly string[] Names={"Slow connection","Balanced connection","Fast connection"};
        public static FeedPreset Get(int index){return index==0?new FeedPreset{Batch=2,DelayMs=120000,Minutes=30}:index==2?new FeedPreset{Batch=6,DelayMs=30000,Minutes=5}:new FeedPreset{Batch=4,DelayMs=60000,Minutes=15};}
    }
    // A finite background allowance; pending work waits for the next window.
    public static class FeedQueuePolicy {
        public static bool IsManual(int priority){return priority<0;}
        public static bool MayQueue(bool running,bool attempted,bool manual){return !running && (!attempted || manual);}
        public static int FollowUpPriority(int priority){return IsManual(priority)?2:priority;}
        public static bool SameBudget(int left,int right){return IsManual(left)==IsManual(right);}
    }
    public sealed class FeedWorkBudget {
        DateTime window=DateTime.MinValue;int starts,dates;double seconds;
        public bool CanStart(DateTime now,int minutes,bool date){
            if(window==DateTime.MinValue || now-window>=TimeSpan.FromMinutes(minutes)){window=now;starts=dates=0;seconds=0;}
            return starts<4 && seconds<60 && (!date || dates<1);
        }
        public void Start(bool date){starts++;if(date)dates++;}
        public void Complete(double elapsed){seconds+=Math.Max(0,elapsed);}
    }
    public sealed class ChannelCacheEntry {
        public string Scope="",Channel="",Kind="";
        public int Depth;
        public long Checked,Used;
        public bool End;
        public List<Video> Rows=new List<Video>();
    }
    // All disk access is serialized by gate and performed away from the UI thread.
    public sealed class FeedCache {
        readonly object gate=new object();readonly string path;
        List<ChannelCacheEntry> entries=new List<ChannelCacheEntry>();
        public FeedCache(string file){path=file;}
        public void Load(){lock(gate){entries=(Json.Load(path,new List<ChannelCacheEntry>())??new List<ChannelCacheEntry>()).Where(x=>x!=null && x.Rows!=null && LocalFeeds.ValidChannelId(x.Channel)).Take(2000).ToList();foreach(var e in entries)e.Rows=Merge(new Video[0],e.Rows).Take(5000).ToList();}}
        ChannelCacheEntry Find(string scope,string channel,string kind){return entries.FirstOrDefault(x=>x.Scope==scope && x.Channel==channel && x.Kind==kind);}
        public int Depth(string scope,string channel,string kind){lock(gate){var e=Find(scope,channel,kind);return e==null?0:e.Depth;}}
        public bool Fresh(string scope,string channel,string kind,int minutes){lock(gate){var e=Find(scope,channel,kind);return e!=null && DateTime.UtcNow.Ticks-e.Checked<TimeSpan.FromMinutes(minutes).Ticks;}}
        public bool End(string scope,string channel,string kind){lock(gate){var e=Find(scope,channel,kind);return e!=null && e.End;}}
        public List<Video> Read(string scope,IEnumerable<string> channels,string kind){lock(gate){var ids=new HashSet<string>(channels);return Merge(new Video[0],entries.Where(e=>e.Scope==scope && ids.Contains(e.Channel) && (kind=="all" || e.Kind==kind)).SelectMany(e=>e.Rows));}}
        // Returns whether a refresh must continue to find overlap with the saved head.
        public bool Add(string scope,string channel,string kind,int page,List<Video> rows,bool save=true){lock(gate){
            var e=Find(scope,channel,kind);if(e==null){e=new ChannelCacheEntry{Scope=scope,Channel=channel,Kind=kind};entries.Add(e);}
            e.Used=DateTime.UtcNow.Ticks;var old=new HashSet<string>(e.Rows.Select(Key));bool continueHead=old.Count>0 && rows.Count>=25 && !rows.Any(v=>old.Contains(Key(v)));
            e.Rows=Merge(page==0?rows:e.Rows,page==0?e.Rows:rows).Take(5000).ToList();if(page==0){e.End=false;e.Checked=DateTime.UtcNow.Ticks;}
            if(page<=e.Depth)e.Depth=Math.Max(e.Depth,page+1);if(rows.Count<25)e.End=true;
            if(save)Save();return continueHead;
        }}
        public void Enrich(string id,Video info){lock(gate){bool changed=false;foreach(var e in entries)foreach(var v in e.Rows.Where(v=>v.Id==id && String.IsNullOrEmpty(v.Published))){v.Published=info.Published;v.Date=info.Date;changed=true;}if(changed)Save();}}
        public void Flush(){lock(gate)Save();}
        void Save(){
            entries=entries.OrderByDescending(e=>e.Used==0?e.Checked:e.Used).Take(2000).ToList();int remaining=20000;
            foreach(var e in entries){int keep=Math.Min(5000,remaining);if(e.Rows.Count>keep){e.Rows=e.Rows.Take(keep).ToList();e.Depth=0;e.End=false;}remaining-=e.Rows.Count;}
            Json.Save(path,entries);
        }
        public static string Key(Video v){return String.IsNullOrEmpty(v.Id)?v.Url:v.Id;}
        public static List<Video> Merge(IEnumerable<Video> fresh,IEnumerable<Video> old){
            var map=new Dictionary<string,Video>();var order=new List<Video>();
            foreach(var v in fresh.Concat(old).Where(v=>v!=null && !String.IsNullOrEmpty(v.Url))){Video prior;string key=Key(v);if(map.TryGetValue(key,out prior)){if(String.IsNullOrEmpty(prior.Published) && !String.IsNullOrEmpty(v.Published)){prior.Published=v.Published;prior.Date=v.Date;}continue;}map.Add(key,v);order.Add(v);}
            // LINQ's stable order preserves source order for unknown/tied dates.
            return order.OrderByDescending(v=>v.Published??"",StringComparer.Ordinal).ToList();
        }
        public static void SetDate(Video v,Dictionary<string,object> d){
            double seconds;DateTime time;string date=Json.Str(d,"upload_date");
            if(Double.TryParse(Json.Str(d,"timestamp"),NumberStyles.Float,CultureInfo.InvariantCulture,out seconds) && seconds>0 && seconds<253402300799d)time=new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc).AddSeconds(seconds);
            else if(!DateTime.TryParseExact(date,"yyyyMMdd",CultureInfo.InvariantCulture,DateTimeStyles.AssumeUniversal|DateTimeStyles.AdjustToUniversal,out time))return;
            v.Published=time.ToString("yyyy-MM-ddTHH:mm:ssZ",CultureInfo.InvariantCulture);v.Date=time.ToString("yyyy-MM-dd",CultureInfo.InvariantCulture);
        }
    }
    // Instance-owned, bounded memory only: a new process can never restore old recommendation pages.
    public sealed class RecommendationCache {
        readonly Dictionary<string,List<Video>> pages=new Dictionary<string,List<Video>>();readonly Queue<string> order=new Queue<string>();
        public List<Video> Get(string key){List<Video> rows;return pages.TryGetValue(key,out rows)?new List<Video>(rows):null;}
        public void Put(string key,List<Video> rows){if(!pages.ContainsKey(key))order.Enqueue(key);pages[key]=new List<Video>(rows);while(order.Count>12)pages.Remove(order.Dequeue());}
        public void Clear(){pages.Clear();order.Clear();}
    }
}
