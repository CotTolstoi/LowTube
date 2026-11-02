using System;
using System.Linq;
using LowTube;
public static class BenchPlanTests {
 static int checks;static void Check(bool value,string text){if(!value)throw new Exception(text);checks++;Console.WriteLine("PASS "+text);}
 public static void Main(){
  var original=new Settings{D3D11Flip=true,Equalizer=new EqualizerSettings{Enabled=true}};string before=Json.Serializer().Serialize(original);var cases=BenchPlan.Base(original);
  Check(cases.Count>=12,"base includes direct/copy/software/automatic modes");
  Check(Json.Serializer().Serialize(original)==before,"planning never changes user settings");
  Check(cases.All(c=>!c.Options.Equalizer.Enabled && !c.Options.PlayerOnTop),"consistent EQ and stacking");
  Check(cases.All(c=>Rules.CompatibleRenderer(c.Options.Decoder,c.Options.Renderer)==c.Options.Renderer),"only compatible direct rendering pairs");
  foreach(var c in cases)Check(Player.BuildArgs(c.Options,new Media{VideoUrl="clip.mkv"},false,"test").Count>0,"valid arguments: "+c.Name);
  var advanced=BenchPlan.Advanced(new Settings{Decoder="no",Renderer="opengl"});
  Check(!advanced.Any(c=>c.Name.Contains("D3D11")),"OpenGL excludes D3D11 tuning");
  Check(advanced.Select(c=>PlayerTuning.Signature(c.Options)).Distinct().Count()==advanced.Count,"no duplicate advanced profiles");
  Check(advanced.Any(c=>c.Options.DecodeThreads==0) && advanced.Any(c=>c.Options.DecodeThreads==16),"CPU thread sweep");
  Check(advanced.Any(c=>c.Options.VideoInterpolation && c.Options.VideoSync=="display-resample"),"interpolation has display synchronization");
  Check(BenchPlan.Advanced(new Settings{Decoder="d3d11va"}).Any(c=>c.Options.D3D11ZeroCopy),"hardware D3D11 zero copy tested");
  Check(BenchPlan.Status("dxva2","no").StartsWith("FALLBACK"),"CPU fallback never misreported as hardware");
  Check(BenchPlan.Status("auto","no")=="AUTO_SOFTWARE","auto reports actual software choice");
  Check(BenchPlan.Status("no","unknown")=="UNKNOWN","missing property is not fabricated software result");
  var f=Json.Object("{\"vcodec\":\"avc1\",\"height\":720,\"width\":1280,\"fps\":30,\"acodec\":\"none\",\"url\":\"https://example.com/x\"}");
  Check(BenchPlan.VideoFormat(f),"valid video format admitted");f["has_drm"]=true;Check(!BenchPlan.VideoFormat(f),"DRM excluded");f["has_drm"]=false;
  string key=BenchPlan.FormatKey(f);f["fps"]=60;Check(key!=BenchPlan.FormatKey(f),"30 and 60fps not collapsed");f["fps"]=30;f["protocol"]="other";Check(key==BenchPlan.FormatKey(f),"transport variants grouped");
  var low=Json.Object("{\"vcodec\":\"avc1\",\"width\":640,\"height\":360,\"url\":\"https://example.com/x\",\"format_note\":\"360p\"}");
  Check(!BenchPlan.EligibleFormat(low),"360p excluded before download");
  low["width"]=854;low["height"]=480;low["format_note"]="480p";Check(BenchPlan.EligibleFormat(low),"480p included");
  low["width"]=3840;low["height"]=2160;low["format_note"]="2160p60";Check(BenchPlan.EligibleFormat(low),"4K retained");
  low["width"]=1120;low["height"]=480;low["format_note"]="360p";Check(!BenchPlan.EligibleFormat(low),"explicit YouTube tier takes precedence");
  low["width"]=1120;low["height"]=468;low["format_note"]="720p";Check(BenchPlan.EligibleFormat(low),"ultrawide 720p retained below 480 physical pixels");
  Check(BenchPlan.NormalizeCodec("vp9")==BenchPlan.NormalizeCodec("vp09.00.41.08"),"equivalent 8-bit profile 0 VP9 spelling deduplicates");
  Check(BenchPlan.NormalizeCodec("vp09.02.41.10")!=BenchPlan.NormalizeCodec("vp9"),"10-bit VP9 stays distinct");
  var rating=new BenchResult{Samples=11,Fps=60,Elapsed=10,Advance=10,AvSync=0.01,Drops=2};rating.Classify();Check(rating.Valid,"smooth playback is valid");
  rating.AvSync=1.69;rating.Classify();Check(rating.Status=="AV_DESYNC" && !rating.Valid,"large desync cannot be OK");
  rating.AvSync=0.01;rating.Drops=127;rating.Classify();Check(rating.Status=="FRAME_DROPS" && rating.DropPercent>20,"many dropped frames cannot be OK");
  rating.Drops=0;rating.Advance=8.3;rating.Classify();Check(rating.Status=="SLOW_OR_STALLED","83 percent playback speed is not smooth");
  rating.Advance=10;rating.Fps=0;rating.Classify();Check(!rating.Valid,"missing FPS cannot be verified smooth");
  var saved=new BenchCheckpoint{Url="8AApd803TUE",Seconds=10,Baseline=BenchCheckpoint.Clean(new Settings{CookiesFile="PRIVATE",Auth="saved",AuthMedia=true})};saved.Pending="case";saved.Put("done",rating);
  var restored=Json.Serializer().Deserialize<BenchCheckpoint>(Json.Serializer().Serialize(saved));Check(restored.Pending=="case" && restored.Find("done")!=null && restored.Baseline.CookiesFile=="" && !restored.Baseline.AuthMedia,"checkpoint restores results and pending case without credentials");
  string legacy=Environment.GetEnvironmentVariable("LOWTUBE_TEST_REPORT");if(!String.IsNullOrEmpty(legacy)){saved.ImportLegacy(legacy);Check(saved.CompletedFormats.Count==7,"supplied legacy report imports seven distinct completed groups");saved.ImportLegacy(legacy);Check(saved.CompletedFormats.Count==7,"legacy import is idempotent");}
  Console.WriteLine("TOTAL "+checks+" benchmark planning checks passed");
 }
}
