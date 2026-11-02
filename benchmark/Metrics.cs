using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Management;
using System.Runtime.InteropServices;
namespace LowTube {
 public sealed class BenchMetrics:IDisposable {
  [StructLayout(LayoutKind.Sequential)]struct Cpu {public long Idle,Kernel,User,Dpc,Interrupt;public uint Count;}
  [DllImport("ntdll.dll")]static extern int NtQuerySystemInformation(int info,IntPtr buffer,int size,out int returned);
  [StructLayout(LayoutKind.Sequential)]struct Memory {public uint Length,Load;public ulong Total,Available,PageTotal,PageAvailable,VirtualTotal,VirtualAvailable,Extended;}
  [DllImport("kernel32.dll")]static extern bool GlobalMemoryStatusEx(ref Memory m);
  [StructLayout(LayoutKind.Sequential)]struct Io {public ulong ReadOps,WriteOps,OtherOps,ReadBytes,WriteBytes,OtherBytes;}
  [DllImport("kernel32.dll")]static extern bool GetProcessIoCounters(IntPtr handle,out Io counters);
  Cpu[] previous;double wall,cpu;Io io;readonly Stopwatch clock=Stopwatch.StartNew();
  readonly List<PerformanceCounter> gpu=new List<PerformanceCounter>(),disk=new List<PerformanceCounter>(),network=new List<PerformanceCounter>();
  public string Availability="GPU/disk/network counters unavailable unless listed below.";
  public static string Hardware(){
   var lines=new List<string>{"OS: "+Environment.OSVersion,"64-bit OS: "+Environment.Is64BitOperatingSystem,"Logical processors: "+Environment.ProcessorCount};
   foreach(string query in new[]{"SELECT Name,NumberOfCores,NumberOfLogicalProcessors,MaxClockSpeed FROM Win32_Processor","SELECT Name,DriverVersion,AdapterRAM FROM Win32_VideoController","SELECT Caption,Version,BuildNumber FROM Win32_OperatingSystem","SELECT TotalPhysicalMemory FROM Win32_ComputerSystem"}){
    try{using(var search=new ManagementObjectSearcher(query){Options=new EnumerationOptions{Timeout=TimeSpan.FromSeconds(5)}})using(var items=search.Get()){foreach(ManagementObject item in items){using(item)foreach(PropertyData prop in item.Properties)lines.Add(prop.Name+": "+Convert.ToString(prop.Value));}}}catch(Exception e){lines.Add("Hardware query unavailable: "+e.GetType().Name);}
   }return String.Join(Environment.NewLine,lines);
  }
  public BenchMetrics(Process p){
   previous=CpuSnapshot();p.Refresh();cpu=p.TotalProcessorTime.TotalSeconds;wall=clock.Elapsed.TotalSeconds;GetProcessIoCounters(p.Handle,out io);
   AddCounters("GPU Engine","Utilization Percentage",gpu,s=>s.StartsWith("pid_"+p.Id+"_",StringComparison.OrdinalIgnoreCase));
   AddCounters("PhysicalDisk","Disk Bytes/sec",disk,s=>s=="_Total");
   AddCounters("Network Interface","Bytes Total/sec",network,s=>true);
   Availability="GPU engines="+gpu.Count+"; system disk counters="+disk.Count+"; system network counters="+network.Count+". Missing counters=N/A, not zero. GPU values are per engine, not additive. Temperatures/power unavailable. I/O bytes include non-disk I/O.";
  }
  static Cpu[] CpuSnapshot(){int count=Environment.ProcessorCount,size=Marshal.SizeOf(typeof(Cpu));IntPtr buffer=Marshal.AllocHGlobal(size*count);
   try{int returned;if(NtQuerySystemInformation(8,buffer,size*count,out returned)!=0)return null;var result=new Cpu[returned/size];for(int i=0;i<result.Length;i++)result[i]=(Cpu)Marshal.PtrToStructure(IntPtr.Add(buffer,i*size),typeof(Cpu));return result;}finally{Marshal.FreeHGlobal(buffer);}}
  static void AddCounters(string category,string name,List<PerformanceCounter> list,Func<string,bool> filter){try{foreach(string instance in new PerformanceCounterCategory(category).GetInstanceNames().Where(filter)){var c=new PerformanceCounter(category,name,instance,true);try{c.NextValue();list.Add(c);}catch{c.Dispose();}}}catch(Exception){}}
  public Dictionary<string,object> Read(Process p){
   var r=new Dictionary<string,object>();p.Refresh();double now=clock.Elapsed.TotalSeconds,elapsed=now-wall,total=p.TotalProcessorTime.TotalSeconds;
   r["mpv_cpu_percent_machine"]=elapsed>0?(total-cpu)/elapsed/Environment.ProcessorCount*100:0;r["mpv_working_set_MiB"]=p.WorkingSet64/1048576.0;r["mpv_private_MiB"]=p.PrivateMemorySize64/1048576.0;
   Cpu[] next=CpuSnapshot();if(next!=null && previous!=null && next.Length==previous.Length){var percentages=new List<double>();for(int i=0;i<next.Length;i++){long dt=next[i].Kernel+next[i].User-previous[i].Kernel-previous[i].User;long idle=next[i].Idle-previous[i].Idle;if(dt>0)percentages.Add(Math.Max(0,Math.Min(100,100.0*(dt-idle)/dt)));}if(percentages.Count>0){r["system_cpu_percent"]=percentages.Average();r["cpu_per_logical_processor_percent"]=percentages.ToArray();}}previous=next;
   var mem=new Memory{Length=(uint)Marshal.SizeOf(typeof(Memory))};if(GlobalMemoryStatusEx(ref mem)){r["system_memory_used_MiB"]=(mem.Total-mem.Available)/1048576.0;r["system_memory_available_MiB"]=mem.Available/1048576.0;}
   Io nextIo;if(GetProcessIoCounters(p.Handle,out nextIo) && elapsed>0){r["mpv_io_read_bytes_per_sec"]=(nextIo.ReadBytes-io.ReadBytes)/elapsed;r["mpv_io_write_bytes_per_sec"]=(nextIo.WriteBytes-io.WriteBytes)/elapsed;io=nextIo;}
   Counters(r,gpu,"gpu:");Counters(r,disk,"system_disk_bytes_per_sec:");Counters(r,network,"system_network_bytes_per_sec:");wall=now;cpu=total;return r;
  }
  static void Counters(Dictionary<string,object> r,List<PerformanceCounter> list,string prefix){foreach(var c in list)try{r[prefix+c.InstanceName]=c.NextValue();}catch(Exception){}}
  public void Dispose(){foreach(var c in gpu.Concat(disk).Concat(network))c.Dispose();}
 }
}
