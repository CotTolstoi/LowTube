using System;
using System.Globalization;
namespace LowTube {
 public sealed class BenchResult {
  public string Status="FAILED",Actual="",Name;public int Samples;public double Fps,DropPercent;public double Cpu,PeakCpu,Ram,Drops,DecoderDrops,AvSync,Advance,Elapsed,ReadySeconds;
  public void Classify(){
   DropPercent=Fps>0 && Elapsed>0?100*(Drops+DecoderDrops)/(Fps*Elapsed):0;
   if(Samples<3 || Elapsed<=0){Status="INSUFFICIENT_DATA";return;}
   if(Advance<Elapsed*0.95 || Advance>Elapsed*1.1){Status="SLOW_OR_STALLED";return;}
   if(AvSync>0.1){Status="AV_DESYNC";return;}
   if(Fps<=0){Status="UNVERIFIED_FPS";return;}
   if(DropPercent>1){Status="FRAME_DROPS";return;}Status="OK";
  }
  public bool Valid {get{return Status=="OK" && Samples>=3 && Advance>1;}}
  public double Score {get{return (Valid?0:1000000)+(Status=="SLOW_OR_STALLED"?1000000:0)+DropPercent*1000+(AvSync>0.1?100000:0)+AvSync*100+Cpu;}}
  public override string ToString(){return String.Format(CultureInfo.InvariantCulture,"{0}; status={1}; actual_decoder={2}; samples={3}; mpv_CPU_mean={4:F2}%; CPU_peak={5:F2}%; RAM_peak={6:F1} MiB; display_drops={7}; decoder_drops={8}; max_abs_AVsync={9:F4}s; media_advance={10:F2}s; measured_wall={11:F2}s; first_ready_observed={12:F2}s; dropped_percent={13:F2}%",Name,Status,Actual,Samples,Cpu,PeakCpu,Ram,Drops,DecoderDrops,AvSync,Advance,Elapsed,ReadySeconds,DropPercent);}
 }
}
