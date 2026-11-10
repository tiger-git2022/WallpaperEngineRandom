using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using Wpf.Ui.Appearance;

namespace WERandom;

/// <summary>
/// 主界面里的“创意工坊”页：一个 WebView2，显示内嵌的 workshop.html（时间线、浏览搜索、作者、详情）。
/// 页面通过 postMessage 调用这里的方法（见 Dispatch），Steam 请求由 <see cref="SteamWorkshop"/> 发出。
/// </summary>
public sealed class WorkshopView : Grid
{
	private const string Host = "https://werandom.local/";

	private static SteamWorkshop _steam;

	private static SteamWorkshop _sub;

	/// <summary>订阅账号的 WebView2 配置文件名（cookie 和浏览账号的默认配置文件分开存）。</summary>
	private const string SubProfile = "subscribe";

	/// <summary>看不见的 WebView2，用订阅账号的配置文件，只用来读写它的 cookie。</summary>
	private WebView2 _subWeb;

	private readonly Engine _engine;

	private readonly WebView2 _web;

	private readonly System.Windows.Controls.TextBlock _message;

	private bool _started;

	private bool _ready;

	private readonly Queue<string> _outbox = new Queue<string>();

	/// <summary>时间线里还没看过的数量（页面告诉我们的），给导航栏显示。</summary>
	public event Action<int> UnreadChanged;

	/// <summary>浏览账号：时间线、浏览、作者页、关注。</summary>
	public static SteamWorkshop Steam(Engine engine)
	{
		return _steam ??= new SteamWorkshop(engine.Dir, engine.Log);
	}

	/// <summary>订阅账号：只负责订阅（比如 Steam 客户端里用的、看不到部分内容的大号）；没登录时订阅用浏览账号。</summary>
	public static SteamWorkshop Sub(Engine engine)
	{
		return _sub ??= new SteamWorkshop(engine.Dir, engine.Log, "订阅账号");
	}

	private SteamWorkshop Subscriber => Sub(_engine).LoggedIn ? Sub(_engine) : Steam(_engine);

	private string StatePath => Path.Combine(_engine.Dir, "we_random.workshop.json");

	public WorkshopView(Engine engine)
	{
		_engine = engine;
		_web = new WebView2 { Visibility = Visibility.Hidden };
		_message = new System.Windows.Controls.TextBlock
		{
			Text = "正在加载创意工坊…",
			HorizontalAlignment = HorizontalAlignment.Center,
			VerticalAlignment = VerticalAlignment.Center,
			TextWrapping = TextWrapping.Wrap,
			MaxWidth = 460,
			TextAlignment = TextAlignment.Center
		};
		_message.SetResourceReference(System.Windows.Controls.TextBlock.ForegroundProperty, "TextFillColorSecondaryBrush");
		Children.Add(_message);
		Children.Add(_web);
		ApplicationThemeManager.Changed += OnThemeChanged;
		Unloaded += delegate { ApplicationThemeManager.Changed -= OnThemeChanged; };
	}

	/// <summary>第一次切到这个页面时才初始化 WebView2（避免每次打开主界面都启动浏览器进程）。</summary>
	public async void Start()
	{
		if (_started)
		{
			return;
		}
		_started = true;
		try
		{
			SteamWorkshop steam = Steam(_engine);
			CoreWebView2Environment env = await steam.GetEnvironmentAsync();
			await _web.EnsureCoreWebView2Async(env);
			CoreWebView2 core = _web.CoreWebView2;
			core.Settings.IsStatusBarEnabled = false;
			core.Settings.AreDefaultContextMenusEnabled = true;
			core.Settings.IsZoomControlEnabled = false;
			// transparent, so the window's Mica / Acrylic background shows through like on the other pages
			_web.DefaultBackgroundColor = System.Drawing.Color.Transparent;
			core.AddWebResourceRequestedFilter(Host + "*", CoreWebView2WebResourceContext.All);
			core.WebResourceRequested += OnResource;
			core.WebMessageReceived += OnMessage;
			core.NewWindowRequested += (s, e) =>
			{
				e.Handled = true;
				OpenExternal(e.Uri);
			};
			core.NavigationStarting += (s, e) =>
			{
				// 页面里点到的外部链接用默认浏览器打开，WebView 只显示自己的页面
				if (!e.Uri.StartsWith(Host, StringComparison.OrdinalIgnoreCase))
				{
					e.Cancel = true;
					OpenExternal(e.Uri);
				}
			};
			core.NavigationCompleted += (s, e) =>
			{
				_web.Visibility = Visibility.Visible;
				_message.Visibility = Visibility.Collapsed;
			};
			steam.AuthChanged += OnAuthChanged;
			Unloaded += delegate { steam.AuthChanged -= OnAuthChanged; };
			await steam.SyncCookiesAsync(core);
			await StartSubAsync(env);
			core.Navigate(Host + "workshop.html?theme=" + (Dark() ? "dark" : "light") + "&accent=" + Uri.EscapeDataString(AccentHex()));
		}
		catch (Exception ex)
		{
			_engine.Log.Log("创意工坊页面初始化失败：" + ex);
			_message.Text = "创意工坊页面打不开：" + ex.Message + "\n\n需要 Microsoft Edge WebView2 运行时（Windows 11 自带；Windows 10 可到微软官网下载安装）。";
		}
	}

