using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Windows.Forms;

namespace WERandom;

public class SettingsForm : Form
{
	private readonly Engine _engine;

	private readonly TrayApp _app;

	private readonly float _k;

	private readonly List<NavButton> _navButtons = new List<NavButton>();

	private readonly List<Panel> _pages = new List<Panel>();

	private Label _header;

	private Label _sideStatus;

	private NumericUpDown _interval;

	private NumericUpDown _idle;

	private NumericUpDown _older;

	private NumericUpDown _avoidRecent;

	private CheckBox _startup;

	private CheckBox _onlyVisible;

	private CheckBox _switchOnStart;

	private CheckBox _fullscreen;

	private CheckBox _rebalance;

	private CheckBox _sameOnAll;

	private CheckBox _tScene;

	private CheckBox _tVideo;

	private CheckBox _tWeb;

	private ComboBox _mode;

	private RadioButton _monAll;

	private RadioButton _monList;

	private TextBox _monText;

	private TextBox _exePath;

	private TextBox _workshopPath;

	private TextBox _excludeIds;

	private DataGridView _grid;

	private ListView _list;

	private Label _summary;

	private Label _status;

	private bool _previewBusy;

	private bool _previewAgain;

	public SettingsForm(Engine engine, TrayApp app)
	{
		SettingsForm settingsForm = this;
		_engine = engine;
		_app = app;
		_k = Theme.ScreenScale();
		Text = "WE 加权轮播";
		base.StartPosition = FormStartPosition.CenterScreen;
		base.AutoScaleMode = AutoScaleMode.None;
		Font = Theme.UiFont(10f);
		BackColor = Theme.Window;
		ForeColor = Theme.Text;
		base.ClientSize = new Size(S(980), S(660));
		MinimumSize = new Size(S(820), S(560));
		try
		{
			base.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
		}
		catch
		{
		}
		Panel side = new Panel
		{
			Dock = DockStyle.Left,
			Width = S(200),
			BackColor = Theme.Sidebar,
			Padding = new Padding(S(0), S(16), S(0), S(0))
		};
		Label value = new Label
		{
			Text = "WE 加权轮播",
			Font = Theme.UiFont(13f, FontStyle.Bold),
			AutoSize = true,
			Location = new Point(S(22), S(20)),
			BackColor = Theme.Sidebar
		};
		_sideStatus = new Label
		{
			Text = "",
			Font = Theme.UiFont(9f),
			ForeColor = Theme.TextMuted,
			AutoSize = true,
			Location = new Point(S(23), S(52)),
			BackColor = Theme.Sidebar
		};
		side.Controls.Add(value);
		side.Controls.Add(_sideStatus);
		FlowLayoutPanel nav = new FlowLayoutPanel
		{
			Location = new Point(S(0), S(90)),
			Width = S(200),
			AutoSize = true,
			FlowDirection = FlowDirection.TopDown,
			WrapContents = false,
			BackColor = Theme.Sidebar
		};
		side.Controls.Add(nav);
		side.Resize += delegate
		{
			nav.Width = side.Width;
		};
		Panel panel = new Panel
		{
			Dock = DockStyle.Fill,
			BackColor = Theme.Window
		};
		_header = new Label
		{
			Dock = DockStyle.Top,
			AutoSize = false,
			Height = S(64),
			Font = Theme.UiFont(17f, FontStyle.Bold),
			Padding = new Padding(S(28), S(18), S(0), S(0)),
			TextAlign = ContentAlignment.TopLeft
		};
		Panel panel2 = new Panel
		{
			Dock = DockStyle.Bottom,
			AutoSize = true,
			AutoSizeMode = AutoSizeMode.GrowAndShrink,
			Padding = new Padding(S(28), S(12), S(20), S(14)),
			BackColor = Theme.Window
		};
		Panel value2 = new Panel
		{
			Dock = DockStyle.Top,
			Height = S(1),
			BackColor = Theme.Border
		};
		FlowLayoutPanel flowLayoutPanel = new FlowLayoutPanel
		{
			Dock = DockStyle.Top,
			AutoSize = true,
			AutoSizeMode = AutoSizeMode.GrowAndShrink,
			FlowDirection = FlowDirection.RightToLeft,
			WrapContents = false,
			Padding = new Padding(S(0), S(10), S(0), S(0))
		};
		ModernButton modernButton = new ModernButton
		{
			Text = "关闭",
			Margin = new Padding(S(8), S(0), S(0), S(0))
		};
		modernButton.Click += delegate
		{
			settingsForm.Close();
		};
		ModernButton modernButton2 = new ModernButton
		{
			Text = "立即切换一张",
			Margin = new Padding(S(8), S(0), S(0), S(0))
		};
		modernButton2.Click += delegate
		{
			if (settingsForm.SaveAll(resetCycle: false))
			{
				settingsForm._app.DoSwitch("手动");
			}
		};
		Action onSwitched = delegate
		{
			if (!settingsForm.IsDisposed)
			{
				settingsForm.RefreshPreview();
			}
		};
		_app.Switched += onSwitched;
		base.FormClosed += delegate
		{
			settingsForm._app.Switched -= onSwitched;
		};
		ModernButton modernButton3 = new ModernButton
		{
			Text = "保存并重新开一轮",
			Margin = new Padding(S(8), S(0), S(0), S(0))
		};
		modernButton3.Click += delegate
		{
			if (settingsForm.SaveAll(resetCycle: true))
			{
				settingsForm.RefreshPreview();
			}
		};
		ModernButton modernButton4 = new ModernButton
		{
			Text = "保存",
			Primary = true,
			Margin = new Padding(S(8), S(0), S(0), S(0))
		};
		modernButton4.Click += delegate
		{
			if (settingsForm.SaveAll(resetCycle: false))
			{
				settingsForm.RefreshPreview();
			}
		};
		_status = new Label
		{
			AutoSize = true,
			ForeColor = Theme.TextMuted,
			Margin = new Padding(S(0), S(8), S(0), S(0))
		};
		flowLayoutPanel.Controls.Add(modernButton);
		flowLayoutPanel.Controls.Add(modernButton2);
		flowLayoutPanel.Controls.Add(modernButton3);
		flowLayoutPanel.Controls.Add(modernButton4);
		flowLayoutPanel.Controls.Add(_status);
		panel2.Controls.Add(flowLayoutPanel);
		panel2.Controls.Add(value2);
		Panel panel3 = new Panel
		{
			Dock = DockStyle.Fill,
			Padding = new Padding(S(28), S(4), S(24), S(8)),
			BackColor = Theme.Window
		};
		AddPage(nav, panel3, "基本设置", BuildBasicPage());
		AddPage(nav, panel3, "权重档位", BuildTierPage());
		AddPage(nav, panel3, "壁纸预览", BuildPreviewPage());
		panel.Controls.Add(panel3);
		panel.Controls.Add(panel2);
		panel.Controls.Add(_header);
		base.Controls.Add(panel);
		base.Controls.Add(side);
		LoadFromConfig();
		SelectPage(0);
		base.Shown += delegate
		{
			settingsForm.RefreshPreview();
			settingsForm.UpdateSideStatus();
		};
	}

