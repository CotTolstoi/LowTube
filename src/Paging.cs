using System;
using System.Globalization;
using System.Text.RegularExpressions;
namespace LowTube {
    public static class Paging {
        public static int Size(int value){return Math.Max(1,Math.Min(250,value));}
        public static int BatchCount(int value){return ((Size(value)+24)/25)*25;}
        public static long First(int page,int size){return (long)Math.Max(0,page)*Size(size)+1;}
        public static string Range(int page,int size){long first=First(page,size);return first.ToString(CultureInfo.InvariantCulture)+":"+(first+Size(size)-1).ToString(CultureInfo.InvariantCulture);}
        public static string SearchTarget(string target,int page,int size){
            // A ytsearch100 target must not truncate a page configured for 250 results.
            return Regex.Replace(target,@"^ytsearch\d+:","ytsearch"+(First(page,size)+Size(size)-1).ToString(CultureInfo.InvariantCulture)+":");
        }
    }
}
