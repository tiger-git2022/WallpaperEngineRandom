using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace WERandom;

public class TrayApp : ApplicationContext
{
	public const string AppName = "WE 加权轮播";

	private const string RunKeyName = "WEWeightedRandom";

	private readonly Engine _engine;

	private readonly NotifyIcon _tray;

	private readonly System.Windows.Forms.Timer _timer;

	private readonly ToolStripMenuItem _miPause;

	private readonly ToolStripMenuItem _miStatus;

	private readonly ToolStripMenuItem _miSwitch;

	private readonly ToolStripMenuItem _miCurrent;

	private string _lastReason = null;

	private MainWindow _settings;

	private Control _sync;

	private readonly PortraitService _portrait;

	private readonly ToolStripMenuItem _miPortrait;

	private readonly ToolStripMenuItem _miFps30;

	private double _accumulatedSec = 0.0;

	private DateTime _lastTick;

	private bool _locked = false;

	private bool _suspended = false;

	private bool _switching = false;

	private bool _weWasRunning;

	private DateTime? _weStartedAt = null;

	private bool _fsPrev = false;

	private string _statusText = "";

	public string StatusText => _statusText;

	public PortraitService Portrait => _portrait;

	public event Action Switched;

	public TrayApp(Engine engine)
	{
		TrayApp trayApp = this;
		_engine = engine;
		_lastTick = DateTime.Now;
		_weWasRunning = new WindowsPlatform().IsWallpaperEngineRunning();
		ContextMenuStrip menu = new ContextMenuStrip
		{
			Renderer = new ModernMenuRenderer(),
			ShowImageMargin = false,
			ShowCheckMargin = true,
			Font = Theme.UiFont(10f),
			Padding = new Padding(4, 6, 4, 6),
			BackColor = Theme.Window
		};
		menu.Opening += delegate
		{
			Dwm.RoundCorners(menu.Handle, small: true);
		};
		_miStatus = new ToolStripMenuItem("状态")
		{
			Enabled = false
		};
		_miCurrent = new ToolStripMenuItem("")
		{
			Enabled = false,
			Visible = false
		};
		_miSwitch = new ToolStripMenuItem("立即切换下一张(&N)", null, delegate
		{
			trayApp.DoSwitch("手动");
		});
		_miPause = new ToolStripMenuItem("暂停自动切换(&P)", null, delegate
		{
			trayApp.TogglePause();
		})
		{
			CheckOnClick = false
		};
		ToolStripMenuItem value = new ToolStripMenuItem("打开主界面(&O)", null, delegate
		{
			trayApp.ShowSettings(-1);
		})
		{
			Font = Theme.UiFont(10f, FontStyle.Bold)
		};
		menu.Items.Add(_miStatus);
		menu.Items.Add(_miCurrent);
		menu.Items.Add(new ToolStripSeparator());
		menu.Items.Add(value);
		menu.Items.Add(_miSwitch);
		menu.Items.Add(_miPause);
		menu.Items.Add(new ToolStripMenuItem("设置(&S)...", null, delegate
		{
			trayApp.ShowSettings(0);
		}));
		menu.Items.Add(new ToolStripSeparator());
		_miPortrait = new ToolStripMenuItem("人物对齐数据")
		{
			Enabled = false
		};
		menu.Items.Add(_miPortrait);
		menu.Items.Add(new ToolStripMenuItem("立即分析新壁纸(&A)", null, delegate
		{
			trayApp._portrait.AnalyseNow();
		}));
		_miFps30 = new ToolStripMenuItem("30 帧转码：平板同步时自动进行")
		{
			Enabled = false
		};
		menu.Items.Add(_miFps30);
		menu.Items.Add(new ToolStripSeparator());
		menu.Items.Add(new ToolStripMenuItem("查看日志(&L)", null, delegate
		{
			OpenFile(trayApp._engine.LogPath);
		}));
		menu.Items.Add(new ToolStripMenuItem("打开程序文件夹(&F)", null, delegate
		{
			OpenFile(trayApp._engine.Dir);
		}));
		menu.Items.Add(new ToolStripSeparator());
		menu.Items.Add(new ToolStripMenuItem("退出(&X)", null, delegate
		{
			trayApp.ExitApp();
		}));
		float num = Theme.ScreenScale();
		foreach (ToolStripItem item in menu.Items)
		{
			if (item is ToolStripMenuItem)
			{
				item.Padding = new Padding((int)(10f * num), (int)(5f * num), (int)(10f * num), (int)(5f * num));
			}
		}
		_tray = new NotifyIcon
		{
			Icon = LoadIcon(),
			Text = "WE 加权轮播",
			Visible = true
		};
		_tray.MouseUp += delegate(object s, MouseEventArgs e)
		{
			if (e.Button == MouseButtons.Right)
			{
				trayApp.ShowTrayMenu();
			}
		};
		_tray.DoubleClick += delegate
		{
			trayApp.ShowSettings();
		};
		_tray.MouseClick += delegate(object s, MouseEventArgs e)
		{
			if (e.Button == MouseButtons.Left)
			{
				trayApp.ShowSettings();
			}
		};
		SystemEvents.SessionSwitch += OnSessionSwitch;
		SystemEvents.PowerModeChanged += OnPowerModeChanged;
		_timer = new System.Windows.Forms.Timer
		{
			Interval = 15000
		};
		_timer.Tick += delegate
		{
			trayApp.Tick();
		};
		_timer.Start();
		_sync = new Control();
		_sync.CreateControl();
		_portrait = new PortraitService(_engine);
		_portrait.StateChanged += delegate
		{
			if (trayApp._sync.IsHandleCreated)
			{
				trayApp._sync.BeginInvoke((Action)delegate
				{
					trayApp._miPortrait.Text = trayApp._portrait.State;
					string fps = trayApp._portrait.Fps30State;
					if (fps.Length > 0)
					{
						trayApp._miFps30.Text = fps.Length > 60 ? fps.Substring(0, 60) + "…" : fps;
					}
				});
			}
		};
		_portrait.Start();
		Thread thread = new Thread((ThreadStart)delegate
		{
			try
			{
				Stopwatch stopwatch = Stopwatch.StartNew();
				Paths paths = trayApp._engine.ResolvePaths();
				List<Wallpaper> list = trayApp._engine.Scan(paths);
				trayApp._engine.Log.Log("启动预扫描：" + list.Count + " 张，" + stopwatch.ElapsedMilliseconds + " ms（读文件 " + trayApp._engine.LastCacheMiss + " 个）");
			}
			catch (Exception ex)
			{
				trayApp._engine.Log.Log("启动预扫描失败：" + ex.Message);
			}
		})
		{
			IsBackground = true,
			Priority = ThreadPriority.BelowNormal
		};
		thread.Start();
		_engine.Log.Log("程序启动（间隔 " + _engine.Cfg.IntervalMinutes + " 分钟，" + ((!_engine.Cfg.Paused) ? "运行中" : "已暂停") + "）");
		UpdateMenu();
		if (_engine.Cfg.Paused)
		{
			_tray.ShowBalloonTip(3000, "WE 加权轮播", "自动切换处于暂停状态，右键托盘图标可以继续。", ToolTipIcon.Info);
		}
	}