	/// <summary>订阅账号的看不见的 WebView2；失败时不影响页面，只是不能用订阅账号。</summary>
	private async Task StartSubAsync(CoreWebView2Environment env)
	{
		try
		{
			_subWeb = new WebView2 { Visibility = Visibility.Hidden, Width = 1, Height = 1, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };
			Children.Add(_subWeb);
			await _subWeb.EnsureCoreWebView2Async(env, SubOptions(env));
			SteamWorkshop sub = Sub(_engine);
			sub.AuthChanged += OnAuthChanged;
			Unloaded += delegate { sub.AuthChanged -= OnAuthChanged; };
			await sub.SyncCookiesAsync(_subWeb.CoreWebView2);
		}
		catch (Exception ex)
		{
			_engine.Log.Log("创意工坊：订阅账号初始化失败：" + ex.Message);
			_subWeb = null;
		}
	}

	private static CoreWebView2ControllerOptions SubOptions(CoreWebView2Environment env)
	{
		CoreWebView2ControllerOptions o = env.CreateCoreWebView2ControllerOptions();
		o.ProfileName = SubProfile;
		return o;
	}

	private CoreWebView2 SubCore => _subWeb?.CoreWebView2 ?? throw new SteamException(0, "订阅账号用不了（WebView2 初始化失败，详见日志）");

	private static bool Dark()
	{
		return ApplicationThemeManager.GetAppTheme() == ApplicationTheme.Dark;
	}

	private static string AccentHex()
	{
		try
		{
			System.Windows.Media.Color c = ApplicationAccentColorManager.PrimaryAccent;
			return "#" + c.R.ToString("x2") + c.G.ToString("x2") + c.B.ToString("x2");
		}
		catch
		{
			return "#60cdff";
		}
	}

	private void OnThemeChanged(ApplicationTheme theme, System.Windows.Media.Color accent)
	{
		Dispatcher.BeginInvoke((Action)delegate
		{
			Post("{\"event\":\"theme\",\"theme\":\"" + (Dark() ? "dark" : "light") + "\",\"accent\":\"" + AccentHex() + "\"}");
		});
	}

	private void OnAuthChanged()
	{
		Dispatcher.BeginInvoke((Action)delegate { Post("{\"event\":\"auth\"}"); });
	}

	/// <summary>在工坊页里打开某个壁纸的详情（概览页的“创意工坊页面”按钮用）。</summary>
	public void ShowItem(string id)
	{
		if (Regex.IsMatch(id ?? "", "^\\d+$"))
		{
			Post("{\"event\":\"showItem\",\"id\":\"" + id + "\"}");
		}
	}

	private void Post(string json)
	{
		if (!_ready || _web.CoreWebView2 == null)
		{
			_outbox.Enqueue(json);
			return;
		}
		try
		{
			_web.CoreWebView2.PostWebMessageAsJson(json);
		}
		catch
		{
		}
	}

	private void OnResource(object sender, CoreWebView2WebResourceRequestedEventArgs e)
	{
		string path = new Uri(e.Request.Uri).AbsolutePath.TrimStart('/');
		string name = path == "workshop.html" ? "WERandom.workshop.html" : null;
		Stream s = name == null ? null : typeof(WorkshopView).Assembly.GetManifestResourceStream(name);
		CoreWebView2Environment env = _web.CoreWebView2.Environment;
		e.Response = s == null
			? env.CreateWebResourceResponse(null, 404, "Not Found", "")
			: env.CreateWebResourceResponse(s, 200, "OK", "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store");
	}

	// ---- bridge ----