	private void AddPage(FlowLayoutPanel nav, Panel content, string name, Panel page)
	{
		int idx = _pages.Count;
		NavButton navButton = new NavButton();
		navButton.Text = name;
		navButton.Width = S(200);
		navButton.Height = S(40);
		navButton.Margin = new Padding(S(0), S(2), S(0), S(2));
		navButton.Font = Theme.UiFont(10.5f);
		NavButton navButton2 = navButton;
		navButton2.Click += delegate
		{
			SelectPage(idx);
		};
		nav.Controls.Add(navButton2);
		_navButtons.Add(navButton2);
		page.Dock = DockStyle.Fill;
		page.Visible = false;
		content.Controls.Add(page);
		_pages.Add(page);
	}

	public void SelectPage(int idx)
	{
		for (int i = 0; i < _pages.Count; i++)
		{
			_pages[i].Visible = i == idx;
			_navButtons[i].Selected = i == idx;
			_navButtons[i].Invalidate();
		}
		_header.Text = _navButtons[idx].Text;
		if (idx == 2)
		{
			RefreshPreview();
		}
	}

	private int S(int v)
	{
		return (int)Math.Round((float)v * _k);
	}

	public void UpdateSideStatus()
	{
		_sideStatus.Text = ((!_engine.Cfg.Paused) ? ("运行中 · 每 " + _engine.Cfg.IntervalMinutes + " 分钟") : "已暂停");
	}

	private Label L(string text, bool muted = false)
	{
		Label label = new Label();
		label.Text = text;
		label.AutoSize = true;
		label.Margin = new Padding(S(3), S(9), S(3), S(3));
		label.ForeColor = ((!muted) ? Theme.Text : Theme.TextMuted);
		return label;
	}

