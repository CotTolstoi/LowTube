using System;
using System.Globalization;

namespace LowTube {
    public enum InnerTubeFailure { Network,Http,InvalidResponse,Session,RequestBudget,Cooldown }

    // Only fixed captions and numeric status/delay values reach the application log.
    // Raw response bodies, headers, cookie values and continuation tokens never do.
    public sealed class InnerTubeException : InvalidOperationException {
        public readonly InnerTubeFailure Kind;
        public readonly int StatusCode;
        internal readonly string RetryAfter;
        public InnerTubeException(InnerTubeFailure kind,string message,int statusCode=0,string retryAfter=null):base(message){Kind=kind;StatusCode=statusCode;RetryAfter=retryAfter;}
        public bool CanResume {get{return Kind==InnerTubeFailure.Network || Kind==InnerTubeFailure.RequestBudget || Kind==InnerTubeFailure.Cooldown || (Kind==InnerTubeFailure.Http && (StatusCode==408 || StatusCode==429 || StatusCode>=500));}}
        public bool StopsBatch {get{return Kind==InnerTubeFailure.Network || Kind==InnerTubeFailure.Session || Kind==InnerTubeFailure.Cooldown || (Kind==InnerTubeFailure.Http && (StatusCode==401 || StatusCode==403 || StatusCode==429 || StatusCode>=500));}}
        public static InnerTubeException Http(int status,string retryAfter=null){return new InnerTubeException(InnerTubeFailure.Http,L.T("InnerTube HTTP {0}. Check the connection, session or consent in your browser.",status),status,retryAfter);}
        public static InnerTubeException InvalidResponse(){return new InnerTubeException(InnerTubeFailure.InvalidResponse,L.T("InnerTube returned an unsupported response. Retry later."));}
        public static bool MayResume(Exception error){var failure=error as InnerTubeException;return error is OperationCanceledException || error is TimeoutException || (failure!=null && failure.CanResume);}
    }

    // No timers, sleeps, retries or background work. Callers explicitly request a
    // retry; until then cached pages remain readable and HTTP fails locally.
    public sealed class InnerTubeBackoff {
        readonly object gate=new object();
        readonly Func<DateTime> utcNow;
        DateTime until=DateTime.MinValue;
        public InnerTubeBackoff(Func<DateTime> utcNow=null){this.utcNow=utcNow??(()=>DateTime.UtcNow);}
        public int RemainingSeconds {get{lock(gate)return (int)Math.Min(86400,Math.Max(0,Math.Ceiling((until-utcNow()).TotalSeconds)));}}
        public void ThrowIfPaused(){int remaining=RemainingSeconds;if(remaining>0)throw new InnerTubeException(InnerTubeFailure.Cooldown,L.T("YouTube paused catalogue requests. Retry in {0} s.",remaining));}
        public void Observe(InnerTubeException error){
            if(error.StatusCode!=429 && error.StatusCode!=503)return;
            DateTime now=utcNow();double seconds=error.StatusCode==429?60:30;long delta;DateTimeOffset date;
            if(Int64.TryParse(error.RetryAfter,NumberStyles.None,CultureInfo.InvariantCulture,out delta))seconds=delta;
            else if(DateTimeOffset.TryParseExact(error.RetryAfter,"r",CultureInfo.InvariantCulture,DateTimeStyles.AssumeUniversal,out date))seconds=(date.UtcDateTime-now).TotalSeconds;
            seconds=Math.Max(1,Math.Min(86400,seconds));DateTime next=now.AddSeconds(seconds);
            lock(gate){if(next>until)until=next;}
        }
    }
}
