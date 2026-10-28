using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
namespace LowTube {
    public sealed class BrowserProfileChoice {
        public string Browser,Profile,Label;
        public bool Running;
        public override string ToString(){return Label+(Running?" ("+L.T("running")+")":"");}
    }
    public sealed class BrowserDiscoveryResult {
        public readonly List<BrowserProfileChoice> Profiles=new List<BrowserProfileChoice>();
        public bool Limited;
    }
    public static class BrowserDiscovery {
        static readonly string[] Skip={"cache","cache2","code cache","gpucache","shadercache","extensions","node_modules","crash reports","crashpad","service worker","temp","packages","lowtube","session-work"};
        public static BrowserDiscoveryResult Detect(CancellationToken ct){
            var executables=new List<string>();
            foreach(var process in Process.GetProcesses())using(process){
                ct.ThrowIfCancellationRequested();
                try{string path=process.MainModule.FileName;string dir=Path.GetDirectoryName(path);
                    if(File.Exists(Path.Combine(dir,"xul.dll")) || File.Exists(Path.Combine(dir,"chrome_elf.dll")) || Known(Path.GetFileNameWithoutExtension(path))!=null)executables.Add(path);
                }catch(System.ComponentModel.Win32Exception){}catch(InvalidOperationException){}catch(NotSupportedException){}
            }
            return Scan(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),executables,ct);
        }
        static string Known(string name){
            switch(name.ToLowerInvariant()){
                case "firefox":return "Firefox";case "chrome":return "Chrome";case "msedge":return "Edge";case "brave":return "Brave";case "opera":return "Opera";case "vivaldi":return "Vivaldi";case "chromium":return "Chromium";case "whale":return "Whale";
                default:return null;
            }
        }
        static string Nice(string path){
            string p=path.Replace('\\','/').ToLowerInvariant();
            if(p.Contains("/opera gx"))return "Opera GX";
            foreach(string name in new[]{"Firefox","LibreWolf","Waterfox","Floorp","Zen","Chrome","Edge","Brave","Vivaldi","Chromium","Whale","Opera","Thorium","Supermium","Yandex"})
                if(p.Split('/').Any(x=>x==name.ToLowerInvariant() || (x.StartsWith(name.ToLowerInvariant()+"-",StringComparison.Ordinal) || x.StartsWith(name.ToLowerInvariant()+" ",StringComparison.Ordinal)) || x=="naver whale" && name=="Whale"))return name;
            return null;
        }
        static string Engine(string name){
            switch(name){case "Firefox":case "LibreWolf":case "Waterfox":case "Floorp":case "Zen":return "firefox";case "Chrome":return "chrome";case "Edge":return "edge";case "Brave":return "brave";case "Vivaldi":return "vivaldi";case "Whale":return "whale";case "Opera":case "Opera GX":return "opera";default:return "chromium";}
        }
        public static BrowserDiscoveryResult Scan(string local,string roaming,IEnumerable<string> executables,CancellationToken ct){
            var result=new BrowserDiscoveryResult();var seen=new HashSet<string>(StringComparer.OrdinalIgnoreCase);var visited=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var running=executables.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            Action<string,string> add=delegate(string path,string fallback){
                ct.ThrowIfCancellationRequested();
                if(!Directory.Exists(path))return;
                string engine=null,root=path;
                if(File.Exists(Path.Combine(path,"cookies.sqlite")))engine="firefox";
                else if(!String.Equals(Path.GetFileName(path),"Network",StringComparison.OrdinalIgnoreCase) && (File.Exists(Path.Combine(path,"Cookies")) || File.Exists(Path.Combine(path,"Network","Cookies")))){
                    for(int i=0;i<4 && !File.Exists(Path.Combine(root,"Local State"));i++){var parent=Directory.GetParent(root);if(parent==null)break;root=parent.FullName;}
                    if(File.Exists(Path.Combine(root,"Local State")))engine="chromium";
                }
                if(engine==null)return;
                path=Path.GetFullPath(path);if(!seen.Add(path))return;
                string labelRoot=engine=="firefox"?Path.GetDirectoryName(path):root;
                if(String.Equals(Path.GetFileName(labelRoot),"User Data",StringComparison.OrdinalIgnoreCase) || String.Equals(Path.GetFileName(labelRoot),"Profiles",StringComparison.OrdinalIgnoreCase))labelRoot=Path.GetDirectoryName(labelRoot);
                string name=Nice(path)??fallback??Path.GetFileName(labelRoot);
                if(String.IsNullOrWhiteSpace(name))name=engine=="firefox"?"Firefox-compatible":"Chromium-compatible";
                string profileName=Path.GetFileName(path);
                // Human-readable Chromium profile names, not account emails or cookie contents.
                if(engine=="chromium")try{
                    string state=Path.Combine(root,"Local State");if(new FileInfo(state).Length<=4*1024*1024){
                        var data=new JsonCodec().Deserialize<Dictionary<string,object>>(File.ReadAllText(state));object profile,cache,item,value;
                        if(data.TryGetValue("profile",out profile) && profile is Dictionary<string,object> && ((Dictionary<string,object>)profile).TryGetValue("info_cache",out cache) && cache is Dictionary<string,object> && ((Dictionary<string,object>)cache).TryGetValue(profileName,out item) && item is Dictionary<string,object> && ((Dictionary<string,object>)item).TryGetValue("name",out value) && value is string && ((string)value).Length>0 && ((string)value).Length<100)profileName=(string)value;
                    }
                }catch(IOException){}catch(UnauthorizedAccessException){}catch(ArgumentException){}
                bool active=running.Any(x=>String.Equals(Nice(x)??Known(Path.GetFileNameWithoutExtension(x)),name,StringComparison.OrdinalIgnoreCase) || path.StartsWith(Path.GetDirectoryName(x)+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase));
                result.Profiles.Add(new BrowserProfileChoice{Browser=engine=="firefox"?"firefox":Engine(name),Profile=path,Label=name+" — "+profileName,Running=active});
            };
            // Known roots first so a scan limit never hides the common browsers.
            foreach(string browser in BrowserSessions.Names)foreach(string path in BrowserSessions.Discover(browser,local,roaming))add(path,browser);
            var queue=new Queue<Tuple<string,int,string>>();
            foreach(string exe in running){
                string dir=Path.GetDirectoryName(exe);queue.Enqueue(Tuple.Create(dir,0,Known(Path.GetFileNameWithoutExtension(exe))??Path.GetFileNameWithoutExtension(exe)));
                // PortableApps stores profiles beside App, beneath Data, not beside the binary.
                var parent=Directory.GetParent(dir);for(int i=0;i<3 && parent!=null;i++,parent=parent.Parent){string data=Path.Combine(parent.FullName,"Data");if(Directory.Exists(data))queue.Enqueue(Tuple.Create(data,0,Known(Path.GetFileNameWithoutExtension(exe))??Path.GetFileNameWithoutExtension(exe)));}
            }
            queue.Enqueue(Tuple.Create(roaming,0,(string)null));queue.Enqueue(Tuple.Create(local,0,(string)null));
            var watch=Stopwatch.StartNew();int count=0;
            while(queue.Count>0){
                ct.ThrowIfCancellationRequested();if(count>=5000 || watch.Elapsed.TotalSeconds>12){result.Limited=true;break;}
                var entry=queue.Dequeue();string dir=entry.Item1;
                try{
                    if(!Directory.Exists(dir) || !visited.Add(Path.GetFullPath(dir)) || (File.GetAttributes(dir)&FileAttributes.ReparsePoint)!=0)continue;
                    count++;add(dir,entry.Item3);
                    // Firefox profiles.ini also registers profiles outside standard directories.
                    string ini=Path.Combine(dir,"profiles.ini");if(File.Exists(ini) && new FileInfo(ini).Length<1024*1024)foreach(string line in File.ReadLines(ini))if(line.StartsWith("Path=",StringComparison.OrdinalIgnoreCase)){
                        string target=line.Substring(5).Trim();if(target.Length>0)add(Path.IsPathRooted(target)?target:Path.Combine(dir,target.Replace('/',Path.DirectorySeparatorChar)),Nice(dir)??Path.GetFileName(dir));
                    }
                    if(entry.Item2>=6)continue;
                    foreach(string child in Directory.GetDirectories(dir))if(!Skip.Contains(Path.GetFileName(child).ToLowerInvariant()))queue.Enqueue(Tuple.Create(child,entry.Item2+1,entry.Item3));
                }catch(IOException){}catch(UnauthorizedAccessException){}catch(ArgumentException){}
            }
            // Distinguish identical profile labels without exposing paths in the main flow.
            foreach(var group in result.Profiles.GroupBy(x=>x.Label).Where(x=>x.Count()>1)){int i=1;foreach(var item in group)item.Label+=" ("+(i++)+")";}
            result.Profiles.Sort((a,b)=>a.Running!=b.Running?(a.Running?-1:1):StringComparer.CurrentCultureIgnoreCase.Compare(a.Label,b.Label));return result;
        }
    }
}