	private Label SectionTitle(string text)
	{
		Label label = new Label();
		label.Text = text;
		label.AutoSize = true;
		label.Font = Theme.UiFont(11f, FontStyle.Bold);
		label.Margin = new Padding(S(0), S(14), S(0), S(4));
		label.ForeColor = Theme.Text;
		return label;
	}

	private FlowLayoutPanel Flow(params Control[] cs)
	{
		FlowLayoutPanel flowLayoutPanel = new FlowLayoutPanel();
		flowLayoutPanel.AutoSize = true;
		flowLayoutPanel.AutoSizeMode = AutoSizeMode.GrowAndShrink;
		flowLayoutPanel.WrapContents = true;
		flowLayoutPanel.FlowDirection = FlowDirection.LeftToRight;
		flowLayoutPanel.Margin = new Padding(S(0));
		flowLayoutPanel.Dock = DockStyle.Fill;
		FlowLayoutPanel flowLayoutPanel2 = flowLayoutPanel;
		foreach (Control value in cs)
		{
			flowLayoutPanel2.Controls.Add(value);
		}
		return flowLayoutPanel2;
	}

	private TextBox Tb(int width)
	{
		TextBox textBox = new TextBox();
		textBox.Width = S(width);
		textBox.BorderStyle = BorderStyle.FixedSingle;
		textBox.Margin = new Padding(S(3), S(5), S(3), S(3));
		return textBox;
	}

	private NumericUpDown Num(decimal min, decimal max, int width = 80)
	{
		NumericUpDown numericUpDown = new NumericUpDown();
		numericUpDown.Minimum = min;
		numericUpDown.Maximum = max;
		numericUpDown.Width = S(width);
		numericUpDown.BorderStyle = BorderStyle.FixedSingle;
		numericUpDown.Margin = new Padding(S(3), S(5), S(3), S(3));
		return numericUpDown;
	}

	private CheckBox Cb(string text)
	{
		CheckBox checkBox = new CheckBox();
		checkBox.Text = text;
		checkBox.AutoSize = true;
		checkBox.Margin = new Padding(S(3), S(7), S(3), S(3));
		return checkBox;
	}

