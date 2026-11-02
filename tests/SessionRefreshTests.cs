using System;
using System.Threading;
using System.Threading.Tasks;
using LowTube;

public static class SessionRefreshTests {
    static int checks;
    const string Old=".youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\tfixture-session\n.youtube.com\tTRUE\t/\tTRUE\t4102444800\tPREF\told\n";
    const string Updated=".youtube.com\tTRUE\t/\tTRUE\t4102444800\tPREF\tnew\n.youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\tfixture-session\n";
    static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;Console.WriteLine("PASS: "+label);}
    static async Task<bool> Refresh(Func<CancellationToken,Task<string>> export,Func<string> stamp,Action<string> save,CancellationToken ct){return await SavedSession.RefreshExisting(export,()=>Old,stamp,save,ct);}
    public static int Main(){try{Run().GetAwaiter().GetResult();Console.WriteLine("TOTAL: "+checks+" session refresh checks passed");return 0;}catch(Exception e){Console.Error.WriteLine(e.GetType().Name+": "+e.Message);return 1;}}
    static async Task Run(){
        L.Set("en");int saves=0;string stored="";
        bool changed=await Refresh(ct=>Task.FromResult(Updated),()=>"same",value=>{saves++;stored=value;},CancellationToken.None);
        Check(changed && saves==1 && stored==Updated,"matching signed-in profile updates the saved session once");
        saves=0;changed=await Refresh(ct=>Task.FromResult(Old),()=>"same",value=>saves++,CancellationToken.None);
        Check(!changed && saves==0,"unchanged cookies do not rewrite the saved session or invalidate feed cache");
        string reordered=".youtube.com\tTRUE\t/\tTRUE\t4102444800\tPREF\told\n.youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\tfixture-session\n";
        Check(SavedSession.SameCookies(Old,reordered),"cookie ordering alone does not trigger a refresh");
        bool rejected=false;saves=0;try{await Refresh(ct=>Task.FromResult(Updated.Replace("fixture-session","different-account")),()=>"same",value=>saves++,CancellationToken.None);}catch(InvalidOperationException){rejected=true;}
        Check(rejected && saves==0,"a different signed-in session cannot replace the saved session");
        rejected=false;int calls=0;try{await Refresh(ct=>Task.FromResult(Updated),()=>++calls==1?"original":"changed",value=>saves++,CancellationToken.None);}catch(InvalidOperationException){rejected=true;}
        Check(rejected && saves==0,"a concurrent session replacement is not overwritten");
        var cancellation=new CancellationTokenSource();rejected=false;try{await Refresh(ct=>{cancellation.Cancel();return Task.FromResult(Updated);},()=>"same",value=>saves++,cancellation.Token);}catch(OperationCanceledException){rejected=true;}
        Check(rejected && saves==0,"cancelled browser export cannot replace the saved session");
        rejected=false;try{await Refresh(ct=>Task.FromException<string>(new InvalidOperationException()),()=>"same",value=>saves++,CancellationToken.None);}catch(InvalidOperationException){rejected=true;}
        Check(rejected && saves==0,"browser export failure keeps the previous session");
    }
}
