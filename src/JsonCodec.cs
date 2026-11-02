using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Reflection;
using System.Text;

namespace LowTube {
    // Small bounded JSON codec: keeps desktop startup independent of System.Web.
    // The same implementation is used by the application and its regression tests.
    public sealed class JsonCodec {
        const int Limit=32*1024*1024,DepthLimit=150;
        static readonly CultureInfo Invariant=CultureInfo.InvariantCulture;
        public T Deserialize<T>(string text){return (T)ConvertValue(new Reader(text).Read(),typeof(T));}
        static object ConvertValue(object value,Type type){
            if(value==null)return type.IsValueType?Activator.CreateInstance(type):null;
            if(type==typeof(object) || type.IsInstanceOfType(value))return value;
            if(type.IsArray && type.GetArrayRank()==1){
                var items=value as object[];if(items==null)throw new ArgumentException("Expected JSON array.");
                Type element=type.GetElementType();var array=Array.CreateInstance(element,items.Length);
                for(int i=0;i<items.Length;i++)array.SetValue(ConvertValue(items[i],element),i);return array;
            }
            if(type.IsGenericType && type.GetGenericTypeDefinition()==typeof(List<>)){
                var list=(IList)Activator.CreateInstance(type);var items=value as object[];
                if(items==null)throw new ArgumentException("Invalid JSON array.");
                foreach(var item in items)list.Add(ConvertValue(item,type.GetGenericArguments()[0]));return list;
            }
            var map=value as Dictionary<string,object>;
            if(map!=null && type.IsClass){
                var instance=Activator.CreateInstance(type);
                foreach(var field in type.GetFields(BindingFlags.Public|BindingFlags.Instance)){
                    object item;if(!field.IsInitOnly && map.TryGetValue(field.Name,out item))field.SetValue(instance,ConvertValue(item,field.FieldType));
                }
                return instance;
            }
            if(type==typeof(string) || type==typeof(bool) || type.IsPrimitive || type==typeof(decimal)){
                try{return Convert.ChangeType(value,type,Invariant);}catch(Exception e){throw new ArgumentException("Invalid JSON value.",e);}
            }
            throw new ArgumentException("Unsupported JSON value.");
        }
        public string Serialize(object value){var b=new StringBuilder();Write(b,value,0);if(b.Length>Limit)throw new ArgumentException("JSON exceeds limit.");return b.ToString();}
        static void Write(StringBuilder b,object value,int depth){
            if(depth>DepthLimit || b.Length>Limit)throw new ArgumentException("JSON exceeds limit.");
            if(value==null){b.Append("null");return;}
            if(value is string || value is char){Quote(b,Convert.ToString(value,Invariant));return;}
            if(value is bool){b.Append((bool)value?"true":"false");return;}
            var type=value.GetType();
            if(type.IsPrimitive || value is decimal){string number=Convert.ToString(value,Invariant);if(number=="NaN" || number.Contains("Infinity"))throw new ArgumentException("Non-finite JSON number.");b.Append(number);return;}
            var map=value as IDictionary;
            if(map!=null){b.Append('{');bool first=true;foreach(DictionaryEntry entry in map){if(!first)b.Append(',');first=false;Quote(b,Convert.ToString(entry.Key,Invariant));b.Append(':');Write(b,entry.Value,depth+1);}b.Append('}');return;}
            var list=value as IEnumerable;
            if(list!=null){b.Append('[');bool first=true;foreach(var item in list){if(!first)b.Append(',');first=false;Write(b,item,depth+1);}b.Append(']');return;}
            b.Append('{');bool empty=true;
            foreach(var field in type.GetFields(BindingFlags.Public|BindingFlags.Instance)){if(!empty)b.Append(',');empty=false;Quote(b,field.Name);b.Append(':');Write(b,field.GetValue(value),depth+1);}
            foreach(var prop in type.GetProperties(BindingFlags.Public|BindingFlags.Instance)){if(!prop.CanRead || prop.GetIndexParameters().Length!=0)continue;if(!empty)b.Append(',');empty=false;Quote(b,prop.Name);b.Append(':');Write(b,prop.GetValue(value,null),depth+1);}
            b.Append('}');
        }
        static void Quote(StringBuilder b,string text){b.Append('"');foreach(char c in text){if(c=='"' || c=='\\'){b.Append('\\');b.Append(c);}else if(c<32 || Char.IsSurrogate(c)){b.Append("\\u");b.Append(((int)c).ToString("x4",Invariant));}else b.Append(c);}b.Append('"');}
        sealed class Reader {
            readonly string text;int at;
            public Reader(string value){if(value==null || value.Length>Limit)throw new ArgumentException("JSON exceeds limit.");text=value;}
            ArgumentException Invalid(){return new ArgumentException("Invalid JSON at "+at.ToString(Invariant)+".");}
            void White(){while(at<text.Length && (text[at]==' ' || text[at]=='\r' || text[at]=='\n' || text[at]=='\t'))at++;}
            bool Eat(char c){White();if(at<text.Length && text[at]==c){at++;return true;}return false;}
            void Need(char c){if(!Eat(c))throw Invalid();}
            public object Read(){White();if(at<text.Length && text[at]=='\uFEFF')at++;var value=Value(0);White();if(at!=text.Length)throw Invalid();return value;}
            object Value(int depth){
                White();if(depth>DepthLimit || at>=text.Length)throw Invalid();char c=text[at];
                if(c=='"')return String();
                if(c=='{'){at++;var map=new Dictionary<string,object>();if(Eat('}'))return map;do{White();if(at>=text.Length || text[at]!='"')throw Invalid();string key=String();Need(':');map[key]=Value(depth+1);}while(Eat(','));Need('}');return map;}
                if(c=='['){at++;var list=new List<object>();if(Eat(']'))return list.ToArray();do{list.Add(Value(depth+1));}while(Eat(','));Need(']');return list.ToArray();}
                if(c=='t'){Literal("true");return true;}if(c=='f'){Literal("false");return false;}if(c=='n'){Literal("null");return null;}
                int start=at;if(c=='-')at++;if(at>=text.Length)throw Invalid();
                if(text[at]=='0')at++;else{if(text[at]<'1' || text[at]>'9')throw Invalid();Digits();}
                bool real=false;if(at<text.Length && text[at]=='.'){real=true;at++;Digits();}
                if(at<text.Length && (text[at]=='e' || text[at]=='E')){real=true;at++;if(at<text.Length && (text[at]=='+' || text[at]=='-'))at++;Digits();}
                string number=text.Substring(start,at-start);long integer;double floating;
                if(!real && Int64.TryParse(number,NumberStyles.Integer,Invariant,out integer))return integer;
                if(!Double.TryParse(number,NumberStyles.Float,Invariant,out floating) || Double.IsInfinity(floating) || Double.IsNaN(floating))throw Invalid();return floating;
            }
            void Digits(){int start=at;while(at<text.Length && text[at]>='0' && text[at]<='9')at++;if(at==start)throw Invalid();}
            void Literal(string word){if(at+word.Length>text.Length || text.Substring(at,word.Length)!=word)throw Invalid();at+=word.Length;}
            string String(){
                Need('"');var b=new StringBuilder();while(at<text.Length){char c=text[at++];if(c=='"')return b.ToString();if(c<32)throw Invalid();if(c!='\\'){b.Append(c);continue;}
                    if(at>=text.Length)throw Invalid();c=text[at++];switch(c){case '"':case '\\':case '/':b.Append(c);break;case 'b':b.Append('\b');break;case 'f':b.Append('\f');break;case 'n':b.Append('\n');break;case 'r':b.Append('\r');break;case 't':b.Append('\t');break;
                    case 'u':ushort hex;if(at+4>text.Length || !UInt16.TryParse(text.Substring(at,4),NumberStyles.HexNumber,Invariant,out hex))throw Invalid();b.Append((char)hex);at+=4;break;default:throw Invalid();}
                }throw Invalid();
            }
        }
    }
}