	private async void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
	{
		JObj msg;
		try
		{
			msg = Json.ParseObj(e.WebMessageAsJson);
		}
		catch
		{
			return;
		}
		if (msg.Str("method") == "log")
		{
			string text = (msg.Obj("args")?.Str("message") ?? "");
			_engine.Log.Log("创意工坊页面：" + (text.Length > 500 ? text.Substring(0, 500) : text));
			return;
		}
		if (msg.Str("method") == "ready")
		{
			_ready = true;
			while (_outbox.Count > 0)
			{
				Post(_outbox.Dequeue());
			}
			return;
		}
		string id = msg.Str("id");
		string reply;
		try
		{
			string result = await Dispatch(msg.Str("method"), msg.Obj("args") ?? new JObj());
			reply = "{\"id\":" + Json.Write(id) + ",\"ok\":true,\"result\":" + (result ?? "null") + "}";
		}
		catch (Exception ex)
		{
			if (!(ex is SteamException))
			{
				_engine.Log.Log("创意工坊 " + msg.Str("method") + " 出错：" + ex.Message);
			}
			int status = ex is SteamException se ? se.Status : 0;
			reply = "{\"id\":" + Json.Write(id) + ",\"ok\":false,\"status\":" + status + ",\"error\":" + Json.Write(ex.Message) + "}";
		}
		try
		{
			_web.CoreWebView2?.PostWebMessageAsJson(reply);
		}
		catch
		{
		}
	}

	/// <summary>页面能调用的方法。返回 JSON 文本。</summary>
	private async Task<string> Dispatch(string method, JObj args)
	{
		SteamWorkshop steam = Steam(_engine);
		CoreWebView2 core = _web.CoreWebView2;
		switch (method)
		{
		case "status":
			await steam.SyncCookiesAsync(core);
			if (_subWeb?.CoreWebView2 != null)
			{
				await Sub(_engine).SyncCookiesAsync(_subWeb.CoreWebView2);
			}
			return await StatusJson(steam);
		case "login":
			await ShowLoginAsync(steam, false);
			await steam.SyncCookiesAsync(core);
			return await StatusJson(steam);
		case "logout":
			await steam.LogoutAsync(core);
			return await StatusJson(steam);
		case "subLogin":
		{
			CoreWebView2 subCore = SubCore;
			await ShowLoginAsync(Sub(_engine), true);
			await Sub(_engine).SyncCookiesAsync(subCore);
			return await StatusJson(steam);
		}
		case "subLogout":
			await Sub(_engine).LogoutAsync(SubCore);
			return await StatusJson(steam);
		case "subs":
		{
			// 订阅用的账号（订阅账号，没登录时是浏览账号）在 Steam 上的订阅列表的一页
			SteamWorkshop who = Subscriber;
			SteamWorkshop.SubsPage p = await who.SubscriptionsAsync(Math.Max(1, args.Int("page", 1)));
			var o = new JObj();
			o["owner"] = who.SteamId;
			o["total"] = p.Total;
			o["ids"] = p.Ids;
			return Json.Write(o, pretty: false);
		}
		case "subCheck":
		{
			SteamWorkshop who = Subscriber;
			bool? on = await who.SubscribedOnPageAsync(args.Str("id"));
			var o = new JObj();
			o["owner"] = who.SteamId;
			o["on"] = on.HasValue ? (object)on.Value : null;
			return Json.Write(o, pretty: false);
		}
		case "subsLog":
			_engine.Log.Log("创意工坊：" + args.Str("message"));
			return "true";
		case "subFollowing":
			return Json.Write(await Sub(_engine).FollowingAsync(), pretty: false);
		case "query":
			return await steam.QueryAsync(args.Obj("input") ?? new JObj());
		case "details":
		{
			List<string> ids = args.StrList("ids").Where(x => Regex.IsMatch(x, "^\\d+$")).Take(100).ToList();
			return ids.Count == 0 ? "{\"response\":{\"publishedfiledetails\":[]}}" : await steam.DetailsAsync(ids);
		}
		case "creator":
		{
			string sid = args.Str("steamid");
			int page = Math.Max(1, args.Int("page", 1));
			SteamWorkshop.CreatorPage p = await steam.CreatorFilesAsync(sid, page);
			string details = p.Ids.Count == 0 ? "null" : await steam.DetailsAsync(p.Ids);
			var o = new JObj();
			o["total"] = p.Total;
			o["page"] = page;
			o["following"] = p.Following;
			o["hasFollow"] = p.HasFollowButton;
			o["ids"] = p.Ids;
			string head = Json.Write(o, pretty: false);
			return head.Substring(0, head.Length - 1) + ",\"details\":" + details + "}";
		}
		case "comments":
			return await steam.CommentsAsync(args.Str("owner"), args.Str("id"), args.Int("start"), args.Int("count", 20));
		case "following":
			return Json.Write(await steam.FollowingAsync(), pretty: false);
		case "profiles":
		{
			List<string> ids = args.StrList("ids").Where(x => Regex.IsMatch(x, "^\\d{17}$")).Distinct().Take(200).ToList();
			SteamWorkshop.Profile[] list = await Task.WhenAll(ids.Select(steam.ProfileAsync));
			return Json.Write(list.Select(p =>
			{
				var o = new JObj();
				o["steamid"] = p.SteamId;
				o["name"] = p.Name;
				o["avatar"] = p.Avatar;
				return o;
			}).ToList(), pretty: false);
		}
		case "subscribe":
		{
			// 订阅账号登录了就用它订阅（Steam 客户端下载的是它的订阅），没登录时用浏览账号
			SteamWorkshop who = Subscriber;
			string what = (args.Bool("on") ? "订阅 " : "取消订阅 ") + args.Str("id") + "（" + who.Label + " " + who.SteamId + "）";
			try
			{
				await who.SubscribeAsync(args.Str("id"), args.Bool("on"));
			}
			catch (Exception ex)
			{
				_engine.Log.Log("创意工坊：" + what + " 失败：" + ex.Message);
				throw;
			}
			_engine.Log.Log("创意工坊：" + what);
			return "true";
		}
		case "follow":
		{
			bool ok = await steam.FollowAsync(args.Str("steamid"), args.Bool("on"));
			_engine.Log.Log("创意工坊：" + (args.Bool("on") ? "关注 " : "取消关注 ") + args.Str("steamid") + (ok ? "" : "（Steam 未确认）"));
			return ok ? "true" : "false";
		}
		case "installed":
			return Json.Write(await Task.Run(InstalledIds), pretty: false);
		case "loadState":
			return File.Exists(StatePath) ? Json.Write(File.ReadAllText(StatePath, Encoding.UTF8)) : "null";
		case "saveState":
		{
			string text = args.Str("state");
			string tmp = StatePath + ".tmp";
			File.WriteAllText(tmp, text, new UTF8Encoding(false));
			if (File.Exists(StatePath))
			{
				File.Replace(tmp, StatePath, null);
			}
			else
			{
				File.Move(tmp, StatePath);
			}
			return "true";
		}
		case "unread":
			UnreadChanged?.Invoke(args.Int("n"));
			return "true";
		case "open":
			OpenExternal(args.Str("url"));
			return "true";
		default:
			throw new InvalidOperationException("未知方法 " + method);
		}
	}

