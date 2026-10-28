using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace LowTube {
    // One painted surface instead of hundreds of child controls. Only visible thumbnails load.
    public sealed class VideoGrid : ScrollableControl {
        List<Video> items=new List<Video>();
        readonly Dictionary<string,Image> images=new Dictionary<string,Image>();
        readonly Queue<string> order=new Queue<string>();
        readonly HashSet<string> pending=new HashSet<string>();
        readonly HashSet<string> failed=new HashSet<string>();
        readonly SemaphoreSlim slots=new SemaphoreSlim(2);
        CancellationTokenSource lifetime=new CancellationTokenSource();
        int selected=-1, columns=1, cellWidth=280, cellHeight=235, generation;
        public event Action Activated;
        public event Action<Video,Point> ContextRequested;
        public Func<Video,double> WatchProgress;
        public Video Selected {get{return selected>=0 && selected<items.Count?items[selected]:null;}}
        public VideoGrid(){DoubleBuffered=true;AutoScroll=true;TabStop=true;SetStyle(ControlStyles.Selectable,true);}
        public void SetVideos(IEnumerable<Video> source,bool preserve=false){
            generation++;items=source.Where(v=>v!=null).ToList();selected=-1;failed.Clear();
            // Thumbnails never accumulate across pages or persist to disk.
            if(preserve){var keep=new HashSet<string>(items.Select(v=>v.Id));foreach(string id in images.Keys.Where(id=>!keep.Contains(id)).ToArray()){images[id].Dispose();images.Remove(id);}order.Clear();foreach(string id in images.Keys)order.Enqueue(id);}
            else{foreach(var img in images.Values)img.Dispose();images.Clear();order.Clear();}AutoScrollPosition=Point.Empty;Arrange();
        }
        public void SelectVideo(Video video){selected=items.IndexOf(video);Invalidate();}
        void Arrange(){
            int width=Math.Max(230,ClientSize.Width-SystemInformation.VerticalScrollBarWidth-12);
            columns=Math.Max(1,width/260);cellWidth=width/columns;cellHeight=(cellWidth-20)*9/16+91;
            AutoScrollMinSize=new Size(0,((items.Count+columns-1)/columns)*cellHeight+12);Invalidate();
        }
        protected override void OnResize(EventArgs e){base.OnResize(e);Arrange();}
        protected override void OnScroll(ScrollEventArgs e){base.OnScroll(e);Invalidate();}
        Rectangle Card(int i){return new Rectangle(8+(i%columns)*cellWidth,8+(i/columns)*cellHeight+AutoScrollPosition.Y,cellWidth-16,cellHeight-12);}
        protected override void OnPaint(PaintEventArgs e){
            base.OnPaint(e);e.Graphics.Clear(Theme.Window);
            if(items.Count==0){TextRenderer.DrawText(e.Graphics,L.T("Search for videos or open Recommendations"),Font,ClientRectangle,Theme.Text,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);return;}
            for(int i=0;i<items.Count;i++){
                Rectangle card=Card(i);if(!card.IntersectsWith(ClientRectangle))continue;
                var video=items[i];var picture=new Rectangle(card.X,card.Y,card.Width,card.Width*9/16);
                using(var brush=new SolidBrush(Theme.Surface))e.Graphics.FillRectangle(brush,picture);
                Image img;if(images.TryGetValue(video.Id,out img))e.Graphics.DrawImage(img,picture);
                else {TextRenderer.DrawText(e.Graphics,"▶",Font,picture,Theme.Text,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);Queue(video.Id);}
                string badgeText=video.Kind=="playlist"?L.T("Playlist"):video.LiveStatus=="is_live"?L.T("LIVE"):video.Kind=="shorts"?L.T("Shorts"):video.Duration;
                if(!String.IsNullOrWhiteSpace(badgeText) && badgeText!="—"){
                    var size=TextRenderer.MeasureText(badgeText,Font);var badge=new Rectangle(picture.Right-size.Width-9,picture.Bottom-size.Height-7,size.Width+4,size.Height+2);
                    e.Graphics.FillRectangle(Brushes.Black,badge);TextRenderer.DrawText(e.Graphics,badgeText,Font,badge,Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);
                }
                double progress=WatchProgress==null?0:WatchProgress(video);
                if(progress>0){var bar=new Rectangle(picture.X,picture.Bottom-4,picture.Width,4);e.Graphics.FillRectangle(Brushes.DimGray,bar);bar.Width=(int)Math.Round(bar.Width*Math.Min(1,progress));e.Graphics.FillRectangle(Brushes.Red,bar);}
                var title=new Rectangle(card.X,picture.Bottom+7,card.Width,37);
                TextRenderer.DrawText(e.Graphics,video.Title,Font,title,Theme.Text,TextFormatFlags.WordBreak|TextFormatFlags.EndEllipsis|TextFormatFlags.NoPrefix);
                var meta=new Rectangle(card.X,picture.Bottom+46,card.Width,30);
                TextRenderer.DrawText(e.Graphics,video.Channel+(String.IsNullOrEmpty(video.Date)?"":" · "+video.Date),Font,meta,Color.Gray,TextFormatFlags.WordBreak|TextFormatFlags.EndEllipsis|TextFormatFlags.NoPrefix);
                if(i==selected)using(var pen=new Pen(Color.FromArgb(220,30,40),2)){var outline=card;outline.Inflate(3,3);e.Graphics.DrawRectangle(pen,outline);}
            }
        }
        async void Queue(string id){
            if(!Regex.IsMatch(id??"",@"^[A-Za-z0-9_-]{11}$") || pending.Contains(id) || failed.Contains(id) || lifetime.IsCancellationRequested)return;
            pending.Add(id);int own=generation;var ct=lifetime.Token;Image image=null;
            try {
                await slots.WaitAsync(ct);
                try {if(own!=generation)return;image=await Task.Run(()=>Fetch(id,ct),ct);}finally{slots.Release();}
                if(IsDisposed || ct.IsCancellationRequested || own!=generation){if(image!=null)image.Dispose();return;}
                if(image!=null){while(images.Count>=64){string old=order.Dequeue();images[old].Dispose();images.Remove(old);}images[id]=image;order.Enqueue(id);image=null;}else failed.Add(id);
            }catch(Exception){if(!IsDisposed && own==generation)failed.Add(id);if(image!=null)image.Dispose();}
            finally{pending.Remove(id);if(!IsDisposed)Invalidate();}
        }
        static Image Fetch(string id,CancellationToken ct){
            var req=(HttpWebRequest)WebRequest.Create("https://i.ytimg.com/vi/"+id+"/mqdefault.jpg");req.Timeout=8000;req.ReadWriteTimeout=8000;req.AllowAutoRedirect=false;
            using(ct.Register(req.Abort))using(var response=(HttpWebResponse)req.GetResponse())using(var input=response.GetResponseStream())using(var data=new MemoryStream()){
                byte[] b=new byte[8192];int n;while((n=input.Read(b,0,b.Length))>0){ct.ThrowIfCancellationRequested();if(data.Length+n>512*1024)return null;data.Write(b,0,n);}
                data.Position=0;using(var original=Image.FromStream(data)){if(original.Width>1920 || original.Height>1080)return null;return new Bitmap(original,new Size(320,180));}
            }
        }
        protected override void OnMouseDown(MouseEventArgs e){
            Focus();selected=-1;for(int i=0;i<items.Count;i++)if(Card(i).Contains(e.Location)){selected=i;break;}Invalidate();base.OnMouseDown(e);
        }
        protected override void OnMouseUp(MouseEventArgs e){base.OnMouseUp(e);if(e.Button==MouseButtons.Right && Selected!=null && ContextRequested!=null)ContextRequested(Selected,e.Location);}

        protected override void OnMouseDoubleClick(MouseEventArgs e){base.OnMouseDoubleClick(e);if(e.Button==MouseButtons.Left && Selected!=null && Activated!=null)Activated();}
        protected override bool IsInputKey(Keys key){return key==Keys.Left || key==Keys.Right || key==Keys.Up || key==Keys.Down || key==Keys.Enter || base.IsInputKey(key);}
        protected override void OnKeyDown(KeyEventArgs e){
            base.OnKeyDown(e);if(items.Count==0)return;
            if(e.KeyCode==Keys.Enter){if(Selected!=null && Activated!=null)Activated();e.Handled=true;return;}
            int change=e.KeyCode==Keys.Left?-1:e.KeyCode==Keys.Right?1:e.KeyCode==Keys.Up?-columns:e.KeyCode==Keys.Down?columns:0;
            if(change==0)return;selected=selected<0?0:Math.Max(0,Math.Min(items.Count-1,selected+change));
            var card=Card(selected);int scroll=-AutoScrollPosition.Y;if(card.Top<0)scroll+=card.Top-8;else if(card.Bottom>ClientSize.Height)scroll+=card.Bottom-ClientSize.Height+8;
            AutoScrollPosition=new Point(0,Math.Max(0,scroll));Invalidate();e.Handled=true;
        }
        protected override void Dispose(bool disposing){if(disposing){lifetime.Cancel();foreach(var img in images.Values)img.Dispose();images.Clear();}base.Dispose(disposing);}
    }
}