	private Panel BuildBasicPage()
	{
		Panel panel = new Panel();
		panel.AutoScroll = true;
		Panel panel2 = panel;
		TableLayoutPanel t = new TableLayoutPanel
		{
			Dock = DockStyle.Top,
			AutoSize = true,
			AutoSizeMode = AutoSizeMode.GrowAndShrink,
			ColumnCount = 2,
			Padding = new Padding(S(0), S(0), S(0), S(12))
		};
		t.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
		t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
		int row = 0;
		Action<Control, Control> action = delegate(Control a, Control b)
		{
			t.RowStyles.Add(new RowStyle(SizeType.AutoSize));
			if (a != null)
			{
				t.Controls.Add(a, 0, row);
			}
			if (b != null)
			{
				t.Controls.Add(b, (a != null) ? 1 : 0, row);
				if (a == null)
				{
					t.SetColumnSpan(b, 2);
				}
			}
			row++;
		};
		action(null, SectionTitle("切换"));
		_interval = Num(1m, 1440m);
		action(L("切换间隔"), Flow(_interval, L("分钟切换一次")));
		_startup = Cb("开机自动启动（登录 Windows 后自动在托盘运行）");
		action(null, _startup);
		_switchOnStart = Cb("Wallpaper Engine 被重新打开时，立刻切换一张");
		action(null, _switchOnStart);
		action(null, SectionTitle("什么时候计时"));
		_onlyVisible = Cb("只在能看到桌面时计时（Wallpaper Engine 在运行、未锁屏、未睡眠）");
		action(null, _onlyVisible);
		_fullscreen = Cb("前台有全屏程序时暂停计时（玩游戏、全屏看视频时壁纸反正看不到）");
		action(null, _fullscreen);
		_idle = Num(0m, 600m);
		action(L("无操作判定"), Flow(L("键盘鼠标超过"), _idle, L("分钟没动过就暂停计时（0 = 不判断）", muted: true)));
		action(null, SectionTitle("播放方式"));
		_mode = new ComboBox
		{
			DropDownStyle = ComboBoxStyle.DropDownList,
			Width = S(460),
			FlatStyle = FlatStyle.System,
			Margin = new Padding(S(3), S(5), S(3), S(3))
		};
		_mode.Items.Add("分组轮播：先放完最新一档，再放下一档；组内随机，每张出现设定次数（推荐）");
		_mode.Items.Add("混合轮播：各档混在一起均匀打散，每张出现设定次数");
		_mode.Items.Add("纯加权随机：次数只是相对概率，不保证每张都出现");
		action(L("播放模式"), _mode);
		_rebalance = Cb("壁纸在一轮中途跨入下一档（或改了次数）时，立刻调整它本轮剩余的次数");
		action(null, _rebalance);
		_avoidRecent = Num(0m, 100m);
		action(L("随机模式防重复"), Flow(L("最近播过的"), _avoidRecent, L("张不马上重复（轮播模式不需要）", muted: true)));
		action(null, SectionTitle("显示器"));
		_monAll = new RadioButton
		{
			Text = "所有显示器",
			AutoSize = true,
			Checked = true,
			Margin = new Padding(S(3), S(6), S(3), S(3))
		};
		_monList = new RadioButton
		{
			Text = "指定：",
			AutoSize = true,
			Margin = new Padding(S(12), S(6), S(3), S(3))
		};
		_monText = Tb(90);
		_sameOnAll = Cb("多显示器用同一张");
		_sameOnAll.Margin = new Padding(S(16), S(6), S(3), S(3));
		action(L("应用到"), Flow(_monAll, _monList, _monText, L("（0 = 第一台，多个用逗号）", muted: true), _sameOnAll));
		action(null, SectionTitle("路径与过滤"));
		_exePath = Tb(440);
		ModernButton modernButton = new ModernButton();
		modernButton.Text = "浏览...";
		modernButton.MinimumSize = new Size(S(70), S(28));
		modernButton.Padding = new Padding(S(10), S(3), S(10), S(3));
		modernButton.Margin = new Padding(S(3), S(3), S(3), S(3));
		ModernButton modernButton2 = modernButton;
		modernButton2.Click += delegate
		{
			using OpenFileDialog openFileDialog = new OpenFileDialog
			{
				Filter = "wallpaper64.exe|wallpaper64.exe;wallpaper32.exe|所有文件|*.*",
				Title = "选择 wallpaper64.exe"
			};
			if (openFileDialog.ShowDialog(this) == DialogResult.OK)
			{
				_exePath.Text = openFileDialog.FileName;
			}
		};
		action(L("Wallpaper Engine"), Flow(_exePath, modernButton2, L("留空 = 自动检测", muted: true)));
		_workshopPath = Tb(440);
		modernButton = new ModernButton();
		modernButton.Text = "浏览...";
		modernButton.MinimumSize = new Size(S(70), S(28));
		modernButton.Padding = new Padding(S(10), S(3), S(10), S(3));
		modernButton.Margin = new Padding(S(3), S(3), S(3), S(3));
		ModernButton modernButton3 = modernButton;
		modernButton3.Click += delegate
		{
			using FolderBrowserDialog folderBrowserDialog = new FolderBrowserDialog
			{
				Description = "选择 steamapps\\workshop\\content\\431960 目录"
			};
			if (folderBrowserDialog.ShowDialog(this) == DialogResult.OK)
			{
				_workshopPath.Text = folderBrowserDialog.SelectedPath;
			}
		};
		action(L("创意工坊目录"), Flow(_workshopPath, modernButton3, L("留空 = 自动检测", muted: true)));
		_excludeIds = Tb(520);
		action(L("永不播放的 ID"), Flow(_excludeIds, L("逗号分隔，ID 见预览页", muted: true)));
		_tScene = Cb("scene");
		_tVideo = Cb("video");
		_tWeb = Cb("web");
		action(L("只播放类型"), Flow(_tScene, _tVideo, _tWeb, L("（都不勾 = 全部类型）", muted: true)));
		panel2.Controls.Add(t);
		return panel2;
	}

