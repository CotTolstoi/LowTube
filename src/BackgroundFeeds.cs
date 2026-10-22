using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace LowTube {
    public sealed partial class MainForm {
        sealed class FeedJob {
            public string Key,Scope,Kind;public string Target="",ViewKind="",Label="";public int ViewPage,FetchCount=25;public int Page,Priority;public bool Account,Refresh;public LocalChannel Channel;
            public Func<CancellationToken,Task> Action;
        }
        readonly FeedCache feedCache=new FeedCache(Path.Combine(Paths.Data,"channel-cache-v1.json"));
        readonly RecommendationCache recommendationCache=new RecommendationCache();
        readonly List<FeedJob> feedJobs=new List<FeedJob>();
        List<FeedJob> activeFeedBatch=new List<FeedJob>();
        readonly HashSet<string> runningFeedKeys=new HashSet<string>();
        readonly FeedWorkBudget feedBudget=new FeedWorkBudget();
        readonly HashSet<string> attemptedFeedKeys=new HashSet<string>();
        readonly HashSet<string> dateAttempts=new HashSet<string>();
        readonly Dictionary<string,DateTime> headChecks=new Dictionary<string,DateTime>();
        readonly CancellationTokenSource feedLifetime=new CancellationTokenSource();
        CancellationTokenSource feedRequest;Task feedActive;
        System.Windows.Forms.Timer feedTimer;
        DateTime nextFeedRefresh=DateTime.MinValue,nextFeedJob=DateTime.MinValue;
        bool feedsReady,feedModal;int feedViewVersion,activeFeedPriority=9;
        string FeedScope(bool youtube){return youtube?"youtube:"+settings.Auth+":"+SavedSession.Stamp+":"+BrowserSessions.Profile(settings)+":"+settings.CookiesFile:"local";}
        Settings FeedSettings(){return Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(settings));}
        async void StartFeeds(){
            try{await Task.Run(()=>{feedCache.Load();ContentFeeds.RemoveLegacyRecommendations();});}
            catch(Exception e){if(!closing)Append(L.T("Background update failed: {0}",e.Message));}
            if(closing)return;feedsReady=true;
            try{QueueFeedRefresh();}catch(Exception e){Append(L.T("Background update failed: {0}",e.Message));}
            feedTimer=new System.Windows.Forms.Timer{Interval=1000};feedTimer.Tick+=async delegate{
                if(closing || feedModal || operation!=null)return;
                try{if(DateTime.UtcNow>=nextFeedRefresh && feedActive==null)QueueFeedRefresh();await RunFeedJob();}
                catch(Exception e){if(!closing)Append(L.T("Background update failed: {0}",e.Message));}
            };feedTimer.Start();
            if(currentTarget==":local-subscriptions" || currentTarget==":youtube-subscriptions")try{await SubscriptionView(currentTarget==":youtube-subscriptions",page);}catch(Exception e){Error(e);}
        }
        bool MatchesFeedView(FeedJob job){return job.Target==currentTarget && job.ViewKind==ContentKind && job.ViewPage==page;}
        void FeedNotice(FeedJob job,string text){Append(text);if(MatchesFeedView(job) && operation==null)status.Text=text;}
        void BeginFeedView(string label){
            // Only the currently requested view may bypass the automatic budget.
            foreach(var job in feedJobs.Where(x=>FeedQueuePolicy.IsManual(x.Priority)))job.Priority=job.Action==null?2:1;
            string text=L.T("Feed requested: {0}",label);status.Text=text;Append(text);
        }
        void WakeFeedRequest(){
            if(!feedsReady){Append(L.T("Waiting for feed service initialization."));return;}
            if(feedActive!=null && feedRequest!=null && (!FeedQueuePolicy.IsManual(activeFeedPriority) || !activeFeedBatch.Any(MatchesFeedView))){
                foreach(var job in activeFeedBatch.Where(x=>FeedQueuePolicy.IsManual(x.Priority)))job.Priority=job.Action==null?2:1;
                Append(L.T("Pausing background work for the requested list."));feedRequest.Cancel();
            }
        }
        void PromoteSubscriptionRequest(bool youtube,string kind,int viewPage){
            string scope=FeedScope(youtube),filter=ChannelFilterId;
            var running=activeFeedBatch.FirstOrDefault(x=>x.Action==null && x.Scope==scope && (kind=="all" || x.Kind==kind) && (filter.Length==0 || x.Channel.Id==filter));
            if(running!=null){
                foreach(var part in activeFeedBatch){part.Priority=-1;part.Target=currentTarget;part.ViewKind=kind;part.ViewPage=viewPage;part.Label=L.T(youtube?"YouTube subscriptions":"Subscription videos");}
                activeFeedPriority=-1;FeedNotice(running,L.T("The requested list is already loading."));return;
            }
            var candidates=feedJobs.Where(x=>x.Action==null && x.Scope==scope && (kind=="all" || x.Kind==kind) && (filter.Length==0 || x.Channel.Id==filter)).OrderBy(x=>x.Priority).ToList();
            var first=candidates.FirstOrDefault();
            if(first==null){string message=L.T("Cached list shown; no new request is queued.");status.Text=message;Append(message);return;}
            foreach(var job in candidates.Where(x=>x.Kind==first.Kind && x.Page==first.Page && x.Refresh==first.Refresh).Take(FeedPreset.Get(settings.FeedPreset).Batch)){
                job.Priority=-1;job.FetchCount=Paging.BatchCount(PageSize);job.Target=currentTarget;job.ViewKind=kind;job.ViewPage=viewPage;job.Label=L.T(youtube?"YouTube subscriptions":"Subscription videos");
            }
            string queued=L.T("Requested list queued; one channel batch will load now. Remaining channels keep the background limits.");status.Text=queued;Append(queued);WakeFeedRequest();
        }
        void StopFeeds(){feedLifetime.Cancel();if(feedRequest!=null)feedRequest.Cancel();if(feedTimer!=null){feedTimer.Stop();feedTimer.Dispose();}feedJobs.Clear();}
        async Task PauseFeeds(){if(feedRequest!=null)feedRequest.Cancel();var active=feedActive;if(active!=null)await active;}
        void QueueJob(FeedJob job){
            // Promote an in-flight prefetch when the user opens that exact page.
            var running=activeFeedBatch.FirstOrDefault(x=>x.Key==job.Key);
            if(running!=null && runningFeedKeys.Contains(job.Key) && FeedQueuePolicy.IsManual(job.Priority)){running.Priority=-1;running.Target=job.Target;running.ViewKind=job.ViewKind;running.ViewPage=job.ViewPage;running.Label=job.Label;activeFeedPriority=-1;FeedNotice(running,L.T("The requested list is already loading."));return;}
            // Enqueueing a duplicate must never cancel and restart the active batch.
            var pending=feedJobs.FirstOrDefault(x=>x.Key==job.Key);if(pending!=null){pending.Priority=Math.Min(pending.Priority,job.Priority);pending.Refresh|=job.Refresh;if(FeedQueuePolicy.IsManual(job.Priority)){pending.Target=job.Target;pending.ViewKind=job.ViewKind;pending.ViewPage=job.ViewPage;pending.Label=job.Label;}return;}
            if(FeedQueuePolicy.MayQueue(runningFeedKeys.Contains(job.Key),attemptedFeedKeys.Contains(job.Key),FeedQueuePolicy.IsManual(job.Priority)))feedJobs.Add(job);
        }
        void QueueChannels(IEnumerable<LocalChannel> channels,bool youtube,string kind,int wantedPage,int priority,bool refresh){
            string scope=FeedScope(youtube);foreach(string type in kind=="all"?ContentFeeds.Kinds.Skip(1):new[]{kind})foreach(var channel in channels){
                string key=scope+"|"+channel.Id+"|"+type+"|"+wantedPage;DateTime checkedAt;
                if(priority==0 && refresh && headChecks.TryGetValue(key,out checkedAt) && DateTime.UtcNow-checkedAt<TimeSpan.FromMinutes(FeedPreset.Get(settings.FeedPreset).Minutes))continue;
                QueueJob(new FeedJob{Key=key,Scope=scope,Kind=type,Channel=channel,Page=wantedPage,Priority=priority,Account=youtube,Refresh=refresh});
            }
        }
        void QueueFeedRefresh(){
            if(!feedsReady || closing)return;
            nextFeedRefresh=DateTime.UtcNow.AddMinutes(FeedPreset.Get(settings.FeedPreset).Minutes);attemptedFeedKeys.Clear();
            QueueChannels(LocalFeeds.Load(),false,"all",0,2,true);
            if(settings.Auth!="none")QueueChannels(ContentFeeds.Load().Channels,true,"all",0,2,true);
        }
        async Task RunFeedJob(){
            if(feedActive!=null || operation!=null || recovering || preparingNext || feedModal || !feedsReady || closing || feedJobs.Count==0)return;
            var first=feedJobs.OrderBy(x=>x.Priority).First();
            bool demand=FeedQueuePolicy.IsManual(first.Priority);
            bool date=first.Key.StartsWith("date:",StringComparison.Ordinal);
            if(!demand && (DateTime.UtcNow<nextFeedJob || !feedBudget.CanStart(DateTime.UtcNow,FeedPreset.Get(settings.FeedPreset).Minutes,date)))return;
            if(!demand)feedBudget.Start(date);var clock=System.Diagnostics.Stopwatch.StartNew();
            var batch=first.Action!=null?new List<FeedJob>{first}:feedJobs.Where(x=>x.Action==null && x.Scope==first.Scope && x.Kind==first.Kind && x.Page==first.Page && x.Refresh==first.Refresh && x.FetchCount==first.FetchCount && FeedQueuePolicy.SameBudget(x.Priority,first.Priority)).OrderBy(x=>x.Priority).Take(FeedPreset.Get(settings.FeedPreset).Batch).ToList();
            foreach(var job in batch){feedJobs.Remove(job);runningFeedKeys.Add(job.Key);}
            activeFeedBatch=batch;activeFeedPriority=first.Priority;feedRequest=CancellationTokenSource.CreateLinkedTokenSource(feedLifetime.Token);
            // Yield first, so feedActive is assigned before this operation can complete.
            feedActive=ExecuteFeedJobs(batch,feedRequest.Token);
            try{await feedActive;}finally{activeFeedBatch=new List<FeedJob>();activeFeedPriority=9;feedActive=null;if(!demand)feedBudget.Complete(clock.Elapsed.TotalSeconds);feedRequest.Dispose();feedRequest=null;foreach(var job in batch)runningFeedKeys.Remove(job.Key);nextFeedJob=DateTime.UtcNow.AddMilliseconds(FeedPreset.Get(settings.FeedPreset).DelayMs);}
        }
        async Task ExecuteFeedJobs(List<FeedJob> batch,CancellationToken ct){
            await Task.Yield();
            try{
                if(FeedQueuePolicy.IsManual(batch[0].Priority))FeedNotice(batch[0],L.T("Loading requested list: {0}",batch[0].Label));
                var job=batch[0];if(job.Action!=null){await job.Action(ct);return;}
                if(job.Scope!=FeedScope(job.Account))return;
                var config=FeedSettings();
                var results=await Task.Run(()=>new Backend(config).ChannelBatch(batch.Select(x=>x.Channel).ToList(),job.Kind,job.Page,job.Account,ct,Append,job.FetchCount));
                ct.ThrowIfCancellationRequested();if(closing || job.Scope!=FeedScope(job.Account))return;
                foreach(var part in batch){List<Video> rows;if(!results.TryGetValue(part.Channel.Id,out rows)){Append(L.T("Channel content unavailable: {0}",part.Channel.Title));continue;}
                    bool more=false;int slices=Math.Max(1,(rows.Count+24)/25);
                    for(int offset=0;offset<slices;offset++){int chunkPage=part.Page+offset;var chunk=rows.Skip(offset*25).Take(25).ToList();more=await Task.Run(()=>feedCache.Add(part.Scope,part.Channel.Id,part.Kind,chunkPage,chunk,false));}
                    if(rows.Count>0 && rows.Count<part.FetchCount && rows.Count%25==0){int endPage=part.Page+slices;await Task.Run(()=>feedCache.Add(part.Scope,part.Channel.Id,part.Kind,endPage,new List<Video>(),false));}
                    if(part.Page==0)headChecks[part.Key]=DateTime.UtcNow;
                    int after=part.Page+Math.Max(1,part.FetchCount/25);
                    if(part.Refresh && rows.Count>=part.FetchCount && (more || ((part.Kind=="live" || part.Kind=="playlist") && part.Page==0 && rows.Count>=part.FetchCount)) && after<=7)QueueChannels(new[]{part.Channel},part.Account,part.Kind,after,FeedQueuePolicy.FollowUpPriority(part.Priority),true);
                }
                await Task.Run(()=>feedCache.Flush());
                foreach(var part in batch)attemptedFeedKeys.Add(part.Key);
                if(!closing){await DisplaySubscriptionCache();Append(L.T("Background channels updated: {0}",results.Count));
                    if(FeedQueuePolicy.IsManual(batch[0].Priority))FeedNotice(batch[0],results.Count==0?L.T("No channel results received. Check the log and reopen this list to retry."):L.T("Requested batch completed: {0} channels, {1} items. Remaining channels update in background.",results.Count,results.Values.Sum(x=>x.Count)));
                }
            }catch(OperationCanceledException){if(!closing){foreach(var job in batch){runningFeedKeys.Remove(job.Key);QueueJob(job);}}}
            catch(Exception e){foreach(var part in batch)attemptedFeedKeys.Add(part.Key);if(!closing){if(FeedQueuePolicy.IsManual(batch[0].Priority))FeedNotice(batch[0],L.T("List failed: {0}. Reopen this list to retry.",Rules.Redact(e.Message)));else Append(L.T("Background update failed: {0}",e.Message));}}
        }
        async Task DisplaySubscriptionCache(bool requestMissing=false){
            if(!feedsReady || (currentTarget!=":local-subscriptions" && currentTarget!=":youtube-subscriptions"))return;
            int version=++feedViewVersion,wantedPage=page,size=PageSize;bool youtube=currentTarget==":youtube-subscriptions";string scope=FeedScope(youtube),kind=ContentKind,filter=ChannelFilterId;
            var channels=youtube?ContentFeeds.Load().Channels:LocalFeeds.Load();var chosen=channels.Where(c=>filter.Length==0 || c.Id==filter).ToList();
            var snapshot=await Task.Run(()=>{
                var cachedRows=feedCache.Read(scope,chosen.Select(c=>c.Id),kind);var missing=new List<Tuple<LocalChannel,string,int>>();
                if(requestMissing && cachedRows.Count<(wantedPage+1)*size)foreach(string type in kind=="all"?ContentFeeds.Kinds.Skip(1):new[]{kind})foreach(var channel in chosen){int depth=feedCache.Depth(scope,channel.Id,type);if(depth*25<(wantedPage+1)*size && !feedCache.End(scope,channel.Id,type))missing.Add(Tuple.Create(channel,type,depth));}
                return Tuple.Create(cachedRows,missing);
            });var rows=snapshot.Item1;
            if(closing || version!=feedViewVersion || currentTarget!=(youtube?":youtube-subscriptions":":local-subscriptions") || page!=wantedPage || PageSize!=size || ContentKind!=kind || ChannelFilterId!=filter || FeedScope(youtube)!=scope)return;
            subscriptionFeed=rows;ShowVideosPreserving(rows.Skip(wantedPage*size).Take(size),L.T(youtube?"YouTube subscriptions":"Subscription videos")+L.T(" — page ")+(wantedPage+1));
            // Expand depth only when the requested global page cannot be filled from saved rows.
            foreach(var missing in snapshot.Item2)QueueChannels(new[]{missing.Item1},youtube,missing.Item2,missing.Item3,0,false);
            QueueMissingDates(rows.Skip(wantedPage*size).Take(size),scope,3);
        }
        void ShowVideosPreserving(IEnumerable<Video> rows,string label){
            var selected=SelectedVideo();string url=selected==null?"":selected.Url;var scroll=cards.AutoScrollPosition;int top=videos.TopItem==null?0:videos.TopItem.Index;
            displayRows=rows.Where(v=>v!=null).ToList();displayedLabel=label;ApplyContentFilters(true);var replacement=displayRows.FirstOrDefault(v=>v.Url==url);cards.SelectVideo(replacement);cards.AutoScrollPosition=new System.Drawing.Point(-scroll.X,-scroll.Y);
            foreach(ListViewItem item in videos.Items)if(((Video)item.Tag).Url==url)item.Selected=true;
            if(videos.Items.Count>0 && top<videos.Items.Count)videos.TopItem=videos.Items[top];
        }
        void QueueMissingDates(IEnumerable<Video> rows,string scope,int priority=4){
            if(feedJobs.Count(x=>x.Key.StartsWith("date:",StringComparison.Ordinal))>=25)return;
            foreach(var video in rows.Where(v=>String.IsNullOrEmpty(v.Published) && v.Kind!="playlist" && v.LiveStatus!="is_upcoming").Take(25)){
                if(feedJobs.Count(x=>x.Key.StartsWith("date:",StringComparison.Ordinal))>=25)break;
                string id=video.Id,key="date:"+scope+":"+id;if(dateAttempts.Contains(key))continue;
                var captured=video;
                QueueJob(new FeedJob{Key=key,Priority=priority,Action=async ct=>{
                    if(scope!=FeedScope(scope.StartsWith("youtube:",StringComparison.Ordinal)))return;
                    var config=FeedSettings();Dictionary<string,object> metadata;
                    try{metadata=await Task.Run(()=>new Backend(config).VideoMetadata(captured.Url,ct,Append));ct.ThrowIfCancellationRequested();}
                    catch(OperationCanceledException){throw;}catch{dateAttempts.Add(key);throw;}
                    var info=Video.From(metadata);dateAttempts.Add(key);if(info.Published.Length>0){await Task.Run(()=>feedCache.Enrich(id,info));await DisplaySubscriptionCache();}
                }});
            }
        }
        string RecommendationKey(bool youtube,int number){return (youtube?FeedScope(true):"guest")+"|"+settings.DiscoveryTopic+"|"+settings.DiscoveryLanguage+"|"+ContentKind+"|"+PageSize+"|"+number;}
        Task RecommendationView(bool youtube,int nextPage){
            if(youtube && settings.Auth=="none"){Error(new InvalidOperationException(L.T("Connect a browser session in Account first.")));return Task.FromResult(0);}
            BeginFeedView(L.T(youtube?"YouTube recommendations":"Recommendations"));
            currentTarget=youtube?":ytrec":":guest-recommendations";currentLabel=L.T(youtube?"YouTube recommendations":"Recommendations");currentAccount=youtube;page=nextPage;
            var cached=recommendationCache.Get(RecommendationKey(youtube,nextPage));ShowVideos(cached??new List<Video>(),currentLabel+L.T(" — page ")+(page+1));
            if(cached==null || cached.Count==0){status.Text=L.T("Loading in background…");QueueRecommendation(youtube,nextPage,-1);WakeFeedRequest();}else{string text=L.T("Cached recommendation page: {0} items.",cached.Count);status.Text=text;Append(text);if(cached.Count>0)QueueRecommendation(youtube,nextPage+1,1);}
            return Task.FromResult(0);
        }
        void QueueRecommendation(bool youtube,int number,int priority){
            if(number<0)return;
            string key=RecommendationKey(youtube,number),kind=ContentKind;
            var saved=recommendationCache.Get(key);if(saved!=null && (!FeedQueuePolicy.IsManual(priority) || saved.Count>0))return;
            var config=FeedSettings();string target=youtube?":ytrec":ContentFeeds.SearchTarget(LocalFeeds.DiscoveryTarget(config.DiscoveryTopic,config.DiscoveryLanguage).Substring("ytsearch100:".Length),kind);
            QueueJob(new FeedJob{Key="rec:"+key,Priority=priority,Target=youtube?":ytrec":":guest-recommendations",ViewKind=kind,ViewPage=number,Label=L.T(youtube?"YouTube recommendations":"Recommendations"),Action=async ct=>{
                var rows=await Task.Run(()=>new Backend(config).Browse(target,number,youtube,ct,Append,!youtube));ct.ThrowIfCancellationRequested();
                if(closing || key!=RecommendationKey(youtube,number))return;recommendationCache.Put(key,rows);
                if(currentTarget==(youtube?":ytrec":":guest-recommendations") && page==number){ShowVideosPreserving(rows,currentLabel+L.T(" — page ")+(page+1));string loaded=rows.Count==0?L.T("The service returned an empty list. Check the session and content filter."):L.T("Recommendation page loaded: {0} items.",rows.Count);status.Text=loaded;Append(loaded);if(rows.Count>0)QueueRecommendation(youtube,number+1,1);}
            }});
        }
    }
}