	private async Task<string> StatusJson(SteamWorkshop steam)
	{
		var o = new JObj();
		o["loggedIn"] = steam.LoggedIn;
		o["steamid"] = steam.SteamId;
		if (steam.LoggedIn)
		{
			SteamWorkshop.Profile p = await steam.ProfileAsync(steam.SteamId);
			o["name"] = p.Name;
			o["avatar"] = p.Avatar;
		}
		SteamWorkshop sub = Sub(_engine);
		var so = new JObj();
		so["available"] = _subWeb?.CoreWebView2 != null;
		so["loggedIn"] = sub.LoggedIn;
		so["steamid"] = sub.SteamId;
		if (sub.LoggedIn)
		{
			SteamWorkshop.Profile sp = await steam.ProfileAsync(sub.SteamId);
			so["name"] = sp.Name;
			so["avatar"] = sp.Avatar;
		}
		o["sub"] = so;
		ClientAccount(out string clientId, out string clientName);
		o["clientSteamid"] = clientId;
		o["clientName"] = clientName;
		return Json.Write(o, pretty: false);
	}

	/// <summary>Steam 客户端最近登录的账号（loginusers.vdf 里 MostRecent 的那个），用来提醒“网页登录的不是同一个账号”。</summary>
	private static void ClientAccount(out string steamId, out string name)
	{
		steamId = "";
		name = "";
		try
		{
			string steam = Microsoft.Win32.Registry.GetValue("HKEY_CURRENT_USER\\Software\\Valve\\Steam", "SteamPath", null) as string;
			if (string.IsNullOrEmpty(steam))
			{
				return;
			}
			string vdf = File.ReadAllText(Path.Combine(steam.Replace('/', '\\'), "config", "loginusers.vdf"), Encoding.UTF8);
			MatchCollection users = Regex.Matches(vdf, "\"(\\d{17})\"\\s*\\{([^}]*)\\}");
			Match pick = null;
			foreach (Match u in users)
			{
				if (pick == null || Regex.IsMatch(u.Groups[2].Value, "\"MostRecent\"\\s*\"1\""))
				{
					pick = u;
				}
			}
			if (pick != null)
			{
				steamId = pick.Groups[1].Value;
				Match n = Regex.Match(pick.Groups[2].Value, "\"PersonaName\"\\s*\"([^\"]*)\"");
				name = n.Success ? n.Groups[1].Value : "";
			}
		}
		catch
		{
		}
	}

