using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;

namespace WERandom;

/// <summary>
/// 访问 Steam 创意工坊：用户在内嵌的 WebView2 里登录一次 Steam，之后用它的 cookie 调接口。
/// - 时间线、搜索：IPublishedFileService/QueryFiles（登录后取的 webapi_token，参数用 input_json）
/// - 作者全部作品：作者的 myworkshopfiles 页面（带登录 cookie 才能看到成人分级的作品）拿 ID，再用公开的 GetPublishedFileDetails 补详情
///   （GetUserFiles 只认开发者 key，用登录令牌调返回 401）
/// - 评论：公开的 comment/PublishedFile_Public/render（返回评论的 HTML，由页面解析）
/// - 订阅 / 关注：Steam 网页上那两个按钮用的接口（sessionid 表单）
/// - 作者名字和头像：公开的个人资料 ?xml=1
/// </summary>
public sealed class SteamWorkshop
{
	public const string AppId = "431960";

	public const string Community = "https://steamcommunity.com";

	private const string Api = "https://api.steampowered.com";

	private readonly string _dataDir;

	private readonly Logger _log;

	private readonly HttpClient _http;

	private CoreWebView2Environment _env;

	private static Task<CoreWebView2Environment> _envTask;

	/// <summary>日志里用的名字：「浏览账号」或「订阅账号」。</summary>
	public string Label { get; }

	/// <summary>steamcommunity.com 的 cookie（名字 → 值），从 WebView2 复制过来。</summary>
	private Dictionary<string, string> _cookies = new Dictionary<string, string>();

	private string _token;

	private DateTime _tokenTime;

	private readonly Dictionary<string, Profile> _profiles = new Dictionary<string, Profile>();

	private readonly SemaphoreSlim _profileGate = new SemaphoreSlim(4);

	public string SteamId { get; private set; } = "";

	public bool LoggedIn => SteamId.Length > 0;

	public event Action AuthChanged;

	public sealed class Profile
	{
		public string SteamId;

		public string Name = "";

		public string Avatar = "";
	}

