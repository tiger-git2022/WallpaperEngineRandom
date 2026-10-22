using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace WERandom;

public class Config
{
	public string Mode = "grouped";

	public List<Tier> Tiers = new List<Tier>
	{
		new Tier(2.0, 10.0),
		new Tier(4.0, 5.0),
		new Tier(6.0, 3.0),
		new Tier(12.0, 1.0)
	};

	public double OlderWeight = 1.0;

	public int AvoidRecentCount = 5;

	public bool RebalanceMidCycle = true;

	public string Monitors = "all";

	public bool SameOnAllMonitors = true;

	public List<string> IncludeTypes = new List<string>();

	public List<string> ExcludeIds = new List<string>();

	public string WallpaperEnginePath = "";

	public List<string> WorkshopPaths = new List<string>();

	public int IntervalMinutes = 20;

	public bool OnlyWhenVisible = true;

	public int IdleMinutes = 0;

	public bool PauseWhenFullscreen = true;

	public bool SwitchWhenWEStarts = true;

	public bool Paused = false;

	/// <summary>Main window: "system" | "light" | "dark".</summary>
	public string Theme = "system";

	/// <summary>Main window background: "mica" | "acrylic" (translucent, blurs what is behind) | "none".</summary>
	public string Backdrop = "mica";

	/// <summary>Acrylic background: opacity (%) of the grey layer over it (dark grey / light grey by theme).</summary>
	public int TintPercent = 20;

	public static Config Load(string path)
	{
		Config config = new Config();
		if (!File.Exists(path))
		{
			return config;
		}
		JObj jObj;
		try
		{
			jObj = Json.ParseObj(File.ReadAllText(path, Encoding.UTF8));
		}
		catch
		{
			return config;
		}
		config.Mode = jObj.Str("mode", config.Mode).ToLowerInvariant();
		if (jObj.Has("tiers"))
		{
			config.Tiers = new List<Tier>();
			foreach (object item in jObj.List("tiers"))
			{
				if (item is JObj jObj2)
				{
					config.Tiers.Add(new Tier(jObj2.Num("maxMonths"), jObj2.Num("weight")));
				}
			}
		}
		config.OlderWeight = jObj.Num("olderWeight", config.OlderWeight);
		config.AvoidRecentCount = jObj.Int("avoidRecentCount", config.AvoidRecentCount);
		config.RebalanceMidCycle = jObj.Bool("rebalanceMidCycle", config.RebalanceMidCycle);
		object obj2 = jObj["monitors"];
		if (obj2 is List<object>)
		{
			config.Monitors = string.Join(",", jObj.StrList("monitors").ToArray());
		}
		else if (obj2 != null)
		{
			config.Monitors = Convert.ToString(obj2, CultureInfo.InvariantCulture);
		}
		config.SameOnAllMonitors = jObj.Bool("sameOnAllMonitors", config.SameOnAllMonitors);
		config.IncludeTypes = (from s in jObj.StrList("includeTypes")
			select s.ToLowerInvariant()).ToList();
		config.ExcludeIds = jObj.StrList("excludeIds");
		config.WallpaperEnginePath = jObj.Str("wallpaperEnginePath");
		config.WorkshopPaths = jObj.StrList("workshopPaths");
		config.IntervalMinutes = Math.Max(1, jObj.Int("intervalMinutes", config.IntervalMinutes));
		config.OnlyWhenVisible = jObj.Bool("onlyWhenVisible", config.OnlyWhenVisible);
		config.IdleMinutes = Math.Max(0, jObj.Int("idleMinutes", config.IdleMinutes));
		config.PauseWhenFullscreen = jObj.Bool("pauseWhenFullscreen", config.PauseWhenFullscreen);
		config.SwitchWhenWEStarts = jObj.Bool("switchWhenWEStarts", config.SwitchWhenWEStarts);
		config.Paused = jObj.Bool("paused");
		config.Theme = jObj.Str("theme", config.Theme);
		config.Backdrop = jObj.Str("backdrop", config.Backdrop);
		config.TintPercent = Math.Min(100, Math.Max(0, jObj.Int("tintPercent", config.TintPercent)));
		return config;
	}

	public void Save(string path)
	{
		JObj jObj = new JObj();
		jObj["_说明"] = "档位：添加时间 <= maxMonths 个月的壁纸每轮出现 weight 次；0 = 不播放。也可以在托盘图标的「设置」里改。";
		jObj["mode"] = Mode;
		List<object> list = new List<object>();
		foreach (Tier item in Tiers.OrderBy((Tier t) => t.MaxMonths))
		{
			JObj jObj2 = new JObj();
			jObj2["maxMonths"] = item.MaxMonths;
			jObj2["weight"] = item.Weight;
			list.Add(jObj2);
		}
		jObj["tiers"] = list;
		jObj["olderWeight"] = OlderWeight;
		jObj["avoidRecentCount"] = AvoidRecentCount;
		jObj["rebalanceMidCycle"] = RebalanceMidCycle;
		jObj["monitors"] = Monitors;
		jObj["sameOnAllMonitors"] = SameOnAllMonitors;
		jObj["includeTypes"] = IncludeTypes.Cast<object>().ToList();
		jObj["excludeIds"] = ExcludeIds.Cast<object>().ToList();
		jObj["wallpaperEnginePath"] = WallpaperEnginePath;
		jObj["workshopPaths"] = WorkshopPaths.Cast<object>().ToList();
		jObj["intervalMinutes"] = IntervalMinutes;
		jObj["onlyWhenVisible"] = OnlyWhenVisible;
		jObj["idleMinutes"] = IdleMinutes;
		jObj["pauseWhenFullscreen"] = PauseWhenFullscreen;
		jObj["switchWhenWEStarts"] = SwitchWhenWEStarts;
		jObj["paused"] = Paused;
		jObj["theme"] = Theme;
		jObj["backdrop"] = Backdrop;
		jObj["tintPercent"] = TintPercent;
		File.WriteAllText(path, Json.Write(jObj), new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
	}

	public List<int> MonitorList(int count)
	{
		List<int> list = new List<int>();
		if (string.IsNullOrEmpty(Monitors) || Monitors.Trim().ToLowerInvariant() == "all")
		{
			for (int i = 0; i < Math.Max(1, count); i++)
			{
				list.Add(i);
			}
			return list;
		}
		string[] array = Monitors.Split(new char[3] { ',', ' ', ';' }, StringSplitOptions.RemoveEmptyEntries);
		foreach (string text in array)
		{
			if (int.TryParse(text.Trim(), out var result))
			{
				list.Add(result);
			}
		}
		if (list.Count == 0)
		{
			list.Add(0);
		}
		return list;
	}
}
