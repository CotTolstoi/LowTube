using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace LowTube {
    public sealed class InnerTubeRequest {
        public string Path,Body;
        public CookieContainer Cookies;
        public readonly Dictionary<string,string> Headers=new Dictionary<string,string>();
    }
    // No stream URLs, player API, JS execution or yt-dlp catalogue fallback here.
    // One active catalogue operation; at most four sessions and four lists/session.
    public sealed class InnerTubeClient {
        const string Origin="https://www.youtube.com";
        const int MaxItems=5000,MaxRequests=64;
        static readonly SemaphoreSlim gate=new SemaphoreSlim(1,1);
        static readonly Dictionary<string,InnerTubeClient> clients=new Dictionary<string,InnerTubeClient>();
        static readonly object cacheGate=new object();
        static readonly InnerTubeBackoff networkBackoff=new InnerTubeBackoff();
        readonly Settings settings;
        readonly bool authenticated;
        readonly string language;
        readonly Func<InnerTubeRequest,CancellationToken,Task<string>> send;
        readonly InnerTubeBackoff backoff;
        readonly Dictionary<string,ListState> lists=new Dictionary<string,ListState>();
        CookieContainer cookies=new CookieContainer(512,128,8192);
        Dictionary<string,object> context;
        string version="",visitor="",sessionIndex="0",pageId="";
        DateTime configured,used;
        bool loggedIn;
        sealed class ListState {
            public string Api,Continuation="";public bool Ended,Incomplete;
            public long Offset;
            public DateTime Created=DateTime.UtcNow;
            public readonly List<Video> Items=new List<Video>();
            public readonly List<LocalChannel> Channels=new List<LocalChannel>();
            public readonly HashSet<string> Seen=new HashSet<string>();
            public readonly Queue<string> Tokens=new Queue<string>();
        }
        public InnerTubeClient(Settings settings,bool authenticated,string language="en",Func<InnerTubeRequest,CancellationToken,Task<string>> transport=null)
            :this(settings,authenticated,language,transport,transport==null?networkBackoff:new InnerTubeBackoff()){}
        public InnerTubeClient(Settings settings,bool authenticated,string language,Func<InnerTubeRequest,CancellationToken,Task<string>> transport,InnerTubeBackoff backoff){
            this.settings=Json.Serializer().Deserialize<Settings>(Json.Serializer().Serialize(settings));
            this.authenticated=authenticated;this.language=LocalFeeds.LanguageCode(language)=="any"?"en":LocalFeeds.LanguageCode(language);send=transport??Http;this.backoff=backoff??networkBackoff;
        }
        public static InnerTubeClient For(Settings settings,bool authenticated,bool discovery=false){
            string language=discovery?settings.DiscoveryLanguage:"en";
            string key=language+"|"+(authenticated?String.Join("|",new[]{settings.Auth,SavedSession.Stamp,BrowserSessions.Browser(settings),BrowserSessions.Profile(settings),settings.CookiesFile,File.Exists(settings.CookiesFile)?File.GetLastWriteTimeUtc(settings.CookiesFile).Ticks.ToString():""}):"guest");
            lock(cacheGate){
                InnerTubeClient client;if(!clients.TryGetValue(key,out client)){
                    if(clients.Count>=4)clients.Remove(clients.OrderBy(x=>x.Value.used).First().Key);
                    client=new InnerTubeClient(settings,authenticated,language);clients.Add(key,client);
                }
                client.used=DateTime.UtcNow;return client;
            }
        }
        public static void ClearCache(){lock(cacheGate)clients.Clear();}
        async Task<T> Operation<T>(CancellationToken ct,Action<string> progress,Func<CancellationToken,Task<T>> action){
            ct.ThrowIfCancellationRequested();await gate.WaitAsync(ct);
            var clock=System.Diagnostics.Stopwatch.StartNew();
            try{
                using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct)){
                    timeout.CancelAfter(TimeSpan.FromSeconds(150));
                    try{
                        await Configure(timeout.Token);T result=await action(timeout.Token);timeout.Token.ThrowIfCancellationRequested();return result;
                    }catch(Exception e){
                        ct.ThrowIfCancellationRequested();if(timeout.IsCancellationRequested)throw new TimeoutException(L.T("The server did not respond in time. Retry or update components."));
                        if(e is WebException || (e is IOException && !(e is InvalidDataException)))throw new InnerTubeException(InnerTubeFailure.Network,L.T("InnerTube request failed. Check your connection and retry."));
                        throw;
                    }
                }
            }finally{gate.Release();if(progress!=null)progress(L.T("InnerTube operation: {0:0.0} s",clock.Elapsed.TotalSeconds));}
        }
        async Task Configure(CancellationToken ct){
            if(context!=null && DateTime.UtcNow-configured<TimeSpan.FromMinutes(15))return;
            backoff.ThrowIfPaused();
            context=null;lists.Clear();cookies=new CookieContainer(512,128,8192);
            if(authenticated){
                string text;
                if(settings.Auth=="saved")text=SavedSession.ReadCookies();
                else if(settings.Auth=="file"){
                    if(!File.Exists(settings.CookiesFile))throw new FileNotFoundException(L.T("Choose an existing cookies.txt in account settings."));
                    if(new FileInfo(settings.CookiesFile).Length>8*1024*1024)throw new InvalidDataException(L.T("Invalid cookies file."));
                    text=File.ReadAllText(settings.CookiesFile,Encoding.UTF8);
                }else if(settings.Auth=="browser" || settings.Auth=="firefox")text=await SavedSession.ExportBrowser(BrowserSessions.Browser(settings),BrowserSessions.Profile(settings),ct);
                else throw new InvalidOperationException(L.T("Connect a browser session in Account first."));
                cookies=CookieJar(text,DateTime.UtcNow);
            }
            string html=await Send("/?hl="+language,null,ct,false);
            var config=InnerTubeJson.Embedded(html,"ytcfg.set(");
            var client=InnerTubeJson.Map(InnerTubeJson.At(config,"INNERTUBE_CONTEXT","client"));
            version=Json.Str(config,"INNERTUBE_CLIENT_VERSION",Json.Str(client,"clientVersion"));
            if(version.Length==0)throw new InvalidOperationException(L.T("InnerTube configuration is unavailable. YouTube may require consent or have changed its API."));
            visitor=Json.Str(config,"VISITOR_DATA",Json.Str(client,"visitorData"));
            sessionIndex=Json.Str(config,"SESSION_INDEX","0");pageId=Json.Str(config,"DELEGATED_SESSION_ID");
            loggedIn=Json.Str(config,"LOGGED_IN").Equals("true",StringComparison.OrdinalIgnoreCase);
            context=new Dictionary<string,object>{{"client",new Dictionary<string,object>{{"clientName","WEB"},{"clientVersion",version},{"hl",language},{"gl","US"},{"timeZone","UTC"},{"utcOffsetMinutes",0},{"visitorData",visitor}}}};
            configured=DateTime.UtcNow;
        }
        public static CookieContainer CookieJar(string text,DateTime now){
            var jar=new CookieContainer(512,128,8192);
            foreach(string line in SavedSession.Filter(text).Split('\n')){
                string[] p=line.TrimEnd('\r').Split('\t');long expiry;
                if(p.Length!=7 || !Int64.TryParse(p[4],out expiry) || expiry<0 || (expiry!=0 && expiry<=(long)(now.ToUniversalTime()-new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc)).TotalSeconds))continue;
                string host=p[0].Replace("#HttpOnly_","");
                try{
                    if(!p[2].StartsWith("/",StringComparison.Ordinal) || p[5].IndexOfAny(new[]{'\r','\n',';','=',',',' '})>=0 || p[6].IndexOfAny(new[]{'\r','\n',';'})>=0)continue;
                    var cookie=new Cookie(p[5],p[6],p[2],p[1]=="TRUE"?"."+host.TrimStart('.'):host.TrimStart('.')){Secure=p[3]=="TRUE",HttpOnly=p[0].StartsWith("#HttpOnly_",StringComparison.Ordinal)};
                    if(expiry!=0)cookie.Expires=new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc).AddSeconds(expiry);
                    jar.Add(cookie);
                }catch(CookieException){}catch(ArgumentOutOfRangeException){}
            }
            return jar;
        }
        public static string Authorization(CookieContainer jar,long timestamp){
            var found=jar.GetCookies(new Uri(Origin+"/"));
            string sap=found["SAPISID"]==null?"":found["SAPISID"].Value;
            string first=found["__Secure-1PAPISID"]==null?"":found["__Secure-1PAPISID"].Value;
            string third=found["__Secure-3PAPISID"]==null?"":found["__Secure-3PAPISID"].Value;
            if(sap.Length==0)sap=third;
            var parts=new List<string>();
            foreach(var pair in new[]{new[]{"SAPISIDHASH",sap},new[]{"SAPISID1PHASH",first},new[]{"SAPISID3PHASH",third}}){
                if(pair[1].Length==0)continue;
                string time=timestamp.ToString(CultureInfo.InvariantCulture);
                using(var sha=SHA1.Create())parts.Add(pair[0]+" "+time+"_"+BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(time+" "+pair[1]+" "+Origin))).Replace("-","").ToLowerInvariant());
            }
            return String.Join(" ",parts);
        }
        async Task<string> Send(string path,Dictionary<string,object> body,CancellationToken ct,bool api){
            ct.ThrowIfCancellationRequested();
            backoff.ThrowIfPaused();
            var request=new InnerTubeRequest{Path=path,Cookies=cookies,Body=body==null?null:Json.Serializer().Serialize(body)};
            if(api){
                request.Headers["X-Youtube-Client-Name"]="1";request.Headers["X-Youtube-Client-Version"]=version;
                if(visitor.Length>0)request.Headers["X-Goog-Visitor-Id"]=visitor;
                if(authenticated){
                    string auth=Authorization(cookies,(long)(DateTime.UtcNow-new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc)).TotalSeconds);
                    if(auth.Length>0){request.Headers["Authorization"]=auth;request.Headers["X-Origin"]=Origin;request.Headers["X-Goog-AuthUser"]=sessionIndex;if(pageId.Length>0)request.Headers["X-Goog-PageId"]=pageId;}
                }
            }
            try{return await send(request,ct);}
            catch(InnerTubeException error){backoff.Observe(error);throw;}
            catch(WebException){ct.ThrowIfCancellationRequested();throw new InnerTubeException(InnerTubeFailure.Network,L.T("InnerTube request failed. Check your connection and retry."));}
        }
        async Task<Dictionary<string,object>> Api(string api,Dictionary<string,object> body,CancellationToken ct){
            if(!new[]{"browse","search","navigation/resolve_url"}.Contains(api))throw new ArgumentException("Invalid catalogue endpoint.");
            body["context"]=context;
            string json=await Send("/youtubei/v1/"+api+"?prettyPrint=false",body,ct,true);Dictionary<string,object> root;
            try{root=Json.Object(json);}catch(ArgumentException){throw InnerTubeException.InvalidResponse();}
            if(root==null)throw InnerTubeException.InvalidResponse();
            if(root.ContainsKey("error")){
                int status;if(Int32.TryParse(InnerTubeJson.StringAt(root,"error","code"),out status) && status>=400 && status<=599){var error=InnerTubeException.Http(status);backoff.Observe(error);throw error;}
                throw InnerTubeException.InvalidResponse();
            }
            if(InnerTubeJson.StringAt(root,"responseContext","mainAppWebResponseContext","loggedOut").Equals("true",StringComparison.OrdinalIgnoreCase))loggedIn=false;
            string visitorData=InnerTubeJson.StringAt(root,"responseContext","visitorData");
            if(visitorData.Length>0){visitor=visitorData;InnerTubeJson.Map(InnerTubeJson.At(context,"client"))["visitorData"]=visitor;}
            if(Json.Items(root,"alerts").Any(x=>InnerTubeJson.StringAt(x,"alertRenderer","type")=="ERROR"))throw new InvalidOperationException(L.T("YouTube could not open this list. It may be private, unavailable, or require a fresh session."));
            return root;
        }
        void RequireAccount(){if(!loggedIn)throw new InnerTubeException(InnerTubeFailure.Session,L.T("YouTube did not accept the session. Import a fresh signed-in session in Account."));}
        static Video CopyVideo(Video v){return new Video{Id=v.Id,Title=v.Title,Channel=v.Channel,ChannelId=v.ChannelId,Published=v.Published,Kind=v.Kind,LiveStatus=v.LiveStatus,Url=v.Url,Duration=v.Duration,Date=v.Date};}
        public Task<List<Video>> Browse(string target,int page,int size,bool account,CancellationToken ct,Action<string> progress,bool fresh=false){
            return Operation(ct,progress,async token=>{
                if(account)RequireAccount();
                string videoId=ResumeRules.Key(target);
                if(videoId.Length>0 && QueueRules.PlaylistUrl(target).Length==0){if(page>0)return new List<Video>();return new List<Video>{Video.From(await GetMetadata(target,token))};}
                ListState previous;if(fresh && (!lists.TryGetValue(target,out previous) || !previous.Incomplete))lists.Remove(target);
                long first=(long)Math.Max(0,page)*Paging.Size(size);
                var state=await List(target,first,Paging.Size(size),account,token);
                return first>=state.Offset+state.Items.Count?new List<Video>():state.Items.Skip((int)(first-state.Offset)).Take(Paging.Size(size)).Where(x=>x!=null).Select(CopyVideo).ToList();
            });
        }
        public Task<PlaylistBatch> Playlist(string target,int page,int size,bool account,CancellationToken ct,Action<string> progress){
            return Operation(ct,progress,async token=>{
                if(account)RequireAccount();size=Paging.Size(size);long first=(long)Math.Max(0,page)*size;
                var state=await List(target,first,size,account,token);
                return new PlaylistBatch{Items=first>=state.Offset+state.Items.Count?new List<Video>():state.Items.Skip((int)(first-state.Offset)).Take(size).Where(x=>x!=null && x.Kind!="playlist").Select(CopyVideo).ToList(),HasMore=state.Offset+state.Items.Count>first+size || !state.Ended};
            });
        }
        public Task<List<LocalChannel>> SessionChannels(CancellationToken ct,Action<string> progress){
            return Operation(ct,progress,async token=>{RequireAccount();var state=await List(":ytchannels",0,MaxItems,true,token);return state.Channels.GroupBy(x=>x.Id).Select(x=>new LocalChannel{Id=x.First().Id,Title=x.First().Title}).ToList();});
        }
        public Task<Dictionary<string,object>> Metadata(string url,CancellationToken ct,Action<string> progress){return Operation(ct,progress,token=>GetMetadata(url,token));}
        async Task<Dictionary<string,object>> GetMetadata(string url,CancellationToken ct){
            string id=ResumeRules.Key(Rules.YouTubeUrl(url));if(id.Length==0)throw new ArgumentException(L.T("Paste a YouTube URL or an 11-character video ID."));
            var player=InnerTubeJson.Embedded(await Send("/watch?v="+id+"&hl=en",null,ct,false),"ytInitialPlayerResponse =");
            if(player.Count==0 || InnerTubeJson.StringAt(player,"videoDetails","videoId")!=id)throw InnerTubeException.InvalidResponse();
            return InnerTubeJson.Metadata(player);
        }
        public Task<LocalChannel> ResolveChannel(string input,CancellationToken ct,Action<string> progress){
            return Operation(ct,progress,async token=>{
                string target=LocalFeeds.ChannelInput(input),video=ResumeRules.Key(target);
                if(video.Length>0){var md=await GetMetadata(target,token);string channel=Json.Str(md,"channel_id");if(LocalFeeds.ValidChannelId(channel))return new LocalChannel{Id=channel,Title=Json.Str(md,"channel",channel)};}
                var endpoint=await Endpoint(target,token);var browse=InnerTubeJson.Map(InnerTubeJson.At(endpoint,"browseEndpoint"));string id=Json.Str(browse,"browseId");
                if(!LocalFeeds.ValidChannelId(id))throw new InvalidOperationException(L.T("Could not identify the channel. Paste a channel URL or @handle."));
                var root=await Api("browse",new Dictionary<string,object>(browse),token);
                string title=InnerTubeJson.StringAt(root,"metadata","channelMetadataRenderer","title");
                if(title.Length==0)title=InnerTubeJson.Text(InnerTubeJson.At(root,"header","c4TabbedHeaderRenderer","title"));
                return new LocalChannel{Id=id,Title=title.Length>0?title:id};
            });
        }
        async Task<Dictionary<string,object>> Endpoint(string target,CancellationToken ct){
            var root=await Api("navigation/resolve_url",new Dictionary<string,object>{{"url",Rules.YouTubeUrl(target)}},ct);
            return InnerTubeJson.Map(InnerTubeJson.At(root,"endpoint"));
        }
        static string Query(Uri uri,string name){foreach(string part in uri.Query.TrimStart('?').Split('&')){var pair=part.Split(new[]{'='},2);if(pair[0]==name)return pair.Length>1?Uri.UnescapeDataString(pair[1].Replace("+"," ")):"";}return "";}
        async Task<ListState> List(string target,long first,int size,bool account,CancellationToken ct){
            if(first<0 || first+size<first)throw new ArgumentOutOfRangeException("first");
            ListState state;lists.TryGetValue(target,out state);
            if(state!=null && DateTime.UtcNow-state.Created>TimeSpan.FromMinutes(10)){lists.Remove(target);state=null;}
            if(state!=null && first<state.Offset){lists.Remove(target);state=null;}
            if(state!=null)state.Incomplete=false;
            int requests=0;
            try{
                if(state==null){
                    state=new ListState();Dictionary<string,object> body;string search="",parameters="";
                    var match=Regex.Match(target,@"^ytsearch\d*:(.*)$",RegexOptions.Singleline);
                    if(match.Success)search=match.Groups[1].Value;
                    else if(target.StartsWith(":yt",StringComparison.Ordinal)){
                        string browse=target==":ytrec"?"FEwhat_to_watch":target==":ytsubs"?"FEsubscriptions":target==":ythistory"?"FEhistory":target==":ytchannels"?"FEchannels":"";
                        if(browse.Length==0)throw new ArgumentException(L.T("InnerTube returned an unsupported response. Retry later."));
                        RequireAccount();state.Api="browse";body=new Dictionary<string,object>{{"browseId",browse}};goto fetch;
                    }else{
                        var uri=new Uri(Rules.YouTubeUrl(target));if(uri.AbsolutePath=="/results"){search=Query(uri,"search_query");parameters=Query(uri,"sp");}
                    }
                    if(search.Length>0){state.Api="search";body=new Dictionary<string,object>{{"query",search}};if(parameters.Length>0)body["params"]=parameters;}
                    else{
                        string playlist=QueueRules.PlaylistUrl(target);state.Api="browse";
                        if(playlist.Length>0)body=new Dictionary<string,object>{{"browseId","VL"+Query(new Uri(playlist),"list")}};
                        else{
                            var endpoint=await Endpoint(target,ct);requests++;
                            body=new Dictionary<string,object>(InnerTubeJson.Map(InnerTubeJson.At(endpoint,"browseEndpoint")));
                            if(body.Count==0)throw new InvalidOperationException(L.T("InnerTube returned an unsupported response. Retry later."));
                        }
                    }
                    fetch:
                    var root=await Api(state.Api,body,ct);requests++;if(account)RequireAccount();Append(state,root);
                    if(lists.Count>=4)lists.Remove(lists.OrderBy(x=>x.Value.Created).First().Key);lists[target]=state;
                }
                while(!state.Ended && (target==":ytchannels"?state.Channels.Count:state.Offset+state.Items.Count)<first+size){
                    if(++requests>MaxRequests)throw new InnerTubeException(InnerTubeFailure.RequestBudget,L.T("InnerTube request limit reached. Reopen this list to continue."));
                    ct.ThrowIfCancellationRequested();
                    var root=await Api(state.Api,new Dictionary<string,object>{{"continuation",state.Continuation}},ct);if(account)RequireAccount();Append(state,root);
                }
                if(first<state.Offset)throw new InnerTubeException(InnerTubeFailure.InvalidResponse,L.T("InnerTube list limit reached. Refine the search or open a smaller list."));
                return state;
            }catch(Exception error){
                ListState cached;
                if(InnerTubeException.MayResume(error) || error is WebException || (error is IOException && !(error is InvalidDataException))){if(state!=null && lists.TryGetValue(target,out cached) && Object.ReferenceEquals(cached,state))state.Incomplete=true;}
                else lists.Remove(target);
                throw;
            }
        }
        static void Append(ListState state,Dictionary<string,object> root){
            var page=InnerTubeJson.Parse(root);
            if(!page.Recognized)throw InnerTubeException.InvalidResponse();
            if(page.Items.Count>MaxItems)throw new InvalidOperationException(L.T("InnerTube list limit reached. Refine the search or open a smaller list."));
            if(page.Continuation.Length>0 && !state.Seen.Add(page.Continuation))throw new InvalidOperationException(L.T("InnerTube repeated a page token. Reopen this list to retry."));
            if(page.Continuation.Length>0){state.Tokens.Enqueue(page.Continuation);if(state.Tokens.Count>256)state.Seen.Remove(state.Tokens.Dequeue());}
            state.Items.AddRange(page.Items);int trim=Math.Max(0,state.Items.Count-MaxItems);if(trim>0){state.Items.RemoveRange(0,trim);state.Offset+=trim;}
            state.Channels.AddRange(page.Channels.Take(Math.Max(0,MaxItems-state.Channels.Count)));state.Continuation=page.Continuation;state.Ended=page.Continuation.Length==0;
        }
        static async Task<string> Http(InnerTubeRequest input,CancellationToken ct){
            var request=(HttpWebRequest)WebRequest.Create(Origin+input.Path);
            request.Method=input.Body==null?"GET":"POST";request.CookieContainer=input.Cookies;request.AllowAutoRedirect=false;
            request.UserAgent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
            request.Accept="*/*";request.Referer=Origin+"/";request.AutomaticDecompression=DecompressionMethods.GZip|DecompressionMethods.Deflate;
            request.Timeout=25000;request.ReadWriteTimeout=25000;request.Headers["Origin"]=Origin;
            foreach(var header in input.Headers)request.Headers[header.Key]=header.Value;
            using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct)){
                timeout.CancelAfter(25000);
                using(timeout.Token.Register(delegate{request.Abort();}))try{
                    if(input.Body!=null){byte[] bytes=Encoding.UTF8.GetBytes(input.Body);request.ContentType="application/json";request.ContentLength=bytes.Length;using(var output=await request.GetRequestStreamAsync())await output.WriteAsync(bytes,0,bytes.Length,timeout.Token);}
                    using(var response=(HttpWebResponse)await request.GetResponseAsync()){
                        if(response.StatusCode!=HttpStatusCode.OK)throw InnerTubeException.Http((int)response.StatusCode,response.Headers["Retry-After"]);
                        const int limit=8*1024*1024;if(response.ContentLength>limit)throw new InvalidDataException(L.T("InnerTube response exceeds 8 MB. Request fewer results."));
                        using(var stream=response.GetResponseStream())using(var buffer=new MemoryStream()){
                            byte[] bytes=new byte[8192];int read;
                            while((read=await stream.ReadAsync(bytes,0,bytes.Length,timeout.Token))>0){if(buffer.Length+read>limit)throw new InvalidDataException(L.T("InnerTube response exceeds 8 MB. Request fewer results."));buffer.Write(bytes,0,read);}
                            return Encoding.UTF8.GetString(buffer.ToArray());
                        }
                    }
                }catch(WebException e){
                    ct.ThrowIfCancellationRequested();if(timeout.IsCancellationRequested)throw new TimeoutException(L.T("The server did not respond in time. Retry or update components."));
                    var response=e.Response as HttpWebResponse;if(response!=null){var failure=InnerTubeException.Http((int)response.StatusCode,response.Headers["Retry-After"]);response.Dispose();throw failure;}
                    throw new InnerTubeException(InnerTubeFailure.Network,L.T("InnerTube request failed. Check your connection and retry."));
                }catch(OperationCanceledException){
                    ct.ThrowIfCancellationRequested();throw new TimeoutException(L.T("The server did not respond in time. Retry or update components."));
                }catch(IOException){
                    ct.ThrowIfCancellationRequested();if(timeout.IsCancellationRequested)throw new TimeoutException(L.T("The server did not respond in time. Retry or update components."));throw;
                }
            }
        }
    }
}
