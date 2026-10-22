using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using LowTube;
public static class GoogleTests {
    static int count;
    static void Check(bool value,string name){if(!value)throw new Exception("FAIL: "+name);count++;Console.WriteLine("PASS: "+name);}
    static void Reject(string json,string name){try{GoogleAccount.ParseClient(json);}catch(InvalidOperationException){Check(true,name);return;}throw new Exception(name);}
    static string Send(int port,string target){using(var tcp=new TcpClient("127.0.0.1",port)){tcp.ReceiveTimeout=5000;using(var stream=tcp.GetStream()){byte[] b=Encoding.ASCII.GetBytes("GET "+target+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nConnection: close\r\n\r\n");stream.Write(b,0,b.Length);using(var reader=new StreamReader(stream))return reader.ReadToEnd();}}}
    static string Receive(TcpListener server,string state,CancellationToken ct){try{return (string)typeof(GoogleAccount).GetMethod("ReceiveCode",BindingFlags.NonPublic|BindingFlags.Static).Invoke(null,new object[]{server,state,ct});}catch(TargetInvocationException e){throw e.InnerException;}}
    public static int Main(){try{
        Check(GoogleAccount.Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")=="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM","RFC 7636 PKCE S256 vector");
        Check(GoogleAccount.Base64Url(new byte[]{251,255})=="-_8","base64url no padding");
        var client=GoogleAccount.ParseClient("{\"installed\":{\"client_id\":\"123-abc.apps.googleusercontent.com\",\"client_secret\":\"secret\"}}");
        Check(client.Id=="123-abc.apps.googleusercontent.com" && client.Secret=="secret","desktop client import");
        Reject("{\"web\":{\"client_id\":\"123.apps.googleusercontent.com\"}}","reject web client");
        Reject("{\"installed\":{\"client_id\":\"123.apps.googleusercontent.com.evil.test\"}}","reject wrong client suffix");
        Reject("{\"installed\":{\"client_id\":\"https://evil.test\"}}","reject URL client ID");
        Check(GoogleAccount.Callback("/oauth2/callback?state=abc&code=a%2Bb","abc")["code"]=="a+b","code URL decoding");
        Check(GoogleAccount.Callback("/oauth2/callback?state=wrong&code=a","abc")==null,"reject wrong state");
        Check(GoogleAccount.Callback("/oauth2/callback?code=a","abc")==null,"reject missing state");
        Check(GoogleAccount.Callback("/oauth2/callback?state=abc&state=abc&code=a","abc")==null,"reject duplicate state");
        Check(GoogleAccount.Callback("/oauth2/callback?state=abc&code=a&code=b","abc")==null,"reject duplicate code");
        Check(GoogleAccount.Callback("/oauth2/callback?state=abc&code=a&error=x","abc")==null,"reject mixed success and error");
        Check(GoogleAccount.Callback("/other?state=abc&code=a","abc")==null,"reject wrong callback path");
        Check(GoogleAccount.Callback("https://evil.test/oauth2/callback?state=abc&code=a","abc")==null,"reject absolute target");
        Check(GoogleAccount.Callback("/oauth2/callback?state=abc&error=access_denied","abc")["error"]=="access_denied","authorized callback rejection path");
        Check(GoogleAccount.Form(new Dictionary<string,string>{{"x","a &b=+"}})=="x=a%20%26b%3D%2B","form escaping");
        var old=Json.Serializer().Deserialize<Settings>("{\"Height\":1080,\"Auth\":\"firefox\",\"BufferSeconds\":120}");
        Check(old.CardView && !old.PlayerOnTop && old.Auth=="firefox" && old.BufferSeconds==120,"old settings retain features with safe new defaults");
        var media=new Media{VideoUrl="https://example.test/video",Title="test"};old.PlayerOnTop=true;
        Check(Player.BuildArgs(old,media,false,"test").Contains("--ontop=yes"),"video uses topmost preference");
        old.PlayerOnTop=false;Check(Player.BuildArgs(old,media,false,"test").Contains("--ontop=no"),"video allows normal stacking");
        Check(!Player.BuildArgs(old,media,true,"test").Contains("--ontop=no"),"headless audio has no mpv window flag");
        var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;
        using(var ct=new CancellationTokenSource(5000)){
            var task=Task.Run(()=>Receive(listener,"fixture-state",ct.Token));
            Check(Send(port,"/oauth2/callback?state=other&code=ignored").Contains("400 Bad Request"),"real loopback rejects unrelated callback");
            Check(Send(port,"/oauth2/callback?state=fixture-state&code=valid").Contains("Cache-Control: no-store"),"real callback response is not cached");
            Check(task.GetAwaiter().GetResult()=="valid","real loopback accepts matching code after wrong callback");
        }listener.Stop();
        listener=new TcpListener(IPAddress.Loopback,0);listener.Start();using(var ct=new CancellationTokenSource()){
            var task=Task.Run(()=>Receive(listener,"cancel-state",ct.Token));ct.Cancel();try{task.GetAwaiter().GetResult();throw new Exception("Cancellation ignored");}catch(OperationCanceledException){Check(true,"loopback cancellation closes waiting listener");}
        }listener.Stop();
        Console.WriteLine(count+" tests passed.");return 0;
    }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
