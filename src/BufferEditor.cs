using System;
using System.Drawing;
using System.Windows.Forms;
namespace LowTube {
    public sealed class BufferEditor : UserControl {
        readonly NumericUpDown seconds,memory,back,start;
        public BufferEditor(Settings s){
            Width=610;Height=145;Margin=new Padding(0,5,0,5);
            var grid=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=4};
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,76));grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,24));Controls.Add(grid);
            seconds=Add(grid,0,"Forward buffer, seconds (5–3600)",5,3600,Rules.CacheSeconds(s));
            memory=Add(grid,1,"Forward memory per stream, MiB (16–1024)",16,1024,Rules.CacheMiB(s));
            back=Add(grid,2,"Back buffer per stream, MiB (0–512)",0,512,Rules.BackCacheMiB(s));
            start=Add(grid,3,"Start / refill threshold, seconds (1–30)",1,30,Rules.StartCacheSeconds(s));
        }
        static NumericUpDown Add(TableLayoutPanel grid,int row,string key,int min,int max,int value){
            grid.RowStyles.Add(new RowStyle(SizeType.Percent,25));var label=Ui.Label(key,460);label.Anchor=AnchorStyles.Left;grid.Controls.Add(label,0,row);
            var number=new NumericUpDown{Minimum=min,Maximum=max,Value=value,Width=110,Anchor=AnchorStyles.Left,AccessibleName=L.T(key)};grid.Controls.Add(number,1,row);return number;
        }
        public void Write(Settings s){s.BufferSeconds=(int)seconds.Value;s.BufferMiB=(int)memory.Value;s.BackBufferMiB=(int)back.Value;s.BufferStartSeconds=Math.Min((int)start.Value,s.BufferSeconds);}
    }
}
