using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace WERandom;

public class NavButton : Button
{
	private bool _hover;

	public bool Selected { get; set; }

	public NavButton()
	{
		base.FlatStyle = FlatStyle.Flat;
		base.FlatAppearance.BorderSize = 0;
		base.FlatAppearance.MouseOverBackColor = Color.Transparent;
		base.FlatAppearance.MouseDownBackColor = Color.Transparent;
		TextAlign = ContentAlignment.MiddleLeft;
		SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, value: true);
	}

	protected override void OnMouseEnter(EventArgs e)
	{
		_hover = true;
		Invalidate();
		base.OnMouseEnter(e);
	}

	protected override void OnMouseLeave(EventArgs e)
	{
		_hover = false;
		Invalidate();
		base.OnMouseLeave(e);
	}

	protected override void OnPaint(PaintEventArgs e)
	{
		Graphics graphics = e.Graphics;
		graphics.SmoothingMode = SmoothingMode.AntiAlias;
		graphics.Clear(Theme.Sidebar);
		float num = 1f;
		try
		{
			num = graphics.DpiX / 96f;
		}
		catch
		{
		}
		Rectangle r = new Rectangle((int)(6f * num), 2, base.Width - (int)(12f * num), base.Height - 4);
		if (Selected || _hover)
		{
			using GraphicsPath path = Theme.RoundRect(r, (int)(6f * num));
			using SolidBrush brush = new SolidBrush((!Selected) ? Theme.SidebarHover : Theme.SidebarSelected);
			graphics.FillPath(brush, path);
		}
		if (Selected)
		{
			Rectangle r2 = new Rectangle(r.X, r.Y + r.Height / 4, (int)Math.Max(3f, 3f * num), r.Height / 2);
			using GraphicsPath path2 = Theme.RoundRect(r2, (int)(2f * num));
			using SolidBrush brush2 = new SolidBrush(Theme.Accent);
			graphics.FillPath(brush2, path2);
		}
		TextRenderer.DrawText(bounds: new Rectangle(r.X + (int)(16f * num), r.Y + (int)Math.Round(4.5 * (double)num), r.Width - (int)(16f * num), r.Height), dc: graphics, text: Text, font: Font, foreColor: Theme.Text, flags: TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter);
	}
}
