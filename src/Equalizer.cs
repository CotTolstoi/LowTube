using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
namespace LowTube {
    public sealed class EqualizerSettings {
        public bool Enabled=false;
        public string Preset="flat";
        public int Preamp=0,CustomPreamp=0;
        public int[] Bands=new int[10],CustomBands=new int[10];
    }
    public static class AudioEqualizer {
        public const string Label="lowtube_equalizer";
        public static readonly int[] Frequencies={31,62,125,250,500,1000,2000,4000,8000,16000};
        public static readonly string[] Ids={"flat","bass","voice","treble","rock","classical","custom"};
        public static readonly string[] Names={"Flat","Bass boost","Voice","Treble boost","Rock","Classical","Custom"};
        static readonly int[][] curves={new int[10],new[]{6,5,4,2,0,0,0,0,0,0},new[]{-4,-3,-2,0,2,4,4,2,0,-2},new[]{0,0,0,0,0,1,2,3,4,4},new[]{4,3,2,0,-2,-1,1,3,4,3},new[]{3,2,1,0,-1,-1,0,1,2,3}};
        public static int Gain(int n){return Math.Max(-12,Math.Min(12,n));}
        public static int[] Bands(int[] source){return Enumerable.Range(0,10).Select(i=>source!=null && i<source.Length?Gain(source[i]):0).ToArray();}
        public static EqualizerSettings Copy(EqualizerSettings source){source=source??new EqualizerSettings();return new EqualizerSettings{Enabled=source.Enabled,Preset=Ids.Contains(source.Preset)?source.Preset:"custom",Preamp=Gain(source.Preamp),CustomPreamp=Gain(source.CustomPreamp),Bands=Bands(source.Bands),CustomBands=Bands(source.CustomBands)};}
        public static void Preset(EqualizerSettings value,string id){int index=Array.IndexOf(Ids,id);if(index<0)index=0;value.Preset=Ids[index];value.Bands=index==6?Bands(value.CustomBands):(int[])curves[index].Clone();value.Preamp=index==6?Gain(value.CustomPreamp):0;}
        public static int EffectivePreamp(EqualizerSettings source){var s=Copy(source);return s.Preamp-Math.Max(0,s.Bands.Max());}
        public static string Graph(EqualizerSettings source){
            var s=Copy(source);if(!s.Enabled)return "";
            var filters=new List<string>();int preamp=EffectivePreamp(s);
            if(preamp!=0)filters.Add("volume="+preamp.ToString(CultureInfo.InvariantCulture)+"dB");
            for(int i=0;i<10;i++)if(s.Bands[i]!=0)filters.Add("equalizer=f="+Frequencies[i].ToString(CultureInfo.InvariantCulture)+":t=o:w=1:g="+s.Bands[i].ToString(CultureInfo.InvariantCulture));
            if(filters.Count==0)return "";
            // A fixed rate keeps the 16 kHz band valid for low-rate audio as well.
            return "aresample=48000,"+String.Join(",",filters);
        }
        public static string Filter(EqualizerSettings settings){string graph=Graph(settings);return graph.Length==0?"":"@"+Label+":lavfi=["+graph+"]";}
        public static object[] MergeFilters(object current,EqualizerSettings settings){
            var list=new List<object>();var entries=current as IEnumerable;
            if(entries!=null && !(current is string))foreach(object entry in entries){var d=entry as Dictionary<string,object>;if(d==null || Json.Str(d,"label")!=Label)list.Add(entry);}
            string graph=Graph(settings);
            if(graph.Length>0)list.Add(new Dictionary<string,object>{{"name","lavfi"},{"label",Label},{"enabled",true},{"params",new Dictionary<string,object>{{"graph",graph}}}});
            return list.ToArray();
        }
    }
}
