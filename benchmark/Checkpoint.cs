using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
namespace LowTube {
 public sealed class BenchCheckpoint {
  public int Version=12,Seconds;public string Url="",Report="",Pending="",RuntimeKey="";
  public Settings Baseline;public List<string> CompletedFormats=new List<string>();public List<BenchSavedResult> Results=new List<BenchSavedResult>();
  public BenchResult Find(string key){var row=Results.FirstOrDefault(x=>x.Key==key);return row==null?null:row.Value;}
  public void Put(string key,BenchResult value){Results.RemoveAll(x=>x.Key==key);Results.Add(new BenchSavedResult{Key=key,Value=value});}
  public static string Key(string text){using(var h=SHA256.Create())return BitConverter.ToString(h.ComputeHash(Encoding.UTF8.GetBytes(text))).Replace("-","").Substring(0,24);}
  public static Settings Clean(Settings value){var s=BenchPlan.Clone(value);s.Auth="none";s.AuthMedia=false;s.CookiesFile=s.FirefoxProfile=s.BrowserProfile="";return s;}
  public static string Runtime(){var parts=new List<string>{typeof(Player).Assembly.FullName};foreach(string name in new[]{"mpv","ffmpeg","ffprobe","yt-dlp"}){var file=new FileInfo(Paths.Tool(name));parts.Add(name+":"+file.Length+":"+file.LastWriteTimeUtc.Ticks);}return Key(String.Join("|",parts));}
  public static string VideoId(string url){var uri=new Uri(Rules.YouTubeUrl(url));if(uri.Host=="youtu.be")return uri.AbsolutePath.Trim('/');var m=System.Text.RegularExpressions.Regex.Match(uri.Query,@"(?:\?|&)v=([A-Za-z0-9_-]{11})(?:&|$)");if(m.Success)return m.Groups[1].Value;return Key(url);}
  public void ImportLegacy(string path){
   string header=File.ReadLines(path).FirstOrDefault()??"";var match=System.Text.RegularExpressions.Regex.Match(header,@"URL=([^;\s]+)");if(!match.Success || VideoId(match.Groups[1].Value)!=Url)throw new InvalidOperationException("This report belongs to another video.");

   string method=File.ReadLines(path).FirstOrDefault(x=>x.Contains("METHODOLOGY:"))??"";if(!method.Contains("measurement "+Seconds+"s."))throw new InvalidOperationException("Set the same measured duration as the old report (10 seconds for the supplied report).");
   string group="";foreach(string line in File.ReadLines(path)){
    int i=line.IndexOf(" | BEGIN FORMAT ",StringComparison.Ordinal);if(i>=0){string rest=line.Substring(i+16);int space=rest.IndexOf(' ');if(space>=0){rest=rest.Substring(space+1);int end=rest.IndexOf(" selected=",StringComparison.Ordinal);if(end>=0)group=BenchPlan.NormalizeKey(rest.Substring(0,end));}}
    if(line.Contains(" | FORMAT SUMMARY ") && group.Length>0 && !CompletedFormats.Contains(group))CompletedFormats.Add(group);
   }
  }
 }
 public sealed class BenchSavedResult {public string Key;public BenchResult Value;}
 public sealed class BenchWorkerInput {public BenchCaseData Test;public string File,Output;public int Seconds;}
 public sealed class BenchCaseData {public string Name;public Settings Options;}
}