	/// <summary>本机创意工坊目录里已经下载的壁纸 ID（= 已订阅并下载完成）。</summary>
	private List<string> InstalledIds()
	{
		var ids = new List<string>();
		try
		{
			foreach (string dir in _engine.ResolvePaths().Workshop)
			{
				if (Directory.Exists(dir))
				{
					ids.AddRange(Directory.GetDirectories(dir).Select(Path.GetFileName).Where(n => Regex.IsMatch(n, "^\\d+$")));
				}
			}
		}
		catch
		{
		}
		return ids.Distinct().ToList();
	}

	private static void OpenExternal(string url)
	{
		if (string.IsNullOrEmpty(url) || !(url.StartsWith("https://", StringComparison.OrdinalIgnoreCase) || url.StartsWith("steam://", StringComparison.OrdinalIgnoreCase)))
		{
			return;
		}
		try
		{
			Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
		}
		catch
		{
		}
	}

	// ---- login window ----

	/// <summary>
	/// 弹出一个窗口显示 Steam 官方登录页，用户自己登录（账号密码、手机令牌、扫码都在 Steam 页面里完成），登录成功后自动关闭。
	/// sub：登录订阅账号（WebView2 用订阅账号的配置文件）。
	/// </summary>
	private async Task ShowLoginAsync(SteamWorkshop steam, bool sub)
	{
		// we are inside the page's WebMessageReceived callback; WebView2 must not create another WebView or run a
		// nested modal loop from there (that aborts with E_ABORT), so leave the callback and use a modeless window
		await Task.Yield();
		CoreWebView2Environment env = await steam.GetEnvironmentAsync();
		var web = new WebView2();
		var win = new Window
		{
			Title = sub ? "登录订阅账号（请登录 Steam 客户端里用的账号）" : "登录浏览账号（看时间线、浏览、关注用的账号）",
			Width = 560,
			Height = 760,
			WindowStartupLocation = WindowStartupLocation.CenterOwner,
			Owner = Window.GetWindow(this),
			Content = web
		};
		bool done = false;
		var closed = new TaskCompletionSource<bool>();
		win.Closed += delegate { closed.TrySetResult(true); };
		// dark title bar in the dark theme, like the main window
		win.SourceInitialized += delegate
		{
			try
			{
				if (Dark())
				{
					WindowBackgroundManager.ApplyDarkThemeToWindow(win);
				}
			}
			catch
			{
			}
		};
		win.Loaded += async delegate
		{
			try
			{
				if (sub)
				{
					await web.EnsureCoreWebView2Async(env, SubOptions(env));
				}
				else
				{
					await web.EnsureCoreWebView2Async(env);
				}
				web.CoreWebView2.NewWindowRequested += (s, e) =>
				{
					e.Handled = true;
					OpenExternal(e.Uri);
				};
				web.CoreWebView2.NavigationCompleted += async (s, e) =>
				{
					string url = web.CoreWebView2.Source ?? "";
					if (done || url.IndexOf("/login", StringComparison.OrdinalIgnoreCase) >= 0)
					{
						return;
					}
					await steam.SyncCookiesAsync(web.CoreWebView2);
					if (steam.LoggedIn)
					{
						done = true;
						win.Close();
					}
				};
				web.CoreWebView2.Navigate(SteamWorkshop.Community + "/login/home/?goto=" + Uri.EscapeDataString("my/"));
			}
			catch (Exception ex)
			{
				_engine.Log.Log("Steam 登录窗口出错：" + ex.Message);
				win.Close();
			}
		};
		win.Show();
		await closed.Task;
		web.Dispose();
	}
}
