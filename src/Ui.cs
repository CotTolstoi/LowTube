using System;
using System.Drawing;
using System.Windows.Forms;

namespace LowTube {
    public static class Ui {
        public static void Attach(Form form) {
            Theme.Attach(form);
            Action update=delegate { if(!form.IsDisposed)Relocalize(form); };
            L.Changed+=update;
            form.Disposed+=delegate { L.Changed-=update; };
        }
        public static void Relocalize(Control control) {
            // Only application captions are translated. User input, titles and reports are data.
            if(!(control is TextBoxBase) && !(control is ComboBox) && !Object.Equals(control.Tag,"data"))
                control.Text=L.Relocalize(control.Text);
            if(!String.IsNullOrEmpty(control.AccessibleName))control.AccessibleName=L.Relocalize(control.AccessibleName);
            var combo=control as ComboBox;
            if(combo!=null && combo.FormattingEnabled){object chosen=combo.SelectedItem;combo.SelectedIndex=-1;combo.SelectedItem=chosen;}
            var list=control as ListView;
            if(list!=null)foreach(ColumnHeader column in list.Columns)column.Text=L.Relocalize(column.Text);
            foreach(Control child in control.Controls)Relocalize(child);
        }
        public static Button Button(string key,Action action) {
            var b=new Button { Text=L.T(key),AutoSize=true,MinimumSize=new Size(0,30),Margin=new Padding(0,3,8,3),Padding=new Padding(6,0,6,0) };
            b.Click+=delegate { action(); };return b;
        }
        public static Label Label(string key,int width) { return new Label { Text=L.T(key),AutoSize=true,MaximumSize=new Size(width,0),Margin=new Padding(0,6,0,6) }; }
        public static ComboBox Combo(string[] values,string selected,int width) {
            var c=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=width,Margin=new Padding(0,4,8,4)};
            c.Items.AddRange(values);c.SelectedItem=selected;if(c.SelectedIndex<0)c.SelectedIndex=0;return c;
        }
        public static string Time(double seconds) {
            if(Double.IsNaN(seconds) || Double.IsInfinity(seconds) || seconds<0)return "--:--";
            var t=TimeSpan.FromSeconds(Math.Min(seconds,Int32.MaxValue));
            return t.TotalHours>=1?((int)t.TotalHours)+t.ToString(@"\:mm\:ss"):t.ToString(@"m\:ss");
        }
    }
}