	private static Icon LoadIcon()
	{
		try
		{
			// the tray wants the small size (16 px at 100%, 24 px at 150%); the embedded .ico has them all
			using System.IO.Stream stream = typeof(TrayApp).Assembly.GetManifestResourceStream("WERandom.app.ico");
			if (stream != null)
			{
				return new Icon(stream, SystemInformation.SmallIconSize);
			}
			return Icon.ExtractAssociatedIcon(Application.ExecutablePath);
		}
		catch
		{
			return SystemIcons.Application;
		}
	}

	private void Tick()
	{
		DateTime now = DateTime.Now;
		double num = (now - _lastTick).TotalSeconds;
		_lastTick = now;
		if (num < 0.0 || num > 60.0)
		{
			num = 15.0;
		}
		Config cfg = _engine.Cfg;
		bool flag = new WindowsPlatform().IsWallpaperEngineRunning();
		bool flag2 = cfg.IdleMinutes > 0 && WindowsPlatform.IdleTime().TotalMinutes >= (double)cfg.IdleMinutes;
		bool flag3 = cfg.PauseWhenFullscreen && WindowsPlatform.IsFullscreenAppInForeground();
		bool flag4 = flag3 && _fsPrev;
		_fsPrev = flag3;
		string text = null;
		if (cfg.Paused)
		{
			text = "已暂停";
		}
		else if (!flag)
		{
			text = "Wallpaper Engine 未运行";
		}
		else if (cfg.OnlyWhenVisible && _suspended)
		{
			text = "系统睡眠中";
		}
		else if (cfg.OnlyWhenVisible && _locked)
		{
			text = "已锁屏";
		}
		else if (cfg.OnlyWhenVisible && flag4)
		{
			text = "前台有全屏程序";
		}
		else if (cfg.OnlyWhenVisible && flag2)
		{
			text = "无人操作";
		}
		if (flag && !_weWasRunning)
		{
			_weStartedAt = now;
			_engine.Log.Log("检测到 Wallpaper Engine 已启动");
		}
		_weWasRunning = flag;
		if (!flag)
		{
			_weStartedAt = null;
		}
		DateTime? weStartedAt = _weStartedAt;
		if (weStartedAt.HasValue && (now - _weStartedAt.Value).TotalSeconds >= 20.0)
		{
			_weStartedAt = null;
			if (!cfg.Paused && cfg.SwitchWhenWEStarts)
			{
				DoSwitch("WE 启动");
				_accumulatedSec = 0.0;
				UpdateMenu();
				return;
			}
		}
		if (text != _lastReason)
		{
			if (text == null)
			{
				_engine.Log.Log("计时恢复" + ((_lastReason == null) ? "" : ("（之前：" + _lastReason + "）")));
			}
			else
			{
				_engine.Log.Log("计时暂停：" + text + ((!flag4 || !(text == "前台有全屏程序")) ? "" : ("  " + WindowsPlatform.LastFullscreenInfo)));
			}
			_lastReason = text;
		}
		if (text == null)
		{
			_accumulatedSec += num;
			if (_accumulatedSec >= (double)cfg.IntervalMinutes * 60.0)
			{
				DoSwitch("定时");
				_accumulatedSec = 0.0;
			}
			double sec = Math.Max(0.0, (double)cfg.IntervalMinutes * 60.0 - _accumulatedSec);
			_statusText = "下次切换约 " + FormatSpan(sec);
		}
		else
		{
			_statusText = "计时暂停：" + text;
		}
		UpdateMenu();
	}

