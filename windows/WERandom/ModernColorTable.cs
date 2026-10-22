using System.Drawing;
using System.Windows.Forms;

namespace WERandom;

public class ModernColorTable : ProfessionalColorTable
{
	public override Color MenuBorder => Theme.Border;

	public override Color MenuItemBorder => Color.Transparent;

	public override Color MenuItemSelected => Theme.MenuHover;

	public override Color ToolStripDropDownBackground => Theme.Window;

	public override Color ImageMarginGradientBegin => Theme.Window;

	public override Color ImageMarginGradientMiddle => Theme.Window;

	public override Color ImageMarginGradientEnd => Theme.Window;

	public override Color SeparatorDark => Theme.Border;

	public override Color SeparatorLight => Theme.Window;
}