	private Panel BuildTierPage()
	{
		Panel page = new Panel();
		Label intro = new Label
		{
			Dock = DockStyle.Top,
			AutoSize = true,
			Padding = new Padding(S(0), S(4), S(0), S(12)),
			ForeColor = Theme.TextMuted,
			Text = "按壁纸的「添加时间」分档：添加时间不超过某档月数的壁纸，每一轮就出现该档的次数。\r\n次数 0 = 不播放；月数支持小数（0.5 = 半个月）。直接在表格里改数字；在最后一行空行输入可以新增档位；选中一行按 Delete 删除。"
		};
		_grid = new DataGridView
		{
			Dock = DockStyle.Fill,
			AllowUserToAddRows = true,
			AllowUserToDeleteRows = true,
			AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
			RowHeadersVisible = false,
			SelectionMode = DataGridViewSelectionMode.FullRowSelect,
			MultiSelect = false,
			AllowUserToResizeRows = false,
			BorderStyle = BorderStyle.None,
			BackgroundColor = Theme.Window,
			CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal,
			GridColor = Theme.Border,
			EnableHeadersVisualStyles = false,
			ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.AutoSize,
			ColumnHeadersBorderStyle = DataGridViewHeaderBorderStyle.Single
		};
		_grid.RowTemplate.Height = (int)(34f * _k);
		_grid.RowTemplate.MinimumHeight = (int)(34f * _k);
		_grid.ColumnHeadersDefaultCellStyle.BackColor = Theme.Secondary;
		_grid.ColumnHeadersDefaultCellStyle.ForeColor = Theme.TextMuted;
		_grid.ColumnHeadersDefaultCellStyle.SelectionBackColor = Theme.Secondary;
		_grid.ColumnHeadersDefaultCellStyle.Padding = new Padding(S(6), S(8), S(6), S(8));
		_grid.DefaultCellStyle.Padding = new Padding(S(6), S(0), S(6), S(0));
		_grid.DefaultCellStyle.Font = Theme.UiFont(10.5f);
		_grid.DefaultCellStyle.BackColor = Theme.Window;
		_grid.DefaultCellStyle.ForeColor = Theme.Text;
		_grid.RowsDefaultCellStyle.BackColor = Theme.Window;
		_grid.RowsDefaultCellStyle.ForeColor = Theme.Text;
		_grid.DefaultCellStyle.SelectionBackColor = Theme.Selection;
		_grid.DefaultCellStyle.SelectionForeColor = Theme.Text;
		_grid.Columns.Add("months", "添加时间不超过（月）");
		_grid.Columns.Add("weight", "每轮出现次数");
		_grid.DataError += delegate(object s, DataGridViewDataErrorEventArgs e)
		{
			e.ThrowException = false;
		};
		FlowLayoutPanel flowLayoutPanel = new FlowLayoutPanel();
		flowLayoutPanel.Dock = DockStyle.Bottom;
		flowLayoutPanel.AutoSize = true;
		flowLayoutPanel.AutoSizeMode = AutoSizeMode.GrowAndShrink;
		flowLayoutPanel.Padding = new Padding(S(0), S(10), S(0), S(0));
		FlowLayoutPanel flowLayoutPanel2 = flowLayoutPanel;
		_older = Num(0m, 1000m);
		_older.DecimalPlaces = 1;
		flowLayoutPanel2.Controls.Add(L("超过最后一档的更老壁纸，每轮出现"));
		flowLayoutPanel2.Controls.Add(_older);
		flowLayoutPanel2.Controls.Add(L("次（0 = 不再播放）", muted: true));
		page.Controls.Add(_grid);
		page.Controls.Add(flowLayoutPanel2);
		page.Controls.Add(intro);
		page.Resize += delegate
		{
			intro.MaximumSize = new Size(Math.Max(100, page.ClientSize.Width), 0);
		};
		return page;
	}

