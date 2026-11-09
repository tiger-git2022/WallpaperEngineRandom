using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace WERandom;

/// <summary>
/// What Wallpaper Engine is showing right now, per monitor, read from WE's own config.json
/// (user → general → wallpaperconfig → selectedwallpapers). This also catches wallpapers picked by hand in WE.
/// </summary>
public static class CurrentWallpaper
{
	public sealed class Item
	{
		/// <summary>WE's monitor key, e.g. //?/DISPLAY#AOCA611#...</summary>
		public string MonitorKey = "";

		/// <summary>Short name for the screen, e.g. "AOC 27G2" or "AOC".</summary>
		public string Monitor = "";

		public string Id = "";

		public string Title = "";

		public string Folder = "";

		/// <summary>preview.jpg / .gif / .png in the wallpaper folder, or null.</summary>
		public string PreviewPath;

		public bool IsWorkshop;
	}

	private static readonly object Lock = new object();

	private static string _cachedPath;

	private static DateTime _cachedTime;

	private static List<Item> _cached = new List<Item>();

	private static Dictionary<string, string> _monitorNames;

	/// <summary>Current wallpapers; re-reads config.json only when it changed. Never throws.</summary>
	public static List<Item> Read(string weExe)
	{
		try
		{
			if (string.IsNullOrEmpty(weExe))
			{
				return new List<Item>();
			}
			string cfg = Path.Combine(Path.GetDirectoryName(weExe), "config.json");
			if (!File.Exists(cfg))
			{
				return new List<Item>();
			}
			DateTime mtime = File.GetLastWriteTimeUtc(cfg);
			lock (Lock)
			{
				if (cfg == _cachedPath && mtime == _cachedTime)
				{
					return _cached;
				}
			}
			string text = ReadShared(cfg);
			List<Item> items = Parse(text);
			lock (Lock)
			{
				_cachedPath = cfg;
				_cachedTime = mtime;
				_cached = items;
			}
			return items;
		}
		catch
		{
			lock (Lock)
			{
				return _cached;
			}
		}
	}

	/// <summary>WE may be writing the file; open it with full sharing.</summary>
	private static string ReadShared(string path)
	{
		using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
		using var sr = new StreamReader(fs, Encoding.UTF8);
		return sr.ReadToEnd();
	}

	private static List<Item> Parse(string text)
	{
		JObj root = Json.ParseObj(text);
		JObj selected = null;
		// WE keeps one section per Windows user; prefer the current one.
		var users = new List<string>();
		if (root.Has(Environment.UserName))
		{
			users.Add(Environment.UserName);
		}
		users.AddRange(root.Keys.Where(k => k != Environment.UserName));
		foreach (string u in users)
		{
			JObj sel = root.Obj(u)?.Obj("general")?.Obj("wallpaperconfig")?.Obj("selectedwallpapers");
			if (sel != null && sel.Count > 0)
			{
				selected = sel;
				break;
			}
		}
		var list = new List<Item>();
		if (selected == null)
		{
			return list;
		}
		Dictionary<string, string> names = MonitorNames();
		foreach (string key in selected.Keys)
		{
			string file = selected.Obj(key)?.Str("file") ?? "";
			if (file.Length == 0)
			{
				continue;
			}
			string folder = Path.GetDirectoryName(file.Replace('/', Path.DirectorySeparatorChar)) ?? "";
			var item = new Item
			{
				MonitorKey = key,
				Monitor = MonitorLabel(key, names),
				Folder = folder,
				Id = Path.GetFileName(folder),
				IsWorkshop = folder.Replace('\\', '/').IndexOf("/workshop/content/431960/", StringComparison.OrdinalIgnoreCase) >= 0
			};
			item.Title = item.Id;
			try
			{
				string project = Path.Combine(folder, "project.json");
				if (File.Exists(project))
				{
					JObj p = Json.ParseObj(File.ReadAllText(project, Encoding.UTF8));
					string t = p.Str("title");
					if (t.Length > 0)
					{
						item.Title = t;
					}
					string pv = p.Str("preview");
					if (pv.Length > 0 && File.Exists(Path.Combine(folder, pv)))
					{
						item.PreviewPath = Path.Combine(folder, pv);
					}
				}
			}
			catch
			{
			}
			if (item.PreviewPath == null)
			{
				foreach (string n in new[] { "preview.jpg", "preview.gif", "preview.png", "preview.jpeg" })
				{
					string p = Path.Combine(folder, n);
					if (File.Exists(p))
					{
						item.PreviewPath = p;
						break;
					}
				}
			}
			list.Add(item);
		}
		return list;
	}

	/// <summary>"//?/DISPLAY#AOCA611#5&amp;..." → friendly name from the monitor's EDID, or the maker code.</summary>
	private static string MonitorLabel(string key, Dictionary<string, string> names)
	{
		string[] parts = key.Split('#');
		string pnp = parts.Length > 1 ? parts[1] : key;
		if (names.TryGetValue(pnp.ToUpperInvariant(), out string name) && name.Length > 0)
		{
			return name;
		}
		string maker = pnp.Length >= 3 ? pnp.Substring(0, 3).ToUpperInvariant() : pnp;
		switch (maker)
		{
		case "DEL": return "Dell";
		case "GSM": return "LG";
		case "SAM": return "三星";
		case "AUS": return "华硕";
		case "ACR": return "宏碁";
		case "BNQ": return "明基";
		case "HWP": return "HP";
		case "LEN": return "联想";
		case "MSI": return "MSI";
		case "AOC": return "AOC";
		default: return pnp;
		}
	}

	/// <summary>PNP id (e.g. AOCA611) → model name from WMI (WmiMonitorID). Cached; empty when WMI fails.</summary>
	private static Dictionary<string, string> MonitorNames()
	{
		if (_monitorNames != null)
		{
			return _monitorNames;
		}
		var d = new Dictionary<string, string>();
		try
		{
			using var searcher = new System.Management.ManagementObjectSearcher("root\\wmi", "SELECT InstanceName, UserFriendlyName, ManufacturerName FROM WmiMonitorID");
			foreach (System.Management.ManagementBaseObject mo in searcher.Get())
			{
				string inst = Convert.ToString(mo["InstanceName"]) ?? "";
				string[] p = inst.Split('\\');
				if (p.Length < 2)
				{
					continue;
				}
				string model = Decode(mo["UserFriendlyName"] as ushort[]);
				if (model.Length > 0)
				{
					d[p[1].ToUpperInvariant()] = model;
				}
			}
		}
		catch
		{
		}
		_monitorNames = d;
		return d;
	}

	private static string Decode(ushort[] chars)
	{
		if (chars == null)
		{
			return "";
		}
		var sb = new StringBuilder();
		foreach (ushort c in chars)
		{
			if (c == 0)
			{
				break;
			}
			sb.Append((char)c);
		}
		return sb.ToString().Trim();
	}
}
