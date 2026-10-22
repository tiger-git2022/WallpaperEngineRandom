using System;
using System.Windows;
using Wpf.Ui.Appearance;
using Wpf.Ui.Markup;

namespace WERandom;

/// <summary>
/// The tray app runs a Windows Forms message loop; the main window is WPF (WPF-UI, Fluent style with Mica).
/// One WPF Application object holds the WPF-UI theme and control dictionaries for all WPF windows.
/// </summary>
public static class WpfHost
{
	private static Application _app;

	public static void EnsureApp(Config cfg)
	{
		if (_app != null)
		{
			return;
		}
		_app = Application.Current ?? new Application
		{
			ShutdownMode = ShutdownMode.OnExplicitShutdown
		};
		// the theme picked in the main window ("system" / "light" / "dark"), also for the tray menu
		bool dark = cfg != null && cfg.Theme == "dark" || (cfg == null || cfg.Theme != "light") && ApplicationThemeManager.GetSystemTheme() == SystemTheme.Dark;
		ApplicationTheme theme = dark ? ApplicationTheme.Dark : ApplicationTheme.Light;
		_app.Resources.MergedDictionaries.Add(new ThemesDictionary
		{
			Theme = theme
		});
		_app.Resources.MergedDictionaries.Add(new ControlsDictionary());
	}
}