	private static string Wrap(string text, int maxCols)
	{
		if (string.IsNullOrEmpty(text))
		{
			return "";
		}
		StringBuilder stringBuilder = new StringBuilder();
		int num = 0;
		int num2 = 1;
		foreach (char c in text)
		{
			int num3 = ((c <= '\u2e7f') ? 1 : 2);
			if (num + num3 > maxCols)
			{
				if (num2 >= 3)
				{
					stringBuilder.Append("...");
					return stringBuilder.ToString();
				}
				stringBuilder.Append('\n');
				num = 0;
				num2++;
			}
			stringBuilder.Append(c);
			num += num3;
		}
		return stringBuilder.ToString();
	}

	private static string FormatSpan(double sec)
	{
		int num = (int)(sec / 60.0);
		int num2 = (int)(sec % 60.0);
		return (num <= 0) ? (num2 + " 秒") : (num + " 分 " + num2 + " 秒");
	}

	public void DoSwitch(string why)
	{
		if (_switching)
		{
			return;
		}
		_switching = true;
		if (why == "手动")
		{
			_accumulatedSec = 0.0;
		}
		ThreadPool.QueueUserWorkItem(delegate
		{
			string title = null;
			string error = null;
			try
			{
				title = _engine.SwitchNow();
			}
			catch (Exception ex)
			{
				error = ex.Message;
				_engine.Log.Log("切换失败：" + ex.Message);
			}
			try
			{
				_sync.BeginInvoke((Action)delegate
				{
					_switching = false;
					if (error != null)
					{
						_tray.ShowBalloonTip(5000, "WE 加权轮播", "切换失败：" + error, ToolTipIcon.Error);
					}
					else if (title == null)
					{
						_tray.ShowBalloonTip(3000, "WE 加权轮播", "没有可切换的壁纸，请检查设置。", ToolTipIcon.Warning);
					}
					UpdateMenu();
					Action switched = this.Switched;
					if (switched != null)
					{
						try
						{
							switched();
						}
						catch
						{
						}
					}
				});
			}
			catch
			{
				_switching = false;
			}
		});
	}

	public void ResetTimer()
	{
		_accumulatedSec = 0.0;
		_lastTick = DateTime.Now;
	}

