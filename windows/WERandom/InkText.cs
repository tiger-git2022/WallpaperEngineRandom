using System.Globalization;
using System.Windows;
using System.Windows.Documents;
using System.Windows.Media;

namespace WERandom;

/// <summary>
/// 按字形的实际笔画（墨迹）来排版的一小段文字，给数字徽标这类“文字要在小圆角块里正中”的地方用。
/// TextBlock 的高度是字体的上伸 + 下伸，数字没有下伸部分，按 TextBlock 居中时数字总是偏的，偏多少还随字体和字号变。
/// 这里把文字转成几何图形，控件的大小就是图形的边界，放在容器里居中就是笔画居中，不需要按字体微调。
/// </summary>
public sealed class InkText : FrameworkElement
{
	public static readonly DependencyProperty TextProperty = DependencyProperty.Register(nameof(Text), typeof(string), typeof(InkText),
		new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

	public static readonly DependencyProperty FontSizeProperty = TextElement.FontSizeProperty.AddOwner(typeof(InkText),
		new FrameworkPropertyMetadata(12.0, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

	public static readonly DependencyProperty FontFamilyProperty = TextElement.FontFamilyProperty.AddOwner(typeof(InkText),
		new FrameworkPropertyMetadata(SystemFonts.MessageFontFamily, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

	public static readonly DependencyProperty FontWeightProperty = TextElement.FontWeightProperty.AddOwner(typeof(InkText),
		new FrameworkPropertyMetadata(FontWeights.Normal, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

	public static readonly DependencyProperty ForegroundProperty = TextElement.ForegroundProperty.AddOwner(typeof(InkText),
		new FrameworkPropertyMetadata(Brushes.Black, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsRender));

	private Geometry _ink;

	public string Text
	{
		get => (string)GetValue(TextProperty);
		set => SetValue(TextProperty, value);
	}

	public double FontSize
	{
		get => (double)GetValue(FontSizeProperty);
		set => SetValue(FontSizeProperty, value);
	}

	public FontFamily FontFamily
	{
		get => (FontFamily)GetValue(FontFamilyProperty);
		set => SetValue(FontFamilyProperty, value);
	}

	public FontWeight FontWeight
	{
		get => (FontWeight)GetValue(FontWeightProperty);
		set => SetValue(FontWeightProperty, value);
	}

	public Brush Foreground
	{
		get => (Brush)GetValue(ForegroundProperty);
		set => SetValue(ForegroundProperty, value);
	}

	protected override Size MeasureOverride(Size available)
	{
		_ink = null;
		if (string.IsNullOrEmpty(Text))
		{
			return new Size(0, 0);
		}
		var text = new FormattedText(Text, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
			new Typeface(FontFamily, FontStyles.Normal, FontWeight, FontStretches.Normal), FontSize, Foreground,
			VisualTreeHelper.GetDpi(this).PixelsPerDip);
		Geometry g = text.BuildGeometry(new Point(0, 0));
		Rect b = g.Bounds;
		if (b.IsEmpty)
		{
			return new Size(0, 0);
		}
		// 把笔画的左上角移到 (0, 0)：控件的大小就是笔画的大小
		g.Transform = new TranslateTransform(-b.X, -b.Y);
		_ink = g;
		return new Size(b.Width, b.Height);
	}

	protected override void OnRender(DrawingContext dc)
	{
		if (_ink != null)
		{
			Rect b = _ink.Bounds;
			dc.PushTransform(new TranslateTransform((RenderSize.Width - b.Width) / 2, (RenderSize.Height - b.Height) / 2));
			dc.DrawGeometry(Foreground, null, _ink);
			dc.Pop();
		}
	}
}
