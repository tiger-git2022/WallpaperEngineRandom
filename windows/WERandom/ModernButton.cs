using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace WERandom;

public class ModernButton : Button
{
	private bool _hover;

	private bool _down;

	public bool Primary { get; set; }

	public ModernButton()
	{
		base.FlatStyle = FlatStyle.Flat;
		base.FlatAppearance.BorderSize = 0;
		base.FlatAppearance.MouseOverBackColor = Color.Transparent;
		base.FlatAppearance.MouseDownBackColor = Color.Transparent;
		BackColor = Color.Transparent;
		AutoSize = true;
		base.AutoSizeMode = AutoSizeMode.GrowAndShrink;
		float num = Theme.ScreenScale();
		base.Padding = new Padding((int)(14f * num), (int)(6f * num), (int)(14f * num), (int)(6f * num));
		MinimumSize = new Size((int)(88f * num), (int)(32f * num));
		SetStyle(ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, value: true);
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
		_down = false;
		Invalidate();
		base.OnMouseLeave(e);
	}

	protected override void OnMouseDown(MouseEventArgs e)
	{
		_down = true;
		Invalidate();
		base.OnMouseDown(e);
	}

	protected override void OnMouseUp(MouseEventArgs e)
	{
		_down = false;
		Invalidate();
		base.OnMouseUp(e);
	}

	protected override void OnPaint(PaintEventArgs e)
	{
		Graphics graphics = e.Graphics;
		graphics.SmoothingMode = SmoothingMode.AntiAlias;
		Color color = ((base.Parent == null) ? Theme.Window : base.Parent.BackColor);
		graphics.Clear(color);
		Color color2;
		Color foreColor;
		Color color3;
		if (Primary)
		{
			color2 = ((!base.Enabled) ? Color.FromArgb(160, 190, 220) : (_down ? Theme.AccentPressed : ((!_hover) ? Theme.Accent : Theme.AccentHover)));
			foreColor = Color.White;
			color3 = color2;
		}
		else
		{
			color2 = (_down ? Theme.SecondaryPressed : ((!_hover) ? Theme.Secondary : Theme.SecondaryHover));
			foreColor = ((!base.Enabled) ? Theme.TextMuted : Theme.Text);
			color3 = Theme.Border;
		}
		Rectangle rectangle = new Rectangle(0, 0, base.Width - 1, base.Height - 1);
		int radius = (int)Math.Round(6f * DeviceDpiScale());
		using (GraphicsPath path = Theme.RoundRect(rectangle, radius))
		{
			using (SolidBrush brush = new SolidBrush(color2))
			{
				graphics.FillPath(brush, path);
			}
			using Pen pen = new Pen(color3);
			graphics.DrawPath(pen, path);
		}
		Rectangle bounds = rectangle;
		bounds.Y += (int)Math.Round(4.5 * (double)DeviceDpiScale());
		TextRenderer.DrawText(graphics, Text, Font, bounds, foreColor, TextFormatFlags.HorizontalCenter | TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter);
	}

	private float DeviceDpiScale()
	{
		return Theme.ScreenScale();
	}
}
