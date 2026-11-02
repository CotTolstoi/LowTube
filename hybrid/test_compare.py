"""Compile actual report parser and classification against the real JSON codec."""
import pathlib,sys,subprocess,tempfile,json
root=pathlib.Path(sys.argv[1]).resolve();folder=pathlib.Path(__file__).parent
s=(folder/'Compare.cs').read_text();model=s[s.index(' public sealed class Measurement'):s.index(' public static class Program')]
helpers='''namespace LowTube {
public static class Json { public static System.Collections.Generic.Dictionary<string,object> Object(string s){return new JsonCodec().Deserialize<System.Collections.Generic.Dictionary<string,object>>(s);} public static string Str(System.Collections.Generic.Dictionary<string,object>d,string k){object v;return d.TryGetValue(k,out v)?System.Convert.ToString(v,System.Globalization.CultureInfo.InvariantCulture):"";} }
public static class Rules {public static double Number(System.Collections.Generic.Dictionary<string,object>d,string k){double x;return double.TryParse(Json.Str(d,k),System.Globalization.NumberStyles.Any,System.Globalization.CultureInfo.InvariantCulture,out x)?x:0;} }
}'''
test='''using System;using System.Globalization;using System.Collections.Generic;using System.Linq;using LowTube;
'''+model+helpers+'''
class Test {static void Assert(bool b){if(!b)throw new Exception("assert failed");}
static void Main(){
var m=new Measurement();m.Read("MPV HYBRID_SAMPLE {\\"time-pos\\":0,\\"hwdec-current\\":\\"d3d11va\\"}",50);m.Read("MPV HYBRID_SAMPLE {\\"time-pos\\":59,\\"hwdec-current\\":\\"d3d11va\\",\\"paused-for-cache\\":true,\\"frame-drop-count\\":7}",200);
m.Samples=3;m.HardwareSamples=3;Assert(Math.Abs(m.Speed-59.0/150)<0.00001);Assert(m.Status(true,true,true,false)=="SLOW_OR_STALLED");Assert(m.WaitingSamples==1 && m.Drops==7);
m.Read("MPV HYBRID_SAMPLE {\\"time-pos\\":65}",210);Assert(m.Last==59);m.Read("broken HYBRID_SAMPLE {}",220);Assert(m.Samples==3);
Assert(m.Status(false,true,false,false)=="FAILED");m.Samples=3;m.HardwareSamples=2;Assert(m.Status(true,true,true,false)=="UNVERIFIED_HARDWARE");
m.Completed=true;m.DecoderStarts=20;m.HardwareFrames=9;Assert(m.Status(true,false,false,true)=="UNVERIFIED_HARDWARE");m.HardwareFrames=10;Assert(m.Status(true,false,false,true)=="SLOW_OR_STALLED");
m.FirstWall=0;m.LastWall=59;m.MaxAv=0;Assert(m.Status(true,false,false,true)=="COMPLETED");m.MaxAv=.2;Assert(m.Status(true,false,false,true)=="AV_DESYNC");
var c=new Measurement();c.Read("frame=3600",1);c.Read("out_time_us=60000000",2);c.Read("progress=end",3);Assert(c.ControlMedia==60 && c.ControlFrames==3600 && c.Completed);Assert(c.ControlSummary("null-software",true,30).Contains("throughput=2.000x"));
c.Read("STAGE_DECODER gop=0 launch_ms=5 first_frame_ms=10 free_buffer_wait_ms=20 decode_and_pipe_read_ms=100",0);c.Read("STAGE_DECODER gop=1 launch_ms=7 decode_and_pipe_read_ms=200",0);Assert(c.Stages["launch_ms"]==12 && c.Stages["decode_and_pipe_read_ms"]==300);
c.Read("DRAIN_DONE frames=1800 media_s=60 wall_s=40",0);Assert(c.ControlWall==40 && c.ControlSummary("drain-software",true,100).Contains("throughput=1.500x"));Assert(c.ControlSummary("null-hardware",false,10).Contains("FAILED_OR_INCOMPLETE"));
Console.WriteLine("PASS: stalls, tail audio exclusion, fallback, mixed hardware proof, incomplete/error classification");
}}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=pathlib.Path(tmp);(p/'Test.cs').write_text(test)
 refs=next((root/'packs/Microsoft.NETCore.App.Ref').glob('*/ref/net8.0'));csc=next((root/'sdk').glob('*/Roslyn/bincore/csc.dll'))
 (p/'test.rsp').write_text('/nologo\n/target:exe\n/out:'+str(p/'Test.dll')+'\n'+'\n'.join('/r:"'+str(x)+'"' for x in refs.glob('*.dll'))+'\n"'+str(p/'Test.cs')+'"'+'\n"'+str((folder.parent/'src/JsonCodec.cs').resolve())+'"')
 subprocess.run([str(root/'dotnet'),str(csc),'@'+str(p/'test.rsp')],check=True)
 (p/'Test.runtimeconfig.json').write_text(json.dumps({'runtimeOptions':{'tfm':'net8.0','framework':{'name':'Microsoft.NETCore.App','version':'8.0.20'}}}))
 subprocess.run([str(root/'dotnet'),str(p/'Test.dll')],check=True,timeout=30)