	private Panel BuildPreviewPage()
	{
		Panel page = new Panel();
		_summary = new Label
		{
			Dock = DockStyle.Top,
			AutoSize = true,
			Padding = new Padding(S(0), S(4), S(0), S(10)),
			Text = "...",
			ForeColor = Theme.TextMuted
		};
		_list = new ListView
		{
			Dock = DockStyle.Fill,
			View = View.Details,
			FullRowSelect = true,
			GridLines = false,
			BorderStyle = BorderStyle.None,
			HeaderStyle = ColumnHeaderStyle.Nonclickable
		};
		_list.Columns.Add("ID", (int)(105f * _k));
		_list.Columns.Add("标题", (int)(200f * _k));
		_list.Columns.Add("类型", (int)(60f * _k));
		_list.Columns.Add("添加日期", (int)(100f * _k));
		_list.Columns.Add("天数", (int)(60f * _k));
		_list.Columns.Add("档位", (int)(75f * _k));
		_list.Columns.Add("每轮次数", (int)(80f * _k));
		_list.Columns.Add("本轮已播 / 剩余", (int)(120f * _k));
		FlowLayoutPanel flowLayoutPanel = new FlowLayoutPanel();
		flowLayoutPanel.Dock = DockStyle.Bottom;
		flowLayoutPanel.AutoSize = true;
		flowLayoutPanel.AutoSizeMode = AutoSizeMode.GrowAndShrink;
		flowLayoutPanel.Padding = new Padding(S(0), S(10), S(0), S(0));
		FlowLayoutPanel flowLayoutPanel2 = flowLayoutPanel;
		ModernButton modernButton = new ModernButton();
		modernButton.Text = "刷新预览";
		modernButton.Margin = new Padding(S(0), S(0), S(8), S(0));
		ModernButton modernButton2 = modernButton;
		modernButton2.Click += delegate
		{
			RefreshPreview();
		};
		modernButton = new ModernButton();
		modernButton.Text = "重新开一轮";
		modernButton.Margin = new Padding(S(0), S(0), S(8), S(0));
		ModernButton modernButton3 = modernButton;
		modernButton3.Click += delegate
		{
			_engine.ResetQueue();
			_app.ResetTimer();
			RefreshPreview();
		};
		modernButton = new ModernButton();
		modernButton.Text = "查看日志";
		modernButton.Margin = new Padding(S(0), S(0), S(8), S(0));
		ModernButton modernButton4 = modernButton;
		modernButton4.Click += delegate
		{
			try
			{
				Process.Start(new ProcessStartInfo(_engine.LogPath)
				{
					UseShellExecute = true
				});
			}
			catch
			{
			}
		};
		flowLayoutPanel2.Controls.Add(modernButton2);
		flowLayoutPanel2.Controls.Add(modernButton3);
		flowLayoutPanel2.Controls.Add(modernButton4);
		flowLayoutPanel2.Controls.Add(L("预览按已保存的设置计算；改了设置请先保存。", muted: true));
		page.Controls.Add(_list);
		page.Controls.Add(flowLayoutPanel2);
		page.Controls.Add(_summary);
		page.Resize += delegate
		{
			_summary.MaximumSize = new Size(Math.Max(100, page.ClientSize.Width), 0);
		};
		_list.Resize += delegate
		{
			FitTitleColumn();
		};
		return page;
	}

	private void FitTitleColumn()
	{
		if (_list.Columns.Count < 2)
		{
			return;
		}
		int num = 0;
		for (int i = 0; i < _list.Columns.Count; i++)
		{
			if (i != 1)
			{
				num += _list.Columns[i].Width;
			}
		}
		int val = _list.ClientSize.Width - num - (int)(4f * _k);
		_list.Columns[1].Width = Math.Max((int)(120f * _k), val);
	}

	private void LoadFromConfig()
	{
		Config cfg = _engine.Cfg;
		_interval.Value = Math.Min(_interval.Maximum, Math.Max(_interval.Minimum, cfg.IntervalMinutes));
		_startup.Checked = TrayApp.IsStartWithWindows();
		_onlyVisible.Checked = cfg.OnlyWhenVisible;
		_idle.Value = Math.Min(_idle.Maximum, Math.Max(_idle.Minimum, cfg.IdleMinutes));
		_switchOnStart.Checked = cfg.SwitchWhenWEStarts;
		_fullscreen.Checked = cfg.PauseWhenFullscreen;
		_mode.SelectedIndex = ((cfg.Mode == "random") ? 2 : ((cfg.Mode == "cycle") ? 1 : 0));
		_rebalance.Checked = cfg.RebalanceMidCycle;
		_avoidRecent.Value = Math.Min(_avoidRecent.Maximum, Math.Max(0, cfg.AvoidRecentCount));
		bool flag = string.IsNullOrEmpty(cfg.Monitors) || cfg.Monitors.Trim().ToLowerInvariant() == "all";
		_monAll.Checked = flag;
		_monList.Checked = !flag;
		_monText.Text = ((!flag) ? cfg.Monitors : "0");
		_sameOnAll.Checked = cfg.SameOnAllMonitors;
		_exePath.Text = cfg.WallpaperEnginePath ?? "";
		_workshopPath.Text = ((cfg.WorkshopPaths.Count <= 0) ? "" : cfg.WorkshopPaths[0]);
		_excludeIds.Text = string.Join(", ", cfg.ExcludeIds.ToArray());
		_tScene.Checked = cfg.IncludeTypes.Contains("scene");
		_tVideo.Checked = cfg.IncludeTypes.Contains("video");
		_tWeb.Checked = cfg.IncludeTypes.Contains("web");
		_grid.Rows.Clear();
		foreach (Tier item in cfg.Tiers.OrderBy((Tier t) => t.MaxMonths))
		{
			_grid.Rows.Add(Engine.Fmt(item.MaxMonths), Engine.Fmt(item.Weight));
		}
		_older.Value = (decimal)Math.Min((double)_older.Maximum, Math.Max(0.0, cfg.OlderWeight));
	}