	public SteamWorkshop(string dataDir, Logger log, string label = "浏览账号")
	{
		_dataDir = dataDir;
		_log = log;
		Label = label;
		var handler = new HttpClientHandler { UseCookies = false, AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate };
		_http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };
		_http.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
		_http.DefaultRequestHeaders.AcceptLanguage.ParseAdd("zh-CN,zh;q=0.9,en;q=0.6");
		try
		{
			ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
		}
		catch
		{
		}
	}

	/// <summary>
	/// 所有 WebView2 共用一个环境，cookie（登录状态）存在程序文件夹的 webview2 子文件夹里。
	/// 浏览账号用默认配置文件，订阅账号用另一个配置文件（见 WorkshopView），两个号的登录互不覆盖。
	/// </summary>
	public Task<CoreWebView2Environment> GetEnvironmentAsync()
	{
		if (_envTask == null)
		{
			_envTask = CoreWebView2Environment.CreateAsync(null, Path.Combine(_dataDir, "webview2"));
		}
		return _envTask;
	}

	// ---- login state ----

	/// <summary>从 WebView2 复制 Steam 的 cookie，并据此判断登录的是谁。在 UI 线程上调用。</summary>
	public async Task SyncCookiesAsync(CoreWebView2 core)
	{
		var map = new Dictionary<string, string>();
		try
		{
			foreach (CoreWebView2Cookie c in await core.CookieManager.GetCookiesAsync(Community))
			{
				map[c.Name] = c.Value;
			}
		}
		catch (Exception ex)
		{
			_log.Log("读取 Steam cookie 失败：" + ex.Message);
		}
		string old = SteamId;
		string id = "";
		if (map.TryGetValue("steamLoginSecure", out string sls))
		{
			string decoded = Uri.UnescapeDataString(sls);
			int bar = decoded.IndexOf('|');
			if (bar > 0 && Regex.IsMatch(decoded.Substring(0, bar), "^\\d{17}$"))
			{
				id = decoded.Substring(0, bar);
			}
		}
		// sessionid 只是防跨站的随机值，表单里和 cookie 里一样即可；还没有时自己生成一个
		if (id.Length > 0 && !map.ContainsKey("sessionid"))
		{
			string sid = RandomHex(24);
			map["sessionid"] = sid;
			try
			{
				core.CookieManager.AddOrUpdateCookie(core.CookieManager.CreateCookie("sessionid", sid, "steamcommunity.com", "/"));
			}
			catch
			{
			}
		}
		_cookies = map;
		if (id != old)
		{
			SteamId = id;
			_token = null;
			_log.Log("创意工坊：" + Label + (id.Length > 0 ? " 已登录 Steam " + id : " 未登录 Steam"));
			AuthChanged?.Invoke();
		}
	}

	public async Task LogoutAsync(CoreWebView2 core)
	{
		try
		{
			core.CookieManager.DeleteAllCookies();
		}
		catch
		{
		}
		await SyncCookiesAsync(core);
	}

	private static string RandomHex(int n)
	{
		var bytes = new byte[n / 2];
		using (var rng = System.Security.Cryptography.RandomNumberGenerator.Create())
		{
			rng.GetBytes(bytes);
		}
		return BitConverter.ToString(bytes).Replace("-", "").ToLowerInvariant();
	}

	private string CookieHeader()
	{
		return string.Join("; ", _cookies.Select(kv => kv.Key + "=" + kv.Value));
	}

	private string SessionId => _cookies.TryGetValue("sessionid", out string s) ? s : "";

	// ---- http ----

	private async Task<string> GetCommunityAsync(string url, bool withCookies = true)
	{
		using var req = new HttpRequestMessage(HttpMethod.Get, url);
		if (withCookies && _cookies.Count > 0)
		{
			req.Headers.Add("Cookie", CookieHeader());
		}
		using HttpResponseMessage resp = await _http.SendAsync(req);
		string text = await resp.Content.ReadAsStringAsync();
		if (!resp.IsSuccessStatusCode)
		{
			throw new SteamException((int)resp.StatusCode, "Steam 返回 " + (int)resp.StatusCode);
		}
		return text;
	}

	private async Task<string> PostCommunityAsync(string url, Dictionary<string, string> form)
	{
		RequireLogin();
		using var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = new FormUrlEncodedContent(form) };
		req.Headers.Add("Cookie", CookieHeader());
		req.Headers.Referrer = new Uri(Community + "/");
		req.Headers.Add("Origin", Community);
		using HttpResponseMessage resp = await _http.SendAsync(req);
		string text = await resp.Content.ReadAsStringAsync();
		if (!resp.IsSuccessStatusCode)
		{
			throw new SteamException((int)resp.StatusCode, "Steam 返回 " + (int)resp.StatusCode);
		}
		return text;
	}

	private void RequireLogin()
	{
		if (!LoggedIn)
		{
			throw new SteamException(401, "需要先登录 Steam");
		}
	}

	/// <summary>网页接口用的 webapi_token（登录后由社区页面发放，有效期约一天；这里每小时重取一次）。</summary>
	private async Task<string> TokenAsync(bool refresh = false)
	{
		RequireLogin();
		if (!refresh && _token != null && (DateTime.UtcNow - _tokenTime).TotalMinutes < 60)
		{
			return _token;
		}
		string text = await GetCommunityAsync(Community + "/pointssummary/ajaxgetasyncconfig");
		string token = Json.ParseObj(text).Obj("data")?.Str("webapi_token") ?? "";
		if (token.Length == 0)
		{
			throw new SteamException(401, "Steam 登录已失效，请重新登录");
		}
		_token = token;
		_tokenTime = DateTime.UtcNow;
		return token;
	}

	// ---- workshop queries ----

	/// <summary>
	/// IPublishedFileService/QueryFiles。input 是 JSON 对象的文本（query_type、cursor、search_text、taggroups、excludedtags…），
	/// appid 和需要返回的字段在这里补上。返回 Steam 原始的 JSON 文本。
	/// </summary>
	public async Task<string> QueryAsync(JObj input)
	{
		input["appid"] = 431960;
		input["return_tags"] = true;
		input["return_previews"] = true;
		input["return_vote_data"] = true;
		input["return_short_description"] = true;
		if (!input.Has("numperpage"))
		{
			input["numperpage"] = 30;
		}
		if (!input.Has("cursor"))
		{
			input["cursor"] = "*";
		}
		for (int attempt = 0; ; attempt++)
		{
			string token = await TokenAsync(attempt > 0);
			string url = Api + "/IPublishedFileService/QueryFiles/v1/?access_token=" + Uri.EscapeDataString(token) + "&input_json=" + Uri.EscapeDataString(Json.Write(input, pretty: false));
			using HttpResponseMessage resp = await _http.GetAsync(url);
			string text = await resp.Content.ReadAsStringAsync();
			if (resp.StatusCode == HttpStatusCode.Unauthorized && attempt == 0)
			{
				continue;
			}
			if (!resp.IsSuccessStatusCode)
			{
				throw new SteamException((int)resp.StatusCode, "Steam 接口返回 " + (int)resp.StatusCode);
			}
			return text;
		}
	}

	/// <summary>公开的 ISteamRemoteStorage/GetPublishedFileDetails（不需要登录），一次最多 100 个。返回原始 JSON。</summary>
	public async Task<string> DetailsAsync(IList<string> ids)
	{
		var form = new Dictionary<string, string> { ["itemcount"] = ids.Count.ToString() };
		for (int i = 0; i < ids.Count; i++)
		{
			form["publishedfileids[" + i + "]"] = ids[i];
		}
		using HttpResponseMessage resp = await _http.PostAsync(Api + "/ISteamRemoteStorage/GetPublishedFileDetails/v1/", new FormUrlEncodedContent(form));
		string text = await resp.Content.ReadAsStringAsync();
		if (!resp.IsSuccessStatusCode)
		{
			throw new SteamException((int)resp.StatusCode, "Steam 接口返回 " + (int)resp.StatusCode);
		}
		return text;
	}

	/// <summary>
	/// 壁纸下面的评论（公开接口，不需要登录），从新到旧，从第 start 条起最多 count 条。
	/// 返回 Steam 原始 JSON（success、total_count、comments_html），评论的 HTML 由页面解析。
	/// </summary>
	public async Task<string> CommentsAsync(string owner, string id, int start, int count)
	{
		CheckSteamId(owner);
		if (!Regex.IsMatch(id ?? "", "^\\d+$"))
		{
			throw new ArgumentException("壁纸 ID 不对");
		}
		var form = new Dictionary<string, string> { ["start"] = Math.Max(0, start).ToString(), ["count"] = Math.Max(1, Math.Min(50, count)).ToString() };
		using HttpResponseMessage resp = await _http.PostAsync(Community + "/comment/PublishedFile_Public/render/" + owner + "/" + id + "/", new FormUrlEncodedContent(form));
		string text = await resp.Content.ReadAsStringAsync();
		if (!resp.IsSuccessStatusCode)
		{
			throw new SteamException((int)resp.StatusCode, "Steam 返回 " + (int)resp.StatusCode);
		}
		return text;
	}

	public sealed class CreatorPage
	{
		public List<string> Ids = new List<string>();

		public int Total;

		public bool Following;

		public bool HasFollowButton;
	}

	/// <summary>作者上传的 WE 壁纸，按发布时间从新到旧，每页 30 个（page 从 1 开始）。</summary>
	public async Task<CreatorPage> CreatorFilesAsync(string steamId, int page)
	{
		CheckSteamId(steamId);
		string html = await GetCommunityAsync(Community + "/profiles/" + steamId + "/myworkshopfiles/?appid=" + AppId + "&browsefilter=myfiles&sortmethod=creationorder&numperpage=30&p=" + Math.Max(1, page));
		var result = new CreatorPage();
		foreach (Match m in Regex.Matches(html, "data-publishedfileid=\"(\\d+)\""))
		{
			if (!result.Ids.Contains(m.Groups[1].Value))
			{
				result.Ids.Add(m.Groups[1].Value);
			}
		}
		// “正在显示第 1 - 30 项，共 133 项条目” / “Showing 1-30 of 133 entries”
		Match paging = Regex.Match(html, "workshopBrowsePagingInfo\">([^<]*)<");
		if (paging.Success)
		{
			MatchCollection nums = Regex.Matches(paging.Groups[1].Value.Replace(",", ""), "\\d+");
			if (nums.Count > 0)
			{
				int.TryParse(nums[nums.Count - 1].Value, out result.Total);
			}
		}
		Match btn = Regex.Match(html, "id=\"FollowUserBtn\"[^>]*class=\"([^\"]*)\"|class=\"([^\"]*)\"[^>]*id=\"FollowUserBtn\"");
		if (btn.Success)
		{
			result.HasFollowButton = true;
			string cls = btn.Groups[1].Success && btn.Groups[1].Value.Length > 0 ? btn.Groups[1].Value : btn.Groups[2].Value;
			result.Following = cls.Contains("btn_active");
		}
		return result;
	}

	/// <summary>我在 Steam 上关注的玩家（创意工坊作者）。</summary>
	public async Task<List<string>> FollowingAsync()
	{
		RequireLogin();
		string html = await GetCommunityAsync(Community + "/profiles/" + SteamId + "/following/");
		var ids = new List<string>();
		foreach (Match m in Regex.Matches(html, "data-steamid=\"(\\d{17})\""))
		{
			string id = m.Groups[1].Value;
			if (id != SteamId && !ids.Contains(id))
			{
				ids.Add(id);
			}
		}
		if (ids.Count == 0)
		{
			foreach (Match m in Regex.Matches(html, "data-miniprofile=\"(\\d+)\""))
			{
				string id = (76561197960265728L + long.Parse(m.Groups[1].Value)).ToString();
				if (id != SteamId && !ids.Contains(id))
				{
					ids.Add(id);
				}
			}
		}
		return ids;
	}

	public sealed class SubsPage
	{
		public List<string> Ids = new List<string>();

		public int Total;
	}

	private static readonly Regex[] SubIdPatterns =
	{
		new Regex("id=\"Subscription(\\d+)\""),
		new Regex("UnsubscribeItem\\(\\s*'(\\d+)'"),
		new Regex("data-publishedfileid=\"(\\d+)\"")
	};

	/// <summary>
	/// 这个账号订阅的 WE 壁纸：自己的「已订阅的物品」网页，每页 30 个，page 从 1 开始。网页会按账号的内容设置
	/// 藏起一些看不到的作品，所以可能比实际少。网页不是以这个账号登录打开的（登录过期）时抛出异常。
	/// </summary>
	public async Task<SubsPage> SubscriptionsAsync(int page)
	{
		RequireLogin();
		string me = SteamId;
		string html = await GetCommunityAsync(Community + "/profiles/" + me + "/myworkshopfiles/?appid=" + AppId + "&browsefilter=mysubscriptions&numperpage=30&p=" + Math.Max(1, page));
		if (PageAccount(html) != me)
		{
			throw new SteamException(401, "登录过期了，请重新登录");
		}
		var result = new SubsPage();
		foreach (Regex re in SubIdPatterns)
		{
			foreach (Match m in re.Matches(html))
			{
				if (!result.Ids.Contains(m.Groups[1].Value))
				{
					result.Ids.Add(m.Groups[1].Value);
				}
			}
		}
		if (result.Ids.Count == 0)
		{
			foreach (Match m in Regex.Matches(html, "sharedfiles/filedetails/\\?id=(\\d+)"))
			{
				if (!result.Ids.Contains(m.Groups[1].Value))
				{
					result.Ids.Add(m.Groups[1].Value);
				}
			}
		}
		Match paging = Regex.Match(html, "workshopBrowsePagingInfo\">([^<]*)<");
		if (paging.Success)
		{
			MatchCollection nums = Regex.Matches(paging.Groups[1].Value.Replace(",", ""), "\\d+");
			if (nums.Count > 0)
			{
				int.TryParse(nums[nums.Count - 1].Value, out result.Total);
			}
		}
		return result;
	}

	/// <summary>
	/// 打开壁纸的网页看这个账号订阅了没有（「订阅」按钮带 toggled）。网页不是以这个账号登录的、或者看不到按钮
	/// （比如这个号看不到的作品）时返回 null。
	/// </summary>
	public async Task<bool?> SubscribedOnPageAsync(string id)
	{
		if (!LoggedIn || !Regex.IsMatch(id ?? "", "^\\d{1,20}$"))
		{
			return null;
		}
		string html = await GetCommunityAsync(Community + "/sharedfiles/filedetails/?id=" + id);
		if (PageAccount(html) != SteamId)
		{
			return null;
		}
		Match btn = Regex.Match(html, "<a[^>]*id=\"SubscribeItemBtn\"[^>]*>");
		if (!btn.Success)
		{
			return null;
		}
		return btn.Value.Contains("toggled") || Regex.IsMatch(html, "class=\"subscribeOption subscribed[^\"]*selected");
	}

	/// <summary>网页是以哪个账号登录打开的（g_steamID），没登录时为空。</summary>
	private static string PageAccount(string html)
	{
		Match m = Regex.Match(html, "g_steamID = \"(\\d{17})\"");
		return m.Success ? m.Groups[1].Value : "";
	}

	/// <summary>订阅 / 取消订阅；Steam 没确认时抛出 SteamException，消息是中文的原因（Status 是 Steam 的结果码）。</summary>
	public async Task SubscribeAsync(string id, bool on)
	{
		if (!Regex.IsMatch(id ?? "", "^\\d{1,20}$"))
		{
			throw new SteamException(400, "壁纸 ID 不对");
		}
		string text;
		try
		{
			text = await PostCommunityAsync(Community + "/sharedfiles/" + (on ? "subscribe" : "unsubscribe"), new Dictionary<string, string>
			{
				["id"] = id,
				["appid"] = AppId,
				["sessionid"] = SessionId
			});
		}
		catch (SteamException ex) when (ex.Status == 401 || ex.Status == 403)
		{
			throw new SteamException(ex.Status, "登录过期了（Steam 返回 " + ex.Status + "），请重新登录");
		}
		if (SuccessOf(text))
		{
			return;
		}
		object code = null;
		try
		{
			code = Json.ParseObj(text)["success"];
		}
		catch
		{
		}
		string brief = Regex.Replace(text.Length > 200 ? text.Substring(0, 200) : text, "\\s+", " ");
		_log.Log("创意工坊：" + Label + (on ? "订阅 " : "取消订阅 ") + id + " 没成功：" + brief);
		throw new SteamException(code is double d ? (int)d : 0, ResultText(code, text));
	}

	/// <summary>Steam 的 EResult 结果码（常见的几个）和返回网页（没登录时是登录页）翻成中文。</summary>
	private static string ResultText(object code, string text)
	{
		if (code is double d)
		{
			int c = (int)d;
			switch (c)
			{
			case 2:
				return "Steam 出错（结果码 2），稍后再试";
			case 8:
				return "参数不对（结果码 8）";
			case 9:
				return "找不到这个壁纸（结果码 9）：可能已被删除、设为私密，或这个号看不到它";
			case 15:
				return "没有权限（结果码 15）：可能是登录过期，或这个号不能订阅它";
			case 16:
			case 20:
				return "Steam 超时或忙（结果码 " + c + "），稍后再试";
			case 21:
				return "账号没有登录（结果码 21），请重新登录";
			case 25:
				return "订阅数到上限了（结果码 25）";
			case 84:
				return "操作太频繁（结果码 84），过一会再试";
			default:
				return "Steam 返回结果码 " + c;
			}
		}
		if (code == null && (text.TrimStart().StartsWith("<") || text.IndexOf("login", StringComparison.OrdinalIgnoreCase) >= 0))
		{
			return "登录过期了，请重新登录";
		}
		return "Steam 没有确认";
	}

	public async Task<bool> FollowAsync(string steamId, bool on)
	{
		CheckSteamId(steamId);
		string text = await PostCommunityAsync(Community + "/profiles/" + steamId + "/" + (on ? "followuser" : "unfollowuser") + "/", new Dictionary<string, string>
		{
			["steamid"] = steamId,
			["sessionid"] = SessionId
		});
		return SuccessOf(text);
	}

	/// <summary>Steam 返回 {"success":1}（订阅）或 {"success":true}（关注）。</summary>
	private static bool SuccessOf(string text)
	{
		try
		{
			object s = Json.ParseObj(text)["success"];
			return s is bool b ? b : (s is double d && d == 1.0);
		}
		catch
		{
			return false;
		}
	}

	/// <summary>作者名字和头像（公开的 ?xml=1），内存里缓存。</summary>
	public async Task<Profile> ProfileAsync(string steamId)
	{
		CheckSteamId(steamId);
		lock (_profiles)
		{
			if (_profiles.TryGetValue(steamId, out Profile cached))
			{
				return cached;
			}
		}
		await _profileGate.WaitAsync();
		try
		{
			var p = new Profile { SteamId = steamId };
			try
			{
				string xml = await GetCommunityAsync(Community + "/profiles/" + steamId + "/?xml=1", withCookies: false);
				p.Name = WebUtility.HtmlDecode(Cdata(xml, "steamID"));
				p.Avatar = Cdata(xml, "avatarMedium");
			}
			catch (Exception ex)
			{
				_log.Log("读取作者资料失败 " + steamId + "：" + ex.Message);
				return p;
			}
			lock (_profiles)
			{
				_profiles[steamId] = p;
			}
			return p;
		}
		finally
		{
			_profileGate.Release();
		}
	}

	private static string Cdata(string xml, string tag)
	{
		Match m = Regex.Match(xml, "<" + tag + ">\\s*(?:<!\\[CDATA\\[(.*?)\\]\\]>|([^<]*))\\s*</" + tag + ">", RegexOptions.Singleline);
		if (!m.Success)
		{
			return "";
		}
		return (m.Groups[1].Success ? m.Groups[1].Value : m.Groups[2].Value).Trim();
	}

	private static void CheckSteamId(string id)
	{
		if (!Regex.IsMatch(id ?? "", "^\\d{17}$"))
		{
			throw new SteamException(400, "Steam ID 不对");
		}
	}
}

public sealed class SteamException : Exception
{
	public int Status { get; }

	public SteamException(int status, string message)
		: base(message)
	{
		Status = status;
	}
}