	public void TogglePause()
	{
		_engine.Cfg.Paused = !_engine.Cfg.Paused;
		try
		{
			_engine.Cfg.Save(_engine.ConfigPath);
		}
		catch
		{
		}
		_engine.Log.Log((!_engine.Cfg.Paused) ? "已继续自动切换" : "已暂停自动切换");
		UpdateMenu();
	}

	private void UpdateMenu()
	{
		Config cfg = _engine.Cfg;
		_miPause.Text = ((!cfg.Paused) ? "暂停自动切换(&P)" : "继续自动切换(&P)");
		_miPause.Checked = cfg.Paused;
		string text = ((!string.IsNullOrEmpty(_engine.LastTitle)) ? ("当前：" + _engine.LastTitle) : "");
		_miStatus.Text = _statusText;
		_miCurrent.Text = Wrap(text, 34);
		_miCurrent.Visible = text.Length > 0;
		string text2 = "WE 加权轮播\n" + _statusText;
		if (text.Length > 0)
		{
			text2 = text2 + "\n" + text;
		}
		if (text2.Length > 63)
		{
			text2 = text2.Substring(0, 60) + "...";
		}
		_tray.Text = text2;
	}

	private void OnSessionSwitch(object sender, SessionSwitchEventArgs e)
	{
		switch (e.Reason)
		{
		case SessionSwitchReason.ConsoleDisconnect:
		case SessionSwitchReason.RemoteDisconnect:
		case SessionSwitchReason.SessionLock:
			_locked = true;
			_engine.Log.Log(string.Concat("系统事件：锁屏 / 会话断开（", e.Reason, "）"));
			break;
		case SessionSwitchReason.ConsoleConnect:
		case SessionSwitchReason.RemoteConnect:
		case SessionSwitchReason.SessionLogon:
		case SessionSwitchReason.SessionUnlock:
			_locked = false;
			_lastTick = DateTime.Now;
			_engine.Log.Log(string.Concat("系统事件：解锁 / 会话恢复（", e.Reason, "）"));
			break;
		case SessionSwitchReason.SessionLogoff:
			break;
		}
	}

	private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
	{
		if (e.Mode == PowerModes.Suspend)
		{
			_suspended = true;
			_engine.Log.Log("系统事件：进入睡眠 / 休眠");
		}
		else if (e.Mode == PowerModes.Resume)
		{
			_suspended = false;
			_lastTick = DateTime.Now;
			_engine.Log.Log("系统事件：从睡眠 / 休眠唤醒");
		}
	}

	public static bool IsStartWithWindows()
	{
		try
		{
			using RegistryKey registryKey = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
			return registryKey != null && registryKey.GetValue("WEWeightedRandom") != null;
		}
		catch
		{
			return false;
		}
	}

	public static void SetStartWithWindows(bool on)
	{
		using RegistryKey registryKey = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Run", writable: true);
		if (registryKey != null)
		{
			if (on)
			{
				registryKey.SetValue("WEWeightedRandom", "\"" + Application.ExecutablePath + "\"");
			}
			else
			{
				registryKey.DeleteValue("WEWeightedRandom", throwOnMissingValue: false);
			}
		}
	}

	public void ShowSettings()
	{
		ShowSettings(-1);
	}

	/// <summary>Opens the main window (WPF-UI); page -1 = overview, 0 = settings (the tray menu's numbering).</summary>
	public void ShowSettings(int page)
	{
		try
		{
			ShowMainWindow(page);
		}
		catch (Exception ex)
		{
			_settings = null;
			_engine.Log.Log("主界面打开失败：" + ex);
		}
	}

	private void ShowMainWindow(int page)
	{
		WpfHost.EnsureApp(_engine.Cfg);
		if (_settings == null)
		{
			_settings = new MainWindow(_engine, this);
			_closed = false;
			_settings.Closed += delegate
			{
				_closed = true;
				_settings = null;
			};
			System.Windows.Forms.Integration.ElementHost.EnableModelessKeyboardInterop(_settings);
			_settings.Show();
		}
		else
		{
			if (_settings.WindowState == System.Windows.WindowState.Minimized)
			{
				_settings.WindowState = System.Windows.WindowState.Normal;
			}
			_settings.Show();
			_settings.Activate();
		}
		_settings.SelectPage(page < 0 ? 0 : page + 1);
	}

	private bool _closed = true;

	private static void OpenFile(string path)
	{
		try
		{
			ProcessStartInfo processStartInfo = new ProcessStartInfo(path);
			processStartInfo.UseShellExecute = true;
			Process.Start(processStartInfo);
		}
		catch
		{
		}
	}

