using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using Wpf.Ui.Controls;
using MenuItem = Wpf.Ui.Controls.MenuItem;

namespace WERandom;

/// <summary>
/// Right-click menu of the tray icon in the WPF-UI style (rounded, light / dark like the main window).
/// A WPF context menu only closes on an outside click while one of its own windows is in the foreground,
/// so it is opened from a tiny invisible window that is brought to the front first.
/// </summary>
public sealed class TrayMenu
{
	public sealed class Entry
	{
		public string Text;

		public SymbolRegular Icon;

		public bool Enabled = true;

		public bool Bold;

		public Action Click;

		public bool Separator;
	}

	[DllImport("user32.dll")]
	private static extern bool SetForegroundWindow(IntPtr hWnd);

	private Window _host;

	private ContextMenu _menu;

	public void Show(System.Collections.Generic.IEnumerable<Entry> entries)
	{
		if (_menu != null && _menu.IsOpen)
		{
			_menu.IsOpen = false;
		}
		if (_host == null)
		{
			_host = new Window
			{
				WindowStyle = WindowStyle.None,
				AllowsTransparency = true,
				Background = System.Windows.Media.Brushes.Transparent,
				ShowInTaskbar = false,
				ShowActivated = true,
				Topmost = true,
				Width = 1,
				Height = 1,
				Left = -10000,
				Top = -10000
			};
			_host.Show();
		}
		_menu = new ContextMenu
		{
			Placement = System.Windows.Controls.Primitives.PlacementMode.MousePoint,
			PlacementTarget = _host,
			MinWidth = 260
		};
		foreach (Entry e in entries)
		{
			if (e.Separator)
			{
				_menu.Items.Add(new Separator());
				continue;
			}
			MenuItem item = new MenuItem
			{
				Header = e.Text,
				IsEnabled = e.Enabled,
				FontWeight = e.Bold ? FontWeights.SemiBold : FontWeights.Normal
			};
			if (e.Icon != SymbolRegular.Empty)
			{
				item.Icon = new SymbolIcon { Symbol = e.Icon };
			}
			if (e.Click != null)
			{
				Action click = e.Click;
				item.Click += delegate { click(); };
			}
			_menu.Items.Add(item);
		}
		_menu.Closed += delegate { _host.Hide(); };
		_host.Show();
		SetForegroundWindow(new WindowInteropHelper(_host).Handle);
		_host.Activate();
		_menu.IsOpen = true;
	}
}
