using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using LowTube;

public static class VisiblePagingTests {
    static int checks;
    static Dictionary<string,object> Obj(params object[] p){var d=new Dictionary<string,object>();for(int i=0;i<p.Length;i+=2)d[(string)p[i]]=p[i+1];return d;}
    static string Encode(object o){return Json.Serializer().Serialize(o);}
    static object Video(int n,bool shorts){return Obj(shorts?"reelItemRenderer":"videoRenderer",Obj("videoId","abcdefghijk","title",Obj("simpleText",(shorts?"s":"v")+n)));}
    static object Token(int n){return Obj("continuationItemRenderer",Obj("continuationEndpoint",Obj("continuationCommand",Obj("token",n.ToString()))));}
    static string First(object[] items){return Encode(Obj("contents",Obj("twoColumnSearchResultsRenderer",Obj("primaryContents",Obj("sectionListRenderer",Obj("contents",items))))));}
    static string Next(object[] items){return Encode(Obj("onResponseReceivedActions",new[]{Obj("appendContinuationItemsAction",Obj("continuationItems",items))}));}
    static string Config(){return "ytcfg.set("+Encode(Obj("INNERTUBE_CLIENT_VERSION","fixture","LOGGED_IN",false))+");";}
    static void Check(bool ok,string label){if(!ok)throw new Exception(label);checks++;Console.WriteLine("PASS: "+label);}
    public static int Main(){try{Run().GetAwaiter().GetResult();Console.WriteLine("TOTAL: "+checks+" visible paging checks passed");return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}}
    static async Task Run(){
        L.Set("en");int heads=0,calls=0;bool fail=false;
        var client=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());var body=Json.Object(request.Body);bool initial=!body.ContainsKey("continuation");
            int n=initial?0:Int32.Parse(Json.Str(body,"continuation"));if(initial)heads++;
            if(fail && n==53){fail=false;throw new WebException("synthetic failure");}
            calls++;var items=new List<object>();for(int i=0;i<100;i++){items.Add(Video(n*100+i,false));items.Add(Video(n*100+i,true));}if(n<59)items.Add(Token(n+1));
            return Task.FromResult(initial?First(items.ToArray()):Next(items.ToArray()));
        });
        var page=await client.BrowseVisiblePage("ytsearch:long-visible",20,250,false,"video",CancellationToken.None,null);
        Check(page.Items.Count==250 && page.Items[0].Title=="v5000" && page.Items[249].Title=="v5249" && page.HasMore,"filtered video pages advance beyond 5000 without duplicates");
        int before=calls;page=await client.BrowseVisiblePage("ytsearch:long-visible",19,250,false,"video",CancellationToken.None,null);
        Check(page.Items[0].Title=="v4750" && calls==before,"nearby previous pages remain in bounded cache");
        fail=true;bool rejected=false;try{await client.BrowseVisiblePage("ytsearch:long-visible",21,250,false,"video",CancellationToken.None,null);}catch(InnerTubeException){rejected=true;}
        Check(rejected,"temporary continuation failure remains visible");
        page=await client.BrowseVisiblePage("ytsearch:long-visible",21,250,false,"video",CancellationToken.None,null);
        Check(page.Items[0].Title=="v5250" && heads==1,"retry resumes validated filtered continuation beyond 5000");
        page=await client.BrowseVisiblePage("ytsearch:long-visible",23,250,false,"video",CancellationToken.None,null);
        Check(page.Items.Count==250 && page.Items[249].Title=="v5999" && !page.HasMore,"last filtered page has correct end state");
        before=calls;page=await client.BrowseVisiblePage("ytsearch:long-visible",0,250,false,"video",CancellationToken.None,null);
        Check(page.Items[0].Title=="v0" && calls>before && heads==2,"evicted earlier filtered pages rebuild from the beginning");
        page=await client.BrowseVisiblePage("ytsearch:long-visible",20,250,false,"shorts",CancellationToken.None,null);
        Check(page.Items.Count==250 && page.Items[0].Title=="s5000" && page.Items[249].Kind=="shorts","different content filters keep independent offsets");
        int largeCalls=0;var large=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());var body=Json.Object(request.Body);bool initial=!body.ContainsKey("continuation");int n=initial?0:1;largeCalls++;
            var items=Enumerable.Range(n*5000,5000).Select(x=>Video(x,false)).ToList();if(initial)items.Add(Token(1));return Task.FromResult(initial?First(items.ToArray()):Next(items.ToArray()));
        });
        page=await large.BrowseVisiblePage("ytsearch:large-native",33,150,false,"video",CancellationToken.None,null);
        Check(page.Items.Count==150 && page.Items[0].Title=="v4950" && page.Items[149].Title=="v5099" && largeCalls==2,"large native pages preserve the requested boundary while trimming");
        int budgetCalls=0;var sparse=new InnerTubeClient(new Settings(),false,"en",(request,ct)=>{
            if(request.Body==null)return Task.FromResult(Config());budgetCalls++;var body=Json.Object(request.Body);bool initial=!body.ContainsKey("continuation");
            int n=initial?0:Int32.Parse(Json.Str(body,"continuation"));var items=new List<object>();items.Add(Video(n,n<64));if(n<64)items.Add(Token(n+1));
            return Task.FromResult(initial?First(items.ToArray()):Next(items.ToArray()));
        });
        rejected=false;try{await sparse.BrowseVisiblePage("ytsearch:sparse",0,1,false,"video",CancellationToken.None,null,true);}catch(InnerTubeException e){rejected=e.Kind==InnerTubeFailure.RequestBudget;}
        Check(rejected && budgetCalls==64,"sparse filtered pages respect the per-operation request budget");
        page=await sparse.BrowseVisiblePage("ytsearch:sparse",0,1,false,"video",CancellationToken.None,null,true);
        Check(page.Items.Count==1 && page.Items[0].Title=="v64" && budgetCalls==65,"explicit retry resumes sparse filtered pages after the budget");
    }
}
