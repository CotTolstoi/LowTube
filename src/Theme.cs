using System;
using System.Drawing;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace LowTube {
    public static class Theme {
        static bool dark;
        static event Action Changed;
        static readonly ConditionalWeakTable<Control,object> hooked=new ConditionalWeakTable<Control,object>();
        public static Color Window { get { return dark?Color.FromArgb(15,15,15):Color.White; } }
        public static Color Surface { get { return dark?Color.FromArgb(32,32,32):Color.White; } }
        public static Color Text { get { return dark?Color.FromArgb(241,241,241):Color.FromArgb(15,15,15); } }
        public static Color Border { get { return dark?Color.FromArgb(74,81,94):Color.FromArgb(183,191,203); } }
        static Color Selected { get { return dark?Color.FromArgb(39,79,125):SystemColors.Highlight; } }
        public static void Set(string value){bool next=value=="dark";if(next==dark)return;dark=next;if(Changed!=null)Changed();}
        public static void Attach(Form form){
            Action update=delegate{if(!form.IsDisposed){Apply(form);TitleBar(form);}};
            Changed+=update;form.Disposed+=delegate{Changed-=update;};form.HandleCreated+=delegate{TitleBar(form);};update();
        }
        [DllImport("dwmapi.dll")]static extern int DwmSetWindowAttribute(IntPtr hwnd,int attribute,ref int value,int size);
        static void TitleBar(Form form){
            if(!form.IsHandleCreated)return;
            try{int value=dark?1:0;if(DwmSetWindowAttribute(form.Handle,20,ref value,4)!=0)DwmSetWindowAttribute(form.Handle,19,ref value,4);}
            catch(DllNotFoundException){}catch(EntryPointNotFoundException){}
        }
        static void Apply(Control control){
            control.BackColor=control is TextBoxBase || control is ListControl || control is ListView?Surface:Window;control.ForeColor=Text;
            var button=control as Button;
            if(button!=null){button.UseVisualStyleBackColor=false;button.FlatStyle=FlatStyle.Flat;button.BackColor=Surface;button.FlatAppearance.BorderColor=Border;button.FlatAppearance.BorderSize=0;button.FlatAppearance.MouseOverBackColor=dark?Color.FromArgb(53,61,74):Color.FromArgb(225,234,246);}
            if(Object.Equals(control.Tag,"brand")){control.BackColor=Color.FromArgb(230,20,30);control.ForeColor=Color.White;}
            var link=control as LinkLabel;if(link!=null){link.LinkColor=dark?Color.LightSkyBlue:Color.RoyalBlue;link.VisitedLinkColor=link.LinkColor;}
            object unused;
            if(!hooked.TryGetValue(control,out unused)) {
                hooked.Add(control,new object());
                var combo=control as ComboBox;
                if(combo!=null){combo.DrawMode=DrawMode.OwnerDrawFixed;combo.DrawItem+=delegate(object sender,DrawItemEventArgs e){
                    bool selected=(e.State & DrawItemState.Selected)!=0;using(var brush=new SolidBrush(selected?Selected:Surface))e.Graphics.FillRectangle(brush,e.Bounds);
                    string label=e.Index>=0?combo.GetItemText(combo.Items[e.Index]):combo.Text;
                    TextRenderer.DrawText(e.Graphics,label,combo.Font,e.Bounds,combo.Enabled?(selected?Color.White:Text):SystemColors.GrayText,TextFormatFlags.Left|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);e.DrawFocusRectangle();
                };}
                var list=control as ListView;
                if(list!=null){list.OwnerDraw=true;list.DrawColumnHeader+=delegate(object sender,DrawListViewColumnHeaderEventArgs e){
                    using(var brush=new SolidBrush(Window))e.Graphics.FillRectangle(brush,e.Bounds);
                    using(var pen=new Pen(Border))e.Graphics.DrawLine(pen,e.Bounds.Right-1,e.Bounds.Top,e.Bounds.Right-1,e.Bounds.Bottom);
                    var bounds=e.Bounds;bounds.Inflate(-5,0);TextRenderer.DrawText(e.Graphics,e.Header.Text,list.Font,bounds,Text,TextFormatFlags.Left|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);
                };
                list.DrawItem+=delegate(object sender,DrawListViewItemEventArgs e){if(list.View!=View.Details)e.DrawDefault=true;};
                list.DrawSubItem+=delegate(object sender,DrawListViewSubItemEventArgs e){
                    bool selected=e.Item.Selected;using(var brush=new SolidBrush(selected?Selected:Surface))e.Graphics.FillRectangle(brush,e.Bounds);
                    var bounds=e.Bounds;bounds.Inflate(-5,0);TextRenderer.DrawText(e.Graphics,e.SubItem.Text,list.Font,bounds,selected?Color.White:Text,TextFormatFlags.Left|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis|TextFormatFlags.NoPrefix);
                };}
            }
            foreach(Control child in control.Controls)Apply(child);control.Invalidate();
        }
    }
}
