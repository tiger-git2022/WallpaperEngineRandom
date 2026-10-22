using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace WERandom;

public class ModernMenuRenderer : ToolStripProfessionalRenderer
{
	public ModernMenuRenderer()
		: base(new ModernColorTable())
	{
		base.RoundedEdges = false;
	}

	protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
	{
		Graphics graphics = e.Graphics;
		ToolStripItem item = e.Item;
		int num = (int)(4f * Theme.ScreenScale());
		Rectangle r = new Rectangle(num, 1, item.Width - 2 * num, item.Height - 2);
		using (SolidBrush brush = new SolidBrush(Theme.Window))
		{
			graphics.FillRectangle(brush, new Rectangle(Point.Empty, item.Size));
		}
		if (!item.Selected || !item.Enabled)
		{
			return;
		}
		graphics.SmoothingMode = SmoothingMode.AntiAlias;
		using GraphicsPath path = Theme.RoundRect(r, (int)(5f * Theme.ScreenScale()));
		using SolidBrush brush2 = new SolidBrush(Theme.MenuHover);
		graphics.FillPath(brush2, path);
	}

	protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
	{
		e.TextColor = ((!e.Item.Enabled) ? Theme.TextMuted : Theme.Text);
		e.TextFormat |= TextFormatFlags.VerticalCenter;
		Rectangle textRectangle = e.TextRectangle;
		textRectangle.Y += (int)Math.Round(4.5 * (double)Theme.ScreenScale());
		e.TextRectangle = textRectangle;
		base.OnRenderItemText(e);
	}

	protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
	{
		Graphics graphics = e.Graphics;
		using (SolidBrush brush = new SolidBrush(Theme.Window))
		{
			graphics.FillRectangle(brush, new Rectangle(Point.Empty, e.Item.Size));
		}
		int num = e.Item.Height / 2;
		int num2 = (int)(10f * Theme.ScreenScale());
		using Pen pen = new Pen(Theme.Border);
		graphics.DrawLine(pen, num2, num, e.Item.Width - num2, num);
	}

	protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e)
	{
		using Pen pen = new Pen(Theme.Border);
		e.Graphics.DrawRectangle(pen, new Rectangle(0, 0, e.ToolStrip.Width - 1, e.ToolStrip.Height - 1));
	}

	protected override void OnRenderImageMargin(ToolStripRenderEventArgs e)
	{
		using SolidBrush brush = new SolidBrush(Theme.Window);
		e.Graphics.FillRectangle(brush, e.AffectedBounds);
	}
}
