using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
namespace LowTube {
    public static class BrowserSessions {
        public static readonly string[] Names={"firefox","chrome","edge","brave","opera","vivaldi","chromium","whale"};
        public static string Argument(string browser,string profile){
            if(!Names.Contains(browser))throw new ArgumentException(L.T("Unsupported browser."));
            profile=(profile??"").Trim();
            if(profile.IndexOfAny(new[]{'\r','\n','\0'})>=0 || profile.Contains("::"))throw new ArgumentException(L.T("Invalid profile path."));
            return browser+(profile.Length==0?"":":"+profile);
        }
        public static string Browser(Settings s){return s.Auth=="firefox"?"firefox":Names.Contains(s.BrowserName)?s.BrowserName:"firefox";}
        public static string Profile(Settings s){return s.Auth=="firefox"?s.FirefoxProfile:s.BrowserProfile;}
        // Read directory names only; cookies are read only after the user requests import.
        public static string[] Discover(string browser,string local,string roaming){
            Argument(browser,"");var found=new List<string>();
            string[] roots;
            switch(browser){
                case "firefox": roots=new[]{Path.Combine(roaming,"Mozilla","Firefox","Profiles")};break;
                case "chrome": roots=new[]{Path.Combine(local,"Google","Chrome","User Data")};break;
                case "edge": roots=new[]{Path.Combine(local,"Microsoft","Edge","User Data")};break;
                case "brave": roots=new[]{Path.Combine(local,"BraveSoftware","Brave-Browser","User Data")};break;
                case "vivaldi": roots=new[]{Path.Combine(local,"Vivaldi","User Data")};break;
                case "chromium": roots=new[]{Path.Combine(local,"Chromium","User Data")};break;
                case "whale": roots=new[]{Path.Combine(local,"Naver","Naver Whale","User Data")};break;
                default: roots=new[]{Path.Combine(roaming,"Opera Software","Opera Stable"),Path.Combine(roaming,"Opera Software","Opera GX Stable")};break;
            }
            foreach(string dir in roots)try{
                if(!Directory.Exists(dir))continue;
                if(HasCookies(dir))found.Add(dir);
                foreach(string child in Directory.GetDirectories(dir))if(HasCookies(child))found.Add(child);
            }catch(IOException){}catch(UnauthorizedAccessException){}
            return found.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();
        }
        static bool HasCookies(string dir){return File.Exists(Path.Combine(dir,"cookies.sqlite")) || File.Exists(Path.Combine(dir,"Cookies")) || File.Exists(Path.Combine(dir,"Network","Cookies"));}
        public static string CaptureError(string error){
            string value=(error??"").ToLowerInvariant();
            if(value.Contains("decrypt") || value.Contains("dpapi") || value.Contains("app-bound"))return L.T("Browser cookie encryption prevented import. Import a YouTube cookies.txt file instead. Closing the browser may not resolve encryption errors.");
            return L.T("Could not copy the browser session. Close the selected browser, check its profile, or import a YouTube cookies.txt file.");
        }
    }
    public static class SavedSession {
        static readonly byte[] entropy=Encoding.UTF8.GetBytes("LowTube.YouTube.Session.v1");
        static string Store {get{return Path.Combine(Paths.Data,"youtube-session.bin");}}
        public static bool Exists {get{return File.Exists(Store);}}
        public static string Stamp {get{return Exists?File.GetLastWriteTimeUtc(Store).Ticks.ToString():"missing";}}
        public static string Filter(string text){
            if(text==null || text.Length>8*1024*1024)throw new InvalidDataException(L.T("Invalid cookies file."));
            var output=new StringBuilder("# Netscape HTTP Cookie File\n");int count=0;
            foreach(string raw in text.Replace("\r\n","\n").Split('\n')){
                string line=raw.TrimEnd('\r');if(line.Length==0 || (line.StartsWith("#") && !line.StartsWith("#HttpOnly_")))continue;
                var parts=line.Split('\t');if(parts.Length!=7)continue;
                string host=parts[0].Replace("#HttpOnly_","").TrimStart('.').ToLowerInvariant();
                if(host!="youtube.com" && !host.EndsWith(".youtube.com",StringComparison.Ordinal))continue;
                long expiry;if(!Int64.TryParse(parts[4],out expiry) || (parts[1]!="TRUE" && parts[1]!="FALSE") || (parts[3]!="TRUE" && parts[3]!="FALSE"))continue;
                output.AppendLine(line);count++;
            }
            if(count==0)throw new InvalidDataException(L.T("No YouTube cookies found. Sign in to YouTube first."));return output.ToString();
        }
        public static bool HasSignInCookies(string text){
            long now=(long)(DateTime.UtcNow-new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc)).TotalSeconds;
            foreach(string line in Filter(text).Split('\n')){
                var fields=line.TrimEnd('\r').Split('\t');long expiry;
                if(fields.Length==7 && Int64.TryParse(fields[4],out expiry) && (expiry==0 || expiry>now) && fields[6].Length>0 && new[]{"SID","SAPISID","__Secure-1PSID","__Secure-3PSID","__Secure-1PAPISID","__Secure-3PAPISID"}.Contains(fields[5]))return true;
            }
            return false;
        }
        public static void Save(string text){
            byte[] bytes=ProtectedData.Protect(Encoding.UTF8.GetBytes(Filter(text)),entropy,DataProtectionScope.CurrentUser);
            Directory.CreateDirectory(Paths.Data);string temp=Store+"."+Guid.NewGuid().ToString("N")+".tmp";File.WriteAllBytes(temp,bytes);if(File.Exists(Store))File.Replace(temp,Store,null);else File.Move(temp,Store);Backend.ClearMetadataCache();
        }
        public static void Import(string path){if(new FileInfo(path).Length>8*1024*1024)throw new InvalidDataException(L.T("Invalid cookies file."));Save(File.ReadAllText(path,Encoding.UTF8));}
        public static void Remove(){if(File.Exists(Store))File.Delete(Store);Backend.ClearMetadataCache();}
        static string Read(){if(!Exists)throw new InvalidOperationException(L.T("Save a YouTube session in Account first."));try{return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(Store),entropy,DataProtectionScope.CurrentUser));}catch(CryptographicException){throw new InvalidOperationException(L.T("Saved session cannot be opened. Save it again on this Windows account."));}}
        // The catalogue reads the protected session directly into memory, without a
        // plaintext lease on disk. Media extraction still uses its existing lease.
        internal static string ReadCookies(){return Filter(Read());}
        public sealed class Lease : IDisposable {
            public readonly string DirectoryPath,FilePath;
            FileStream marker;
            static void Cleanup(){
                string parent=Path.Combine(Paths.Data,"session-work");if(!Directory.Exists(parent))return;
                foreach(string dir in Directory.GetDirectories(parent))try{
                    Guid id;if(!Guid.TryParseExact(Path.GetFileName(dir),"N",out id))continue;
                    // A live LowTube instance holds the marker exclusively. Do not touch it.
                    using(var probe=new FileStream(Path.Combine(dir,"active.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None)){}
                    Directory.Delete(dir,true);
                }catch(IOException){}catch(UnauthorizedAccessException){}
            }
            public Lease(bool load){
                Cleanup();
                DirectoryPath=Path.Combine(Paths.Data,"session-work",Guid.NewGuid().ToString("N"));
                FilePath=Path.Combine(DirectoryPath,"cookies.txt");
                try{
                Directory.CreateDirectory(DirectoryPath);
                marker=new FileStream(Path.Combine(DirectoryPath,"active.lock"),FileMode.CreateNew,FileAccess.ReadWrite,FileShare.None);
                if(Environment.OSVersion.Platform==PlatformID.Win32NT){
                    var acl=new DirectorySecurity();acl.SetAccessRuleProtection(true,false);var user=WindowsIdentity.GetCurrent().User;
                    acl.AddAccessRule(new FileSystemAccessRule(user,FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));new DirectoryInfo(DirectoryPath).SetAccessControl(acl);
                }
                if(load)File.WriteAllText(FilePath,Filter(Read()),new UTF8Encoding(false));}catch{Dispose();throw;}
            }
            public void Dispose(){if(marker!=null){marker.Dispose();marker=null;}try{if(Directory.Exists(DirectoryPath))Directory.Delete(DirectoryPath,true);}catch(IOException){}catch(UnauthorizedAccessException){}}
        }
        public static Task CaptureFirefox(string profile,CancellationToken ct){return CaptureBrowser("firefox",profile,ct);}
        public static async Task CaptureBrowser(string browser,string profile,CancellationToken ct,bool requireSignIn=false){
            string text=await ExportBrowser(browser,profile,ct);ct.ThrowIfCancellationRequested();
            if(requireSignIn && !HasSignInCookies(text))throw new InvalidOperationException(L.T("No sign-in cookies could be read. Sign in to YouTube in this profile. Browser encryption may also prevent import. Your saved session was not changed."));
            Save(text);
        }
        internal static async Task<string> ExportBrowser(string browser,string profile,CancellationToken ct){
            string browserArgument=BrowserSessions.Argument(browser,profile);
            if((profile??"").IndexOfAny(new[]{'\r','\n'})>=0)throw new ArgumentException(L.T("Invalid profile path."));
            using(var lease=new Lease(false)){
                string info=Path.Combine(lease.DirectoryPath,"export.json");
                // Offline fixture triggers yt-dlp's normal cookie save, without contacting YouTube.
                Json.Save(info,new{id="session-export",title="session-export",extractor="generic",webpage_url="https://www.youtube.com/",url="https://www.youtube.com/",ext="mp4"});
                var args=new[]{"--ignore-config","--no-plugin-dirs","--no-js-runtimes","--cookies-from-browser",browserArgument,"--cookies",lease.FilePath,"--load-info-json",info,"--skip-download","--quiet","--no-warnings"};
                var result=await Runner.Run(Paths.Tool("yt-dlp"),args,ct,90,true,null);ct.ThrowIfCancellationRequested();
                if(result.Code!=0 || !File.Exists(lease.FilePath))throw new InvalidOperationException(BrowserSessions.CaptureError(result.Error));
                return Filter(File.ReadAllText(lease.FilePath,Encoding.UTF8));
            }
        }
    }
}
