using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Wpf.Ui.Appearance;
using Wpf.Ui.Controls;
using MessageBox = System.Windows.MessageBox;
using MessageBoxButton = System.Windows.MessageBoxButton;

namespace WERandom;

/// <summary>Main window (WPF-UI): overview, settings, weight tiers, wallpaper preview, tablet / phone sync.</summary>
public partial class MainWindow : FluentWindow
{
	public class TierRow
	{
		public string Months { get; set; }

		public string Weight { get; set; }
	}

	public class PreviewRow
	{
		public string Id { get; set; }

		public string Title { get; set; }

		public string Type { get; set; }

		public string Added { get; set; }

		public string Days { get; set; }

		public string Tier { get; set; }

		public string Weight { get; set; }

		public string Played { get; set; }
	}

	private static readonly string[] PageNames = { "概览", "基本设置", "权重档位", "壁纸预览", "设备同步" };

	private readonly Engine _engine;

	private readonly TrayApp _app;

	private readonly FrameworkElement[] _pages;

	private readonly ObservableCollection<TierRow> _tiers = new ObservableCollection<TierRow>();

	private readonly DispatcherTimer _refresh;

	private bool _previewBusy;

	private bool _previewAgain;

	private int _page;

	public MainWindow(Engine engine, TrayApp app)
	{
		_engine = engine;
		_app = app;
		InitializeComponent();
		_pages = new FrameworkElement[] { PageHome, PageBasic, PageTier, PagePreview, PageDevices };
		TierGrid.ItemsSource = _tiers;
		LoadFromConfig();
		_tintSave.Tick += delegate
		{
			_tintSave.Stop();
			try
			{
				_engine.Cfg.Save(_engine.ConfigPath);
			}
			catch
			{
			}
		};
		ApplicationThemeManager.Changed += OnThemeChanged;
		Closed += delegate { ApplicationThemeManager.Changed -= OnThemeChanged; };
		ApplyAppearance();
		_appearanceReady = true;
		SelectPage(0);
		Action onSwitched = () => Dispatcher.BeginInvoke((Action)delegate
		{
			UpdateStatus();
			if (_page == 3)
			{
				RefreshPreview();
			}
		});
		Action onPortrait = () => Dispatcher.BeginInvoke((Action)UpdateDevices);
		_app.Switched += onSwitched;
		_app.Portrait.StateChanged += onPortrait;
		_refresh = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
		_refresh.Tick += delegate { UpdateStatus(); };
		_refresh.Start();
		Closed += delegate
		{
			_refresh.Stop();
			_app.Switched -= onSwitched;
			_app.Portrait.StateChanged -= onPortrait;
		};
		Loaded += delegate
		{
			UpdateStatus();
			UpdateDevices();
			if (string.IsNullOrEmpty(_engine.LastTitle))
			{
				ThreadPool.QueueUserWorkItem(delegate
				{
					string title = "";
					try
					{
						title = _engine.LastPlayedTitle();
					}
					catch
					{
					}
					if (title.Length > 0 && string.IsNullOrEmpty(_engine.LastTitle))
					{
						_engine.LastTitle = title;
						Dispatcher.BeginInvoke((Action)UpdateStatus);
					}
				});
			}
		};
	}

	public void SelectPage(int idx)
	{
		if (idx < 0 || idx >= _pages.Length)
		{
			idx = 0;
		}
		_page = idx;
		for (int i = 0; i < _pages.Length; i++)
		{
			_pages[i].Visibility = i == idx ? Visibility.Visible : Visibility.Collapsed;
		}
		int n = 0;
		foreach (object child in Nav.Children)
		{
			if (child is Wpf.Ui.Controls.Button b)
			{
				b.Appearance = n == idx ? ControlAppearance.Secondary : ControlAppearance.Transparent;
				n++;
			}
		}
		Header.Text = PageNames[idx];
		if (idx == 3)
		{
			RefreshPreview();
		}
		if (idx == 4 || idx == 0)
		{
			UpdateDevices();
		}
	}

	private void Nav_Click(object sender, RoutedEventArgs e)
	{
		if (sender is FrameworkElement fe && fe.Tag != null)
		{
			SelectPage(int.Parse(fe.Tag.ToString()));
		}
	}

	// ---- appearance ----

	private bool _appearanceReady;

	private bool _watching;