	private bool SaveAll(bool resetCycle)
	{
		Config cfg = _engine.Cfg;
		_grid.EndEdit();
		List<Tier> list = new List<Tier>();
		foreach (DataGridViewRow item in (IEnumerable)_grid.Rows)
		{
			if (item.IsNewRow)
			{
				continue;
			}
			string text = Convert.ToString(item.Cells[0].Value ?? "").Trim();
			string text2 = Convert.ToString(item.Cells[1].Value ?? "").Trim();
			if (text.Length != 0 || text2.Length != 0)
			{
				if (!double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var result) || result <= 0.0 || !double.TryParse(text2, NumberStyles.Float, CultureInfo.InvariantCulture, out var result2) || result2 < 0.0)
				{
					MessageBox.Show(this, "权重档位里有一行数字不对：月数必须大于 0，次数必须大于等于 0。", "WE 加权轮播", MessageBoxButtons.OK, MessageBoxIcon.Exclamation);
					SelectPage(1);
					return false;
				}
				list.Add(new Tier(result, result2));
			}
		}
		if (list.Count == 0)
		{
			MessageBox.Show(this, "至少要有一个档位。", "WE 加权轮播", MessageBoxButtons.OK, MessageBoxIcon.Exclamation);
			SelectPage(1);
			return false;
		}
		cfg.IntervalMinutes = (int)_interval.Value;
		cfg.OnlyWhenVisible = _onlyVisible.Checked;
		cfg.IdleMinutes = (int)_idle.Value;
		cfg.SwitchWhenWEStarts = _switchOnStart.Checked;
		cfg.PauseWhenFullscreen = _fullscreen.Checked;
		cfg.Mode = ((_mode.SelectedIndex == 2) ? "random" : ((_mode.SelectedIndex != 1) ? "grouped" : "cycle"));
		cfg.RebalanceMidCycle = _rebalance.Checked;
		cfg.AvoidRecentCount = (int)_avoidRecent.Value;
		cfg.Monitors = (_monAll.Checked ? "all" : ((_monText.Text.Trim().Length != 0) ? _monText.Text.Trim() : "0"));
		cfg.SameOnAllMonitors = _sameOnAll.Checked;
		cfg.WallpaperEnginePath = _exePath.Text.Trim();
		cfg.WorkshopPaths = ((_workshopPath.Text.Trim().Length != 0) ? new List<string> { _workshopPath.Text.Trim() } : new List<string>());
		cfg.ExcludeIds = (from s in _excludeIds.Text.Split(new char[4] { ',', ' ', ';', '，' }, StringSplitOptions.RemoveEmptyEntries)
			select s.Trim()).ToList();
		cfg.IncludeTypes = new List<string>();
		if (_tScene.Checked)
		{
			cfg.IncludeTypes.Add("scene");
		}
		if (_tVideo.Checked)
		{
			cfg.IncludeTypes.Add("video");
		}
		if (_tWeb.Checked)
		{
			cfg.IncludeTypes.Add("web");
		}
		cfg.Tiers = list.OrderBy((Tier t) => t.MaxMonths).ToList();
		cfg.OlderWeight = (double)_older.Value;
		try
		{
			cfg.Save(_engine.ConfigPath);
		}
		catch (Exception ex)
		{
			MessageBox.Show(this, "保存配置失败：" + ex.Message, "WE 加权轮播", MessageBoxButtons.OK, MessageBoxIcon.Hand);
			return false;
		}
		try
		{
			TrayApp.SetStartWithWindows(_startup.Checked);
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
		_engine.Log.Log("设置已保存" + ((!resetCycle) ? "" : "，并重新开一轮"));
		_app.OnConfigSaved();
		UpdateSideStatus();
		_status.Text = "已保存 " + DateTime.Now.ToString("HH:mm:ss");
		return true;
	}