	private TrayMenu _trayMenu;

	/// <summary>Right-click menu in the main window's style (WPF-UI); same entries as before.</summary>
	internal void ShowTrayMenu()
	{
		try
		{
			WpfHost.EnsureApp(_engine.Cfg);
			UpdateMenu();
			if (_trayMenu == null)
			{
				_trayMenu = new TrayMenu();
			}
			string fps = _portrait.Fps30State;
			var entries = new System.Collections.Generic.List<TrayMenu.Entry>
			{
				new TrayMenu.Entry { Text = _statusText.Length > 0 ? _statusText : (_engine.Cfg.Paused ? "已暂停" : ("运行中 · 每 " + _engine.Cfg.IntervalMinutes + " 分钟")), Enabled = false, Icon = Wpf.Ui.Controls.SymbolRegular.Timer24 }
			};
			if (string.IsNullOrEmpty(_engine.LastTitle))
			{
				try
				{
					_engine.LastTitle = _engine.LastPlayedTitle();
				}
				catch
				{
				}
			}
			if (!string.IsNullOrEmpty(_engine.LastTitle))
			{
				entries.Add(new TrayMenu.Entry { Text = "当前：" + _engine.LastTitle, Enabled = false, Icon = Wpf.Ui.Controls.SymbolRegular.Image24 });
			}
			entries.Add(new TrayMenu.Entry { Separator = true });
			entries.Add(new TrayMenu.Entry { Text = "打开主界面", Bold = true, Icon = Wpf.Ui.Controls.SymbolRegular.Home24, Click = () => ShowSettings(-1) });
			entries.Add(new TrayMenu.Entry { Text = "立即切换下一张", Icon = Wpf.Ui.Controls.SymbolRegular.ArrowNext24, Click = () => DoSwitch("手动") });
			entries.Add(new TrayMenu.Entry { Text = _engine.Cfg.Paused ? "继续自动切换" : "暂停自动切换", Icon = _engine.Cfg.Paused ? Wpf.Ui.Controls.SymbolRegular.Play24 : Wpf.Ui.Controls.SymbolRegular.Pause24, Click = TogglePause });
			entries.Add(new TrayMenu.Entry { Text = "设置…", Icon = Wpf.Ui.Controls.SymbolRegular.Settings24, Click = () => ShowSettings(0) });
			entries.Add(new TrayMenu.Entry { Separator = true });
			entries.Add(new TrayMenu.Entry { Text = _portrait.State, Enabled = false, Icon = Wpf.Ui.Controls.SymbolRegular.PersonSquare24 });
			entries.Add(new TrayMenu.Entry { Text = "立即分析新壁纸", Icon = Wpf.Ui.Controls.SymbolRegular.Search24, Click = () => _portrait.AnalyseNow() });
			entries.Add(new TrayMenu.Entry { Text = fps.Length > 0 ? (fps.Length > 40 ? fps.Substring(0, 40) + "…" : fps) : "30 帧转码：平板同步时自动进行", Enabled = false, Icon = Wpf.Ui.Controls.SymbolRegular.VideoClip24 });
			entries.Add(new TrayMenu.Entry { Separator = true });
			entries.Add(new TrayMenu.Entry { Text = "查看日志", Icon = Wpf.Ui.Controls.SymbolRegular.DocumentText24, Click = () => OpenFile(_engine.LogPath) });
			entries.Add(new TrayMenu.Entry { Text = "打开程序文件夹", Icon = Wpf.Ui.Controls.SymbolRegular.Folder24, Click = () => OpenFile(_engine.Dir) });
			entries.Add(new TrayMenu.Entry { Separator = true });
			entries.Add(new TrayMenu.Entry { Text = "退出", Icon = Wpf.Ui.Controls.SymbolRegular.Power24, Click = ExitApp });
			_trayMenu.Show(entries);
		}
		catch (Exception ex)
		{
			_engine.Log.Log("托盘菜单打开失败：" + ex);
		}
	}

	public void OnConfigSaved()
	{
		UpdateMenu();
	}

	private void ExitApp()
	{
		_engine.Log.Log("程序退出");
		_timer.Stop();
		SystemEvents.SessionSwitch -= OnSessionSwitch;
		SystemEvents.PowerModeChanged -= OnPowerModeChanged;
		_tray.Visible = false;
		_tray.Dispose();
		ExitThread();
	}
}
