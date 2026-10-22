using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;

namespace WERandom;

public static class Theme
{
	public static readonly Color Accent = Color.FromArgb(0, 103, 192);

	public static readonly Color AccentHover = Color.FromArgb(25, 118, 210);

	public static readonly Color AccentPressed = Color.FromArgb(0, 84, 160);

	public static readonly Color Window = Color.White;

	public static readonly Color Sidebar = Color.FromArgb(243, 243, 243);

	public static readonly Color SidebarHover = Color.FromArgb(234, 234, 234);

	public static readonly Color SidebarSelected = Color.FromArgb(225, 225, 225);

	public static readonly Color Text = Color.FromArgb(27, 27, 27);

	public static readonly Color TextMuted = Color.FromArgb(96, 96, 96);

	public static readonly Color Border = Color.FromArgb(229, 229, 229);

	public static readonly Color Secondary = Color.FromArgb(245, 245, 245);

	public static readonly Color SecondaryHover = Color.FromArgb(235, 235, 235);

	public static readonly Color SecondaryPressed = Color.FromArgb(225, 225, 225);

	public static readonly Color Selection = Color.FromArgb(204, 228, 247);

	public static readonly Color MenuHover = Color.FromArgb(240, 240, 240);

	private static float _scale = -1f;

	public static Font UiFont(float size, FontStyle style = FontStyle.Regular)
	{
		string[] array = new string[3] { "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI" };
		foreach (string text in array)
		{
			try
			{
				Font font = new Font(text, size, style);
				if (font.Name == text)
				{
					return font;
				}
			}
			catch
			{
			}
		}
		return new Font(FontFamily.GenericSansSerif, size, style);
	}

	public static float ScreenScale()
	{
		if (_scale > 0f)
		{
			return _scale;
		}
		float num = 1f;
		try
		{
			using Graphics graphics = Graphics.FromHwnd(IntPtr.Zero);
			num = graphics.DpiX / 96f;
		}
		catch
		{
		}
		try
		{
			string environmentVariable = Environment.GetEnvironmentVariable("WERANDOM_SCALE");
			if (!string.IsNullOrEmpty(environmentVariable) && float.TryParse(environmentVariable, NumberStyles.Float, CultureInfo.InvariantCulture, out var result) && result > 0.3f)
			{
				num = result;
			}
		}
		catch
		{
		}
		_scale = num;
		return num;
	}

	public static GraphicsPath RoundRect(Rectangle r, int radius)
	{
		GraphicsPath graphicsPath = new GraphicsPath();
		int num = radius * 2;
		if (num <= 0 || r.Width < num || r.Height < num)
		{
			graphicsPath.AddRectangle(r);
			return graphicsPath;
		}
		graphicsPath.AddArc(r.X, r.Y, num, num, 180f, 90f);
		graphicsPath.AddArc(r.Right - num, r.Y, num, num, 270f, 90f);
		graphicsPath.AddArc(r.Right - num, r.Bottom - num, num, num, 0f, 90f);
		graphicsPath.AddArc(r.X, r.Bottom - num, num, num, 90f, 90f);
		graphicsPath.CloseFigure();
		return graphicsPath;
	}
}