	private void RefreshPreview()
	{
		if (_previewBusy)
		{
			_previewAgain = true;
			return;
		}
		_previewBusy = true;
		_summary.ForeColor = Theme.TextMuted;
		if (_list.Items.Count == 0)
		{
			_summary.Text = "正在扫描壁纸…";
		}
		ThreadPool.QueueUserWorkItem(delegate
		{
			Stopwatch stopwatch = Stopwatch.StartNew();
			Engine.Preview p;
			try
			{
				p = _engine.BuildPreview();
			}
			catch (Exception ex)
			{
				p = new Engine.Preview
				{
					Error = ex.Message
				};
			}
			long scanMs = stopwatch.ElapsedMilliseconds;
			try
			{
				if (!base.IsDisposed)
				{
					BeginInvoke((Action)delegate
					{
						_previewBusy = false;
						Stopwatch stopwatch2 = Stopwatch.StartNew();
						if (!base.IsDisposed)
						{
							ShowPreview(p);
						}
						_engine.Log.Log("预览：扫描 " + scanMs + " ms（读文件 " + _engine.LastCacheMiss + " 个），填表 " + stopwatch2.ElapsedMilliseconds + " ms，共 " + p.All.Count + " 张");
						if (_previewAgain)
						{
							_previewAgain = false;
							RefreshPreview();
						}
					});
				}
			}
			catch
			{
				_previewBusy = false;
			}
		});
	}

	private void ShowPreview(Engine.Preview p)
	{
		_list.BeginUpdate();
		_list.Items.Clear();
		if (p.Error != null)
		{
			_summary.Text = "无法预览：" + p.Error;
			_summary.ForeColor = Color.Firebrick;
			_list.EndUpdate();
			return;
		}
		_summary.ForeColor = Theme.TextMuted;
		Engine.QueueState queue = p.Queue;
		string[] value = (from w in p.All
			group w by w.Tier into g
			orderby g.Min((Wallpaper w) => w.AgeDays)
			select g.Key + " " + g.Count() + "张×" + Engine.Fmt(g.First().Weight)).ToArray();
		string text = "Wallpaper Engine：" + p.Paths.Exe + "\r\n创意工坊：" + string.Join("; ", p.Paths.Workshop.ToArray()) + "\r\n共 " + p.All.Count + " 张壁纸\u3000" + string.Join("\u3000", value) + "\r\n";
		if (_engine.Cfg.Mode == "random")
		{
			text += "纯加权随机模式：次数为相对概率。";
		}
		else
		{
			string text2 = text;
			text = text2 + "一轮 = " + p.CycleLength + " 次切换，按每 " + _engine.Cfg.IntervalMinutes + " 分钟、每天用电脑 8 小时估算约 " + p.EstimatedDays.ToString("0.0") + " 天。";
			if (queue != null)
			{
				text2 = text;
				text = text2 + "\u3000当前轮：始于 " + queue.Started.ToString("MM-dd HH:mm") + "，剩余 " + queue.Remaining + "/" + queue.Length + "。";
				if (queue.Groups != null && queue.CurrentGroup >= 0)
				{
					List<string> list = new List<string>();
					for (int i = 0; i < queue.Groups.Count; i++)
					{
						list.Add(_engine.GroupName(i) + " 剩 " + queue.Groups[i].Count);
					}
					text2 = text;
					text = text2 + "\r\n正在放第 " + (queue.CurrentGroup + 1) + " 组（" + _engine.GroupName(queue.CurrentGroup) + "）；各组：" + string.Join("，", list.ToArray());
				}
			}
			else
			{
				text += "\u3000还没开始第一轮。";
			}
		}
		_summary.Text = text;
		foreach (Wallpaper w2 in p.All)
		{
			string text3 = "";
			if (queue != null && queue.Members.Contains(w2.Id))
			{
				int num = (queue.Played.ContainsKey(w2.Id) ? queue.Played[w2.Id] : 0);
				int num2 = ((queue.Groups == null) ? queue.Queue.Count((string x) => x == w2.Id) : queue.Groups.Sum((List<string> g) => g.Count((string x) => x == w2.Id)));
				text3 = num + " / " + num2;
			}
			ListViewItem listViewItem = new ListViewItem(new string[8]
			{
				w2.Id,
				w2.Title,
				w2.Type,
				w2.Added.ToString("yyyy-MM-dd"),
				Engine.Fmt(w2.AgeDays),
				w2.Tier,
				Engine.Fmt(w2.Weight),
				text3
			});
			if (w2.Weight <= 0.0)
			{
				listViewItem.ForeColor = Theme.TextMuted;
			}
			_list.Items.Add(listViewItem);
		}
		_list.EndUpdate();
	}
}