	private void Appearance_Changed(object sender, SelectionChangedEventArgs e)
	{
		if (!_appearanceReady)
		{
			return;
		}
		Config cfg = _engine.Cfg;
		cfg.Theme = ThemeBox.SelectedIndex == 1 ? "light" : (ThemeBox.SelectedIndex == 2 ? "dark" : "system");
		cfg.Backdrop = BackdropBox.SelectedIndex == 1 ? "acrylic" : (BackdropBox.SelectedIndex == 2 ? "none" : "mica");
		ApplyAppearance();
		try
		{
			cfg.Save(_engine.ConfigPath);
		}
		catch
		{
		}
	}

	/// <summary>Theme (system / light / dark) and window background (Mica / Acrylic / none) from the config.</summary>
	private void ApplyAppearance()
	{
		Config cfg = _engine.Cfg;
		WindowBackdropType backdrop = cfg.Backdrop == "acrylic" ? WindowBackdropType.Acrylic : (cfg.Backdrop == "none" ? WindowBackdropType.None : WindowBackdropType.Mica);
		if (_watching)
		{
			SystemThemeWatcher.UnWatch(this);
			_watching = false;
		}
		if (cfg.Theme == "light" || cfg.Theme == "dark")
		{
			ApplicationThemeManager.Apply(cfg.Theme == "dark" ? ApplicationTheme.Dark : ApplicationTheme.Light, backdrop, updateAccent: true);
		}
		else
		{
			ApplicationThemeManager.ApplySystemTheme(updateAccent: true);
			SystemThemeWatcher.Watch(this, backdrop, updateAccents: true);
			_watching = true;
		}
		WindowBackdropType = backdrop;
		UpdateTint();
	}

	/// <summary>Grey layer over the acrylic background (dark grey / light grey by the theme in use).</summary>
	private void UpdateTint()
	{
		Config cfg = _engine.Cfg;
		bool acrylic = cfg.Backdrop == "acrylic";
		bool dark = ApplicationThemeManager.GetAppTheme() == ApplicationTheme.Dark;
		Tint.Fill = new System.Windows.Media.SolidColorBrush(dark ? System.Windows.Media.Color.FromRgb(0x20, 0x20, 0x20) : System.Windows.Media.Color.FromRgb(0xF3, 0xF3, 0xF3));
		Tint.Opacity = acrylic ? cfg.TintPercent / 100.0 : 0.0;
		PageLayer.Background = acrylic ? System.Windows.Media.Brushes.Transparent : (System.Windows.Media.Brush)FindResource("LayerFillColorDefaultBrush");
		TintCard.Visibility = acrylic ? Visibility.Visible : Visibility.Collapsed;
		TintText.Text = cfg.TintPercent + "%";
	}

	private void OnThemeChanged(ApplicationTheme theme, System.Windows.Media.Color accent)
	{
		Dispatcher.BeginInvoke((Action)UpdateTint);
	}

	private void Tint_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
	{
		if (!_appearanceReady)
		{
			return;
		}
		_engine.Cfg.TintPercent = (int)Math.Round(TintSlider.Value);
		UpdateTint();
		_tintSave.Stop();
		_tintSave.Start();
	}

