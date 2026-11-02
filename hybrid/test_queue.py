"""Compile the actual FrameQueue and Take implementation into a .NET 8 harness.
Usage: python3 test_queue.py DOTNET_SDK_ROOT
"""
import pathlib,sys,subprocess,tempfile,json
root=pathlib.Path(sys.argv[1]).resolve()
source=pathlib.Path(__file__).with_name('Hybrid.cs').read_text()
queue=source[source.index(' public sealed class FrameQueue'):source.index(' public static class Program')]
take=source[source.index('  static async Task<byte[]> Take'):source.index('  static async Task Decode')]
harness='''using System;using System.Collections.Concurrent;using System.Threading;using System.Threading.Tasks;using System.IO;
'''+queue+'''class Test { static CancellationTokenSource Stop=new CancellationTokenSource();
'''+take+'''
static async Task Main(){
 if(ResourceValue.Read<double>(()=>{throw new InvalidOperationException("exited process");},2.5)!=2.5)throw new Exception("exited stats must retain sample");
 if(ResourceValue.Read<long>(()=>{throw new System.ComponentModel.Win32Exception();},42L)!=42L)throw new Exception("unavailable stats must retain sample");
 if(ResourceValue.Read<long>(()=>20L,42L)!=42L || ResourceValue.Read<long>(()=>50L,42L)!=50L)throw new Exception("peak stats");
 using(var q=new FrameQueue(4,16)){
  for(int round=0;round<3;round++){
   var producer=Task.Run(()=>{try{for(int i=0;i<10000;i++){var b=q.Free.Take();b[0]=(byte)(i%251);q.Ready.Add(b);}}finally{q.Ready.CompleteAdding();}});
   for(int i=0;i<10000;i++){var b=await Take(q,producer);if(b[0]!=(byte)(i%251))throw new Exception("frame order");q.Free.Add(b);}
   await producer;if(q.Free.Count!=4 || q.Ready.Count!=0)throw new Exception("pool leak");
   q.Ready.Dispose();q.Ready=new BlockingCollection<byte[]>();
  }
  var failed=Task.FromException(new IOException("start failure"));
  try{await Take(q,failed);throw new Exception("lost failure");}catch(IOException){}
  var blocked=Take(q,new TaskCompletionSource<bool>().Task);Stop.Cancel();
  try{await blocked;throw new Exception("lost cancellation");}catch(OperationCanceledException){}
 }
 Console.WriteLine("PASS: 30000 ordered frames, fixed pool reused, startup failure and cancellation propagated; exited/unavailable process counters harmless");
}}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=pathlib.Path(tmp);(p/'Test.cs').write_text(harness)
 refs=next((root/'packs/Microsoft.NETCore.App.Ref').glob('*/ref/net8.0'))
 csc=next((root/'sdk').glob('*/Roslyn/bincore/csc.dll'))
 (p/'test.rsp').write_text('/nologo\n/target:exe\n/out:'+str(p/'Test.dll')+'\n'+'\n'.join('/r:"'+str(x)+'"' for x in refs.glob('*.dll'))+'\n"'+str(p/'Test.cs')+'"')
 subprocess.run([str(root/'dotnet'),str(csc),'@'+str(p/'test.rsp')],check=True)
 (p/'Test.runtimeconfig.json').write_text(json.dumps({'runtimeOptions':{'tfm':'net8.0','framework':{'name':'Microsoft.NETCore.App','version':'8.0.20'}}}))
 subprocess.run([str(root/'dotnet'),str(p/'Test.dll')],check=True,timeout=30)
