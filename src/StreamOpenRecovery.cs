using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace LowTube {
    public sealed class StreamOpenException : InvalidOperationException {
        public readonly string Reason;
        public bool CanRefresh {get{return Reason=="loading failed" || Reason=="unrecognized file format" || Reason=="no audio or video data played" || Reason=="unknown";}}
        public StreamOpenException(string reason):this(Normalize(reason),true){}
        StreamOpenException(string reason,bool normalized):base(L.T("Could not open the selected stream. mpv reason: {0}.",Caption(reason))){Reason=reason;}
        static string Normalize(string reason){
            switch(reason){case "loading failed":case "unrecognized file format":case "no audio or video data played":case "audio output initialization failed":case "video output initialization failed":return reason;default:return "unknown";}
        }
        static string Caption(string reason){
            switch(reason){case "loading failed":return L.T("Stream loading failed");case "unrecognized file format":return L.T("Media format was not recognized");case "no audio or video data played":return L.T("No media data could be played");case "audio output initialization failed":return L.T("Audio output initialization failed");case "video output initialization failed":return L.T("Video output initialization failed");default:return L.T("Unknown playback error");}
        }
        public static StreamOpenException FromEvent(Dictionary<string,object> message){return new StreamOpenException(Json.Str(message,"file_error",Json.Str(message,"error")));}
    }
    public static class StreamOpenRecovery {
        // A user-initiated start may refresh its URLs once. Resolve errors, output
        // errors and cancellation never enter an unbounded extractor/player loop.
        public static async Task<Dictionary<string,object>> Open(Dictionary<string,object> initial,bool enabled,Func<Task<Dictionary<string,object>>> refresh,Func<Dictionary<string,object>,Task> open,Action invalidate,CancellationToken ct,Action retry=null){
            var info=initial;
            for(int attempt=0;;attempt++){
                ct.ThrowIfCancellationRequested();
                try{await open(info);ct.ThrowIfCancellationRequested();return info;}
                catch(StreamOpenException error){
                    invalidate();ct.ThrowIfCancellationRequested();
                    if(!enabled || attempt!=0 || !error.CanRefresh)throw;
                }
                if(retry!=null)retry();ct.ThrowIfCancellationRequested();info=await refresh();
            }
        }
        // Labels only. These hints are for diagnosis, never recovery decisions.
        public static string Diagnostic(string line){
            if(Regex.IsMatch(line??"",@"(?i)(HTTP (?:error |error: |status )?|server returned )403\b|403 Forbidden"))return "HTTP 403";
            if(Regex.IsMatch(line??"",@"(?i)(HTTP (?:error |error: |status )?|server returned )429\b|429 Too Many Requests"))return "HTTP 429";
            if(Regex.IsMatch(line??"",@"(?i)(HTTP (?:error |error: |status )?|server returned )404\b|404 Not Found"))return "HTTP 404";
            if(Regex.IsMatch(line??"",@"(?i)connection timed out|network.*timed out"))return "network timeout";
            return null;
        }
        public static string FormatLabel(Dictionary<string,object> stream){
            return "id="+Safe(Json.Str(stream,"format_id"))+"; protocol="+Safe(Json.Str(stream,"protocol"))+"; vcodec="+Safe(Json.Str(stream,"vcodec"))+"; acodec="+Safe(Json.Str(stream,"acodec"));
        }
        static string Safe(string value){return value.Length<=80 && Regex.IsMatch(value,@"^[A-Za-z0-9_.+\-]*$")?value:"unavailable";}
        public static string SafeLog(string line){
            if(Regex.IsMatch(line??"",@"(?i)authorization\s*:|cookie\s*:|set-cookie\s*:"))return "[Private headers hidden]";
            return Regex.Replace(line??"",@"https?://[^\s\""<>]+","[URL]");
        }
    }
}