	private readonly DispatcherTimer _tintSave = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };

	// ---- overview ----

	private void UpdateStatus()
	{
		Config cfg = _engine.Cfg;
		SideStatus.Text = cfg.Paused ? "已暂停" : ("运行中 · 每 " + cfg.IntervalMinutes + " 分钟");
		HomeCurrent.Text = string.IsNullOrEmpty(_engine.LastTitle) ? "还没有切换过" : _engine.LastTitle;
		HomeStatus.Text = cfg.Paused ? "已暂停自动切换" : (_app.StatusText.Length > 0 ? _app.StatusText : ("每 " + cfg.IntervalMinutes + " 分钟切换一次"));
		PauseButton.Content = cfg.Paused ? "继续" : "暂停";
		Engine.QueueState q = _engine.ReadQueue();
		HomeRound.Text = q == null ? "还没开始第一轮" : ("始于 " + q.Started.ToString("MM-dd HH:mm") + "，剩余 " + q.Remaining + " / " + q.Length + " 次");
	}

	private void SwitchNow_Click(object sender, RoutedEventArgs e)
	{
		_app.DoSwitch("手动");
		UpdateStatus();
	}

	private void Pause_Click(object sender, RoutedEventArgs e)
	{
		_app.TogglePause();
		UpdateStatus();
	}

	private void ResetRound_Click(object sender, RoutedEventArgs e)
	{
		_engine.ResetQueue();
		_app.ResetTimer();
		UpdateStatus();
		if (_page == 3)
		{
			RefreshPreview();
		}
	}

	private void OpenLog_Click(object sender, RoutedEventArgs e)
	{
		Open(_engine.LogPath);
	}

	private void OpenFolder_Click(object sender, RoutedEventArgs e)
	{
		Open(_engine.Dir);
	}

	private static void Open(string path)
	{
		try
		{
			Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
		}
		catch
		{
		}
	}

	// ---- devices ----

	private void UpdateDevices()
	{
		PortraitState.Text = _app.Portrait.State;
		string fps = _app.Portrait.Fps30State;
		Fps30State.Text = fps.Length > 0 ? fps : "平板同步时自动进行（还没有收到过请求）";
		LanState.Text = "本机地址 " + string.Join("、", LocalAddresses()) + "，端口 " + PortraitService.HttpPort + "（设备自动发现用 " + PortraitService.DiscoveryPort + "）";
		HomeDevices.Text = _app.Portrait.State;
	}

	private static IEnumerable<string> LocalAddresses()
	{
		try
		{
			return Dns.GetHostEntry(Dns.GetHostName()).AddressList
				.Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a))
				.Select(a => a.ToString()).ToList();
		}
		catch
		{
			return new[] { "?" };
		}
	}

	private void Analyse_Click(object sender, RoutedEventArgs e)
	{
		_app.Portrait.AnalyseNow();
		UpdateDevices();
	}

	// ---- settings ----

	private void BrowseExe_Click(object sender, RoutedEventArgs e)
	{
		var dlg = new Microsoft.Win32.OpenFileDialog { Filter = "wallpaper64.exe|wallpaper64.exe;wallpaper32.exe|所有文件|*.*", Title = "选择 wallpaper64.exe" };
		if (dlg.ShowDialog(this) == true)
		{
			ExePath.Text = dlg.FileName;
		}
	}

	private void BrowseWorkshop_Click(object sender, RoutedEventArgs e)
	{
		using var dlg = new System.Windows.Forms.FolderBrowserDialog { Description = "选择 steamapps\\workshop\\content\\431960 目录" };
		if (dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK)
		{
			WorkshopPath.Text = dlg.SelectedPath;
		}
	}

	private void LoadFromConfig()
	{
		Config cfg = _engine.Cfg;
		Interval.Value = Math.Min(1440, Math.Max(1, cfg.IntervalMinutes));
		Startup.IsChecked = TrayApp.IsStartWithWindows();
		ThemeBox.SelectedIndex = cfg.Theme == "light" ? 1 : (cfg.Theme == "dark" ? 2 : 0);
		BackdropBox.SelectedIndex = cfg.Backdrop == "acrylic" ? 1 : (cfg.Backdrop == "none" ? 2 : 0);
		TintSlider.Value = cfg.TintPercent;
		OnlyVisible.IsChecked = cfg.OnlyWhenVisible;
		Idle.Value = Math.Min(600, Math.Max(0, cfg.IdleMinutes));
		SwitchOnStart.IsChecked = cfg.SwitchWhenWEStarts;
		Fullscreen.IsChecked = cfg.PauseWhenFullscreen;
		Mode.SelectedIndex = cfg.Mode == "random" ? 2 : (cfg.Mode == "cycle" ? 1 : 0);
		Rebalance.IsChecked = cfg.RebalanceMidCycle;
		AvoidRecent.Value = Math.Min(100, Math.Max(0, cfg.AvoidRecentCount));
		bool all = string.IsNullOrEmpty(cfg.Monitors) || cfg.Monitors.Trim().ToLowerInvariant() == "all";
		MonAll.IsChecked = all;
		MonList.IsChecked = !all;
		MonText.Text = all ? "0" : cfg.Monitors;
		SameOnAll.IsChecked = cfg.SameOnAllMonitors;
		ExePath.Text = cfg.WallpaperEnginePath ?? "";
		WorkshopPath.Text = cfg.WorkshopPaths.Count > 0 ? cfg.WorkshopPaths[0] : "";
		ExcludeIds.Text = string.Join(", ", cfg.ExcludeIds.ToArray());
		TScene.IsChecked = cfg.IncludeTypes.Contains("scene");
		TVideo.IsChecked = cfg.IncludeTypes.Contains("video");
		TWeb.IsChecked = cfg.IncludeTypes.Contains("web");
		_tiers.Clear();
		foreach (Tier t in cfg.Tiers.OrderBy(t => t.MaxMonths))
		{
			_tiers.Add(new TierRow { Months = Engine.Fmt(t.MaxMonths), Weight = Engine.Fmt(t.Weight) });
		}
		Older.Value = Math.Min(1000, Math.Max(0, cfg.OlderWeight));
	}

	private void Save_Click(object sender, RoutedEventArgs e)
	{
		if (SaveAll(resetCycle: false) && _page == 3)
		{
			RefreshPreview();
		}
	}

	private void SaveReset_Click(object sender, RoutedEventArgs e)
	{
		if (SaveAll(resetCycle: true))
		{
			UpdateStatus();
			if (_page == 3)
			{
				RefreshPreview();
			}
		}
	}

	private bool SaveAll(bool resetCycle)
	{
		Config cfg = _engine.Cfg;
		TierGrid.CommitEdit(DataGridEditingUnit.Row, true);
		var tiers = new List<Tier>();
		foreach (TierRow row in _tiers)
		{
			string m = (row.Months ?? "").Trim();
			string w = (row.Weight ?? "").Trim();
			if (m.Length == 0 && w.Length == 0)
			{
				continue;
			}
			if (!double.TryParse(m, NumberStyles.Float, CultureInfo.InvariantCulture, out double months) || months <= 0
				|| !double.TryParse(w, NumberStyles.Float, CultureInfo.InvariantCulture, out double weight) || weight < 0)
			{
				MessageBox.Show(this, "权重档位里有一行数字不对：月数必须大于 0，次数必须大于等于 0。", "WE 加权轮播", MessageBoxButton.OK, MessageBoxImage.Exclamation);
				SelectPage(2);
				return false;
			}
			tiers.Add(new Tier(months, weight));
		}
		if (tiers.Count == 0)
		{
			MessageBox.Show(this, "至少要有一个档位。", "WE 加权轮播", MessageBoxButton.OK, MessageBoxImage.Exclamation);
			SelectPage(2);
			return false;
		}
		cfg.IntervalMinutes = (int)(Interval.Value ?? cfg.IntervalMinutes);
		cfg.OnlyWhenVisible = OnlyVisible.IsChecked == true;
		cfg.IdleMinutes = (int)(Idle.Value ?? 0);
		cfg.SwitchWhenWEStarts = SwitchOnStart.IsChecked == true;
		cfg.PauseWhenFullscreen = Fullscreen.IsChecked == true;
		cfg.Mode = Mode.SelectedIndex == 2 ? "random" : (Mode.SelectedIndex == 1 ? "cycle" : "grouped");
		cfg.RebalanceMidCycle = Rebalance.IsChecked == true;
		cfg.AvoidRecentCount = (int)(AvoidRecent.Value ?? 0);
		cfg.Monitors = MonAll.IsChecked == true ? "all" : (MonText.Text.Trim().Length > 0 ? MonText.Text.Trim() : "0");
		cfg.SameOnAllMonitors = SameOnAll.IsChecked == true;
		cfg.WallpaperEnginePath = ExePath.Text.Trim();
		cfg.WorkshopPaths = WorkshopPath.Text.Trim().Length > 0 ? new List<string> { WorkshopPath.Text.Trim() } : new List<string>();
		cfg.ExcludeIds = ExcludeIds.Text.Split(new[] { ',', ' ', ';', '，' }, StringSplitOptions.RemoveEmptyEntries).Select(s => s.Trim()).ToList();
		cfg.IncludeTypes = new List<string>();
		if (TScene.IsChecked == true)
		{
			cfg.IncludeTypes.Add("scene");
		}
		if (TVideo.IsChecked == true)
		{
			cfg.IncludeTypes.Add("video");
		}
		if (TWeb.IsChecked == true)
		{
			cfg.IncludeTypes.Add("web");
		}
		cfg.Tiers = tiers.OrderBy(t => t.MaxMonths).ToList();
		cfg.OlderWeight = Older.Value ?? 0;
		try
		{
			cfg.Save(_engine.ConfigPath);
		}
		catch (Exception ex)
		{
			MessageBox.Show(this, "保存配置失败：" + ex.Message, "WE 加权轮播", MessageBoxButton.OK, MessageBoxImage.Error);
			return false;
		}
		try
		{
			TrayApp.SetStartWithWindows(Startup.IsChecked == true);
		}
		catch (Exception ex2)
		{
			_engine.Log.Log("设置开机自启失败：" + ex2.Message);
		}
		if (resetCycle)
		{
			_engine.ResetQueue();
			_app.ResetTimer();
		}
		_engine.Log.Log("设置已保存" + (resetCycle ? "，并重新开一轮" : ""));
		_app.OnConfigSaved();
		UpdateStatus();
		SaveStatus.Text = "已保存 " + DateTime.Now.ToString("HH:mm:ss");
		return true;
	}

	// ---- preview ----

	private void RefreshPreview_Click(object sender, RoutedEventArgs e)
	{
		RefreshPreview();
	}

	private void RefreshPreview()
	{
		if (_previewBusy)
		{
			_previewAgain = true;
			return;
		}
		_previewBusy = true;
		if (PreviewGrid.Items.Count == 0)
		{
			Summary.Text = "正在扫描壁纸…";
		}
		ThreadPool.QueueUserWorkItem(delegate
		{
			Engine.Preview p;
			try
			{
				p = _engine.BuildPreview();
			}
			catch (Exception ex)
			{
				p = new Engine.Preview { Error = ex.Message };
			}
			Dispatcher.BeginInvoke((Action)delegate
			{
				_previewBusy = false;
				ShowPreview(p);
				if (_previewAgain)
				{
					_previewAgain = false;
					RefreshPreview();
				}
			});
		});
	}

	private void ShowPreview(Engine.Preview p)
	{
		if (p.Error != null)
		{
			Summary.Text = "无法预览：" + p.Error;
			PreviewGrid.ItemsSource = null;
			return;
		}
		Engine.QueueState queue = p.Queue;
		string[] tiers = (from w in p.All
			group w by w.Tier into g
			orderby g.Min(w => w.AgeDays)
			select g.Key + " " + g.Count() + "张×" + Engine.Fmt(g.First().Weight)).ToArray();
		string text = "共 " + p.All.Count + " 张壁纸　" + string.Join("　", tiers) + "\r\n";
		if (_engine.Cfg.Mode == "random")
		{
			text += "纯加权随机模式：次数为相对概率。";
		}
		else
		{
			text += "一轮 = " + p.CycleLength + " 次切换，按每 " + _engine.Cfg.IntervalMinutes + " 分钟、每天用电脑 8 小时估算约 " + p.EstimatedDays.ToString("0.0") + " 天。";
			if (queue != null)
			{
				text += "　当前轮：始于 " + queue.Started.ToString("MM-dd HH:mm") + "，剩余 " + queue.Remaining + "/" + queue.Length + "。";
				if (queue.Groups != null && queue.CurrentGroup >= 0)
				{
					var groups = new List<string>();
					for (int i = 0; i < queue.Groups.Count; i++)
					{
						groups.Add(_engine.GroupName(i) + " 剩 " + queue.Groups[i].Count);
					}
					text += "\r\n正在放第 " + (queue.CurrentGroup + 1) + " 组（" + _engine.GroupName(queue.CurrentGroup) + "）；各组：" + string.Join("，", groups.ToArray());
				}
			}
			else
			{
				text += "　还没开始第一轮。";
			}
		}
		text += "\r\nWallpaper Engine：" + p.Paths.Exe + "　创意工坊：" + string.Join("; ", p.Paths.Workshop.ToArray());
		Summary.Text = text;
		var rows = new List<PreviewRow>(p.All.Count);
		foreach (Wallpaper w in p.All)
		{
			string played = "";
			if (queue != null && queue.Members.Contains(w.Id))
			{
				int done = queue.Played.ContainsKey(w.Id) ? queue.Played[w.Id] : 0;
				int left = queue.Groups == null ? queue.Queue.Count(x => x == w.Id) : queue.Groups.Sum(g => g.Count(x => x == w.Id));
				played = done + " / " + left;
			}
			rows.Add(new PreviewRow
			{
				Id = w.Id,
				Title = w.Title,
				Type = w.Type,
				Added = w.Added.ToString("yyyy-MM-dd"),
				Days = Engine.Fmt(w.AgeDays),
				Tier = w.Tier,
				Weight = Engine.Fmt(w.Weight),
				Played = played
			});
		}
		PreviewGrid.ItemsSource = rows;
	}
}
