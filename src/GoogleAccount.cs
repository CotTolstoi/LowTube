using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
namespace LowTube {
    public sealed class GoogleClient {public string Id="",Secret="";}
    public sealed class GoogleSession {public string ClientId="",Access="",Refresh="",Title="";public long Expires;}
    public static class GoogleAccount {
        public const string Scope="https://www.googleapis.com/auth/youtube.readonly";
        static string ConfigPath {get{return Path.Combine(Paths.Data,"google-client.json");}}
        static string TokenPath {get{return Path.Combine(Paths.Data,"google-session.bin");}}
        static readonly byte[] entropy=Encoding.UTF8.GetBytes("LowTube.Google.OAuth.v1");
        public static bool Configured {get{return File.Exists(ConfigPath);}}
        public static bool Connected {get{return File.Exists(TokenPath);}}
        public static Dictionary<string,object> Child(Dictionary<string,object> d,string key){object v;return d!=null && d.TryGetValue(key,out v)?v as Dictionary<string,object>:null;}
        public static GoogleClient ParseClient(string json){
            var installed=Child(Json.Object(json),"installed");string id=Json.Str(installed,"client_id"),secret=Json.Str(installed,"client_secret");
            if(installed==null || !System.Text.RegularExpressions.Regex.IsMatch(id,@"^[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$") || secret.Length>512)
                throw new InvalidOperationException(L.T("Choose a Google OAuth JSON file for a Desktop app."));
            return new GoogleClient{Id=id,Secret=secret};
        }
        public static void ImportClient(string path){
            if(new FileInfo(path).Length>65536)throw new InvalidOperationException(L.T("Choose a Google OAuth JSON file for a Desktop app."));
            var client=ParseClient(File.ReadAllText(path,Encoding.UTF8));
            if(Connected && LoadClient().Id!=client.Id)throw new InvalidOperationException(L.T("Disconnect Google before changing the OAuth client."));
            Json.Save(ConfigPath,new{installed=new{client_id=client.Id,client_secret=client.Secret}});
        }
        static GoogleClient LoadClient(){if(!Configured)throw new InvalidOperationException(L.T("Import your Desktop OAuth client JSON first. See Google sign-in instructions."));return ParseClient(File.ReadAllText(ConfigPath,Encoding.UTF8));}
        static GoogleSession LoadSession(){
            if(!Connected)throw new InvalidOperationException(L.T("Sign in with Google first."));
            try{return Json.Serializer().Deserialize<GoogleSession>(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(TokenPath),entropy,DataProtectionScope.CurrentUser)));}
            catch(CryptographicException){throw new InvalidOperationException(L.T("Google session cannot be opened. Disconnect and sign in again."));}
        }
        static void SaveSession(GoogleSession value){
            byte[] bytes=ProtectedData.Protect(Encoding.UTF8.GetBytes(Json.Serializer().Serialize(value)),entropy,DataProtectionScope.CurrentUser);
            Directory.CreateDirectory(Paths.Data);string temp=TokenPath+".tmp";File.WriteAllBytes(temp,bytes);if(File.Exists(TokenPath))File.Replace(temp,TokenPath,null);else File.Move(temp,TokenPath);
        }
        public static void Disconnect(){if(File.Exists(TokenPath))File.Delete(TokenPath);}
        public static string Base64Url(byte[] bytes){return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+','-').Replace('/','_');}
        public static string Challenge(string verifier){using(var hash=SHA256.Create())return Base64Url(hash.ComputeHash(Encoding.ASCII.GetBytes(verifier)));}
        static string RandomValue(){byte[] b=new byte[32];using(var random=RandomNumberGenerator.Create())random.GetBytes(b);return Base64Url(b);}
        public static string Form(Dictionary<string,string> values){return String.Join("&",values.Select(p=>Uri.EscapeDataString(p.Key)+"="+Uri.EscapeDataString(p.Value??"")));}
        // Only a fixed local path, a unique state, and a single value per parameter are accepted.
        public static Dictionary<string,string> Callback(string target,string state){
            if(target==null || !target.StartsWith("/oauth2/callback?",StringComparison.Ordinal))return null;
            var result=new Dictionary<string,string>(StringComparer.Ordinal);
            foreach(string pair in target.Substring(target.IndexOf('?')+1).Split('&')){
                var parts=pair.Split(new[]{'='},2);string key=Uri.UnescapeDataString(parts[0].Replace('+',' '));
                if(result.ContainsKey(key))return null;result[key]=parts.Length==2?Uri.UnescapeDataString(parts[1].Replace('+',' ')):"";
            }
            string actual;if(!result.TryGetValue("state",out actual) || actual!=state)return null;
            if(result.ContainsKey("code")==result.ContainsKey("error"))return null;
            return result;
        }
        static void Reply(NetworkStream stream,string status,string text){
            byte[] body=Encoding.UTF8.GetBytes(text);byte[] headers=Encoding.ASCII.GetBytes("HTTP/1.1 "+status+"\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: "+body.Length+"\r\n\r\n");stream.Write(headers,0,headers.Length);stream.Write(body,0,body.Length);
        }
        static string ReceiveCode(TcpListener listener,string state,CancellationToken ct){
            using(ct.Register(listener.Stop))while(true){
                ct.ThrowIfCancellationRequested();TcpClient socket;
                try{socket=listener.AcceptTcpClient();}catch(SocketException){ct.ThrowIfCancellationRequested();throw;}catch(InvalidOperationException){ct.ThrowIfCancellationRequested();throw;}
                using(socket)using(ct.Register(socket.Close)){
                    socket.ReceiveTimeout=3000;socket.SendTimeout=3000;
                    try{using(var stream=socket.GetStream()){
                        var bytes=new List<byte>();int b;
                        while(bytes.Count<16384 && (b=stream.ReadByte())>=0){bytes.Add((byte)b);int n=bytes.Count;if(n>=4 && bytes[n-4]==13 && bytes[n-3]==10 && bytes[n-2]==13 && bytes[n-1]==10)break;}
                        string request=Encoding.ASCII.GetString(bytes.ToArray());var line=request.Split(new[]{"\r\n"},StringSplitOptions.None)[0].Split(' ');
                        var parsed=line.Length==3 && line[0]=="GET"?Callback(line[1],state):null;
                        if(parsed==null){Reply(stream,"400 Bad Request","This request does not match the current LowTube sign-in.");continue;}
                        Reply(stream,"200 OK",L.T("Return to LowTube to finish signing in. You can close this tab."));
                        if(parsed.ContainsKey("error"))throw new InvalidOperationException(L.T("Google sign-in was not authorized."));
                        if(String.IsNullOrEmpty(parsed["code"]))throw new InvalidOperationException(L.T("Google sign-in was not authorized."));return parsed["code"];
                    }}catch(IOException){ct.ThrowIfCancellationRequested();}catch(ObjectDisposedException){ct.ThrowIfCancellationRequested();}catch(UriFormatException){}
                }
            }
        }
        public static async Task SignIn(CancellationToken cancellation){
            var client=LoadClient();string state=RandomValue(),verifier=RandomValue();var listener=new TcpListener(IPAddress.Loopback,0);
            using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(cancellation)){
                timeout.CancelAfter(TimeSpan.FromMinutes(5));var ct=timeout.Token;listener.Start(4);
                try{
                    string redirect="http://127.0.0.1:"+((IPEndPoint)listener.LocalEndpoint).Port+"/oauth2/callback";
                    string url="https://accounts.google.com/o/oauth2/v2/auth?"+Form(new Dictionary<string,string>{{"client_id",client.Id},{"redirect_uri",redirect},{"response_type","code"},{"scope",Scope},{"state",state},{"code_challenge",Challenge(verifier)},{"code_challenge_method","S256"},{"access_type","offline"},{"prompt","consent select_account"}});
                    Process.Start(new ProcessStartInfo(url){UseShellExecute=true});
                    string code=await Task.Run(()=>ReceiveCode(listener,state,ct),ct);
                    var data=await Request("https://oauth2.googleapis.com/token",new Dictionary<string,string>{{"client_id",client.Id},{"client_secret",client.Secret},{"code",code},{"code_verifier",verifier},{"redirect_uri",redirect},{"grant_type","authorization_code"}},null,ct);
                    string access=Json.Str(data,"access_token"),granted=Json.Str(data,"scope",Scope);
                    if(access.Length==0 || !granted.Split(' ').Contains(Scope))throw new InvalidOperationException(L.T("YouTube read permission was not granted."));
                    var mine=await Request("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true",null,access,ct);
                    string title=Json.Str(Child(Json.Items(mine,"items").FirstOrDefault(),"snippet"),"title",L.T("Google account"));
                    ct.ThrowIfCancellationRequested();SaveSession(new GoogleSession{ClientId=client.Id,Access=access,Refresh=Json.Str(data,"refresh_token"),Title=title,Expires=Expiry(data)});
                }finally{listener.Stop();}
            }
        }
        static long Expiry(Dictionary<string,object> data){int seconds;return DateTime.UtcNow.AddSeconds(Int32.TryParse(Json.Str(data,"expires_in"),out seconds)?Math.Max(60,Math.Min(86400,seconds)):3600).Ticks;}
        static async Task<string> Access(CancellationToken ct){
            var client=LoadClient();var token=LoadSession();if(token.ClientId!=client.Id)throw new InvalidOperationException(L.T("Sign in with Google first."));
            if(token.Expires>DateTime.UtcNow.AddMinutes(1).Ticks)return token.Access;
            if(token.Refresh.Length==0)throw new InvalidOperationException(L.T("Sign in with Google first."));
            var data=await Request("https://oauth2.googleapis.com/token",new Dictionary<string,string>{{"client_id",client.Id},{"client_secret",client.Secret},{"refresh_token",token.Refresh},{"grant_type","refresh_token"}},null,ct);
            token.Access=Json.Str(data,"access_token");if(token.Access.Length==0)throw new InvalidOperationException(L.T("Sign in with Google first."));token.Expires=Expiry(data);ct.ThrowIfCancellationRequested();SaveSession(token);return token.Access;
        }
        public static async Task<List<LocalChannel>> Subscriptions(CancellationToken ct){
            string access=await Access(ct),page="";var result=new List<LocalChannel>();var ids=new HashSet<string>();var pages=new HashSet<string>();
            do{
                var data=await Request("https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50&pageToken="+Uri.EscapeDataString(page),null,access,ct);
                foreach(var item in Json.Items(data,"items")){
                    var snippet=Child(item,"snippet");string id=Json.Str(Child(snippet,"resourceId"),"channelId");if(LocalFeeds.ValidChannelId(id) && ids.Add(id))result.Add(new LocalChannel{Id=id,Title=Json.Str(snippet,"title")});
                }
                page=Json.Str(data,"nextPageToken");if(page.Length>0 && !pages.Add(page))throw new InvalidOperationException(L.T("Google returned an invalid response."));
            }while(page.Length>0);
            return result;
        }
        public static async Task Revoke(CancellationToken ct){
            var token=LoadSession();await Request("https://oauth2.googleapis.com/revoke",new Dictionary<string,string>{{"token",token.Refresh.Length>0?token.Refresh:token.Access}},null,ct);Disconnect();
        }
        static Task<Dictionary<string,object>> Request(string url,Dictionary<string,string> form,string bearer,CancellationToken ct){
            return Task.Run(delegate{
                var request=(HttpWebRequest)WebRequest.Create(url);request.AllowAutoRedirect=false;request.Timeout=30000;request.ReadWriteTimeout=30000;
                if(bearer!=null)request.Headers[HttpRequestHeader.Authorization]="Bearer "+bearer;
                using(ct.Register(request.Abort))try{
                    if(form!=null){request.Method="POST";request.ContentType="application/x-www-form-urlencoded";byte[] bytes=Encoding.UTF8.GetBytes(Form(form));request.ContentLength=bytes.Length;using(var output=request.GetRequestStream())output.Write(bytes,0,bytes.Length);}
                    using(var response=(HttpWebResponse)request.GetResponse()){
                        if((int)response.StatusCode>=300)throw new InvalidOperationException(L.T("Google returned an invalid response."));
                        using(var input=response.GetResponseStream())using(var data=new MemoryStream()){
                            byte[] buffer=new byte[8192];int n;while((n=input.Read(buffer,0,buffer.Length))>0){ct.ThrowIfCancellationRequested();if(data.Length+n>4*1024*1024)throw new InvalidOperationException(L.T("Google returned an invalid response."));data.Write(buffer,0,n);}
                            string json=Encoding.UTF8.GetString(data.ToArray());return String.IsNullOrWhiteSpace(json)?new Dictionary<string,object>():Json.Object(json);
                        }
                    }
                }catch(WebException e){ct.ThrowIfCancellationRequested();var response=e.Response as HttpWebResponse;int code=response==null?0:(int)response.StatusCode;if(response!=null)response.Dispose();throw new InvalidOperationException(L.T("Google request failed (HTTP {0}). Check your connection, OAuth setup and YouTube API access; sign in again if needed.",code));}
            },ct);
        }
    }
}
