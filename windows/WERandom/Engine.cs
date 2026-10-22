using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace WERandom;

public class Engine
{
	private class MetaEntry
	{
		public long Mtime;

		public long Size;

		public string Title;

		public string Type;
	}

	private class PendingMeta
	{
		public string Id;

		public string Project;

		public long Mtime;

		public long Size;

		public string Title;

		public string Type;
	}

	public class QueueState
	{
		public List<string> Queue = new List<string>();

		public List<string> Members = new List<string>();

		public int Length;

		public DateTime Started;

		public Dictionary<string, int> Assigned = new Dictionary<string, int>();

		public Dictionary<string, double> AssignedWeight = new Dictionary<string, double>();

		public Dictionary<string, int> Played = new Dictionary<string, int>();

		public List<List<string>> Groups;

		public int CurrentGroup
		{
			get
			{
				if (Groups == null)
				{
					return -1;
				}
				for (int i = 0; i < Groups.Count; i++)
				{
					if (Groups[i].Count > 0)
					{
						return i;
					}
				}
				return -1;
			}
		}

		public int Remaining
		{
			get
			{
				if (Groups == null)
				{
					return Queue.Count;
				}
				int num = 0;
				foreach (List<string> group in Groups)
				{
					num += group.Count;
				}
				return num;
			}
		}
	}

	public class Preview
	{
		public Paths Paths;

		public List<Wallpaper> All = new List<Wallpaper>();

		public int CycleLength;

		public double EstimatedDays;

		public QueueState Queue;

		public string Error;
	}

	public const string AppId = "431960";

	public const double DaysPerMonth = 30.4375;

	public readonly string Dir;

	public readonly string ConfigPath;

	public readonly string SeenPath;

	public readonly string HistoryPath;

	public readonly string QueuePath;

	public readonly string LogPath;

	public readonly Logger Log;

	public Config Cfg;

	private readonly IPlatform _pf;

	private readonly Random _rng = new Random();

	private Dictionary<string, MetaEntry> _meta;

	private bool _metaDirty;

	public readonly string CachePath;

	public readonly object ScanLock = new object();

	public int LastScanMs;

	public int LastCacheMiss;

	public string LastTitle = "";

	private int GroupCount => Cfg.Tiers.Count + 1;

	public Engine(string dir, IPlatform pf)
	{
		Dir = dir;
		_pf = pf;
		ConfigPath = Path.Combine(dir, "we_random.config.json");
		SeenPath = Path.Combine(dir, "we_random.firstseen.json");
		HistoryPath = Path.Combine(dir, "we_random.history.json");
		QueuePath = Path.Combine(dir, "we_random.queue.json");
		LogPath = Path.Combine(dir, "we_random.log");
		CachePath = Path.Combine(dir, "we_random.cache.json");
		Log = new Logger(LogPath);
		Cfg = Config.Load(ConfigPath);
		if (!File.Exists(ConfigPath))
		{
			try
			{
				Cfg.Save(ConfigPath);
			}
			catch
			{
			}
		}
	}

	public void ReloadConfig()
	{
		Cfg = Config.Load(ConfigPath);
	}

	public Paths ResolvePaths()
	{
		Paths paths = new Paths();
		paths.Exe = Cfg.WallpaperEnginePath ?? "";
		Paths paths2 = paths;
		paths2.Workshop = Cfg.WorkshopPaths.Where((string w) => !string.IsNullOrWhiteSpace(w)).ToList();
		if (string.IsNullOrWhiteSpace(paths2.Exe) || paths2.Workshop.Count == 0)
		{
			foreach (string item in _pf.SteamLibraries())
			{
				if (string.IsNullOrWhiteSpace(paths2.Exe))
				{
					string text = Path.Combine(item, "steamapps", "common", "wallpaper_engine", "wallpaper64.exe");
					if (File.Exists(text))
					{
						paths2.Exe = text;
					}
				}
				if (paths2.Workshop.Count == 0)
				{
					string text2 = Path.Combine(item, "steamapps", "workshop", "content", "431960");
					if (Directory.Exists(text2))
					{
						paths2.Workshop.Add(text2);
					}
				}
			}
		}
		if (string.IsNullOrWhiteSpace(paths2.Exe) || !File.Exists(paths2.Exe))
		{
			throw new Exception("找不到 wallpaper64.exe，请在设置里手动选择它的路径。");
		}
		if (paths2.Workshop.Count == 0)
		{
			throw new Exception("找不到创意工坊目录 (steamapps\\workshop\\content\\431960)，请在设置里手动选择。");
		}
		return paths2;
	}

	private void LoadMeta()
	{
		if (_meta != null)
		{
			return;
		}
		_meta = new Dictionary<string, MetaEntry>();
		try
		{
			if (!File.Exists(CachePath))
			{
				return;
			}
			JObj jObj = Json.ParseObj(File.ReadAllText(CachePath, Encoding.UTF8));
			foreach (string key in jObj.Keys)
			{
				JObj jObj2 = jObj.Obj(key);
				if (jObj2 != null)
				{
					_meta[key] = new MetaEntry
					{
						Mtime = (long)jObj2.Num("m"),
						Size = (long)jObj2.Num("s"),
						Title = jObj2.Str("t"),
						Type = jObj2.Str("y")
					};
				}
			}
		}
		catch
		{
			_meta = new Dictionary<string, MetaEntry>();
		}
	}

	private void SaveMeta()
	{
		if (!_metaDirty || _meta == null)
		{
			return;
		}
		try
		{
			JObj jObj = new JObj();
			foreach (KeyValuePair<string, MetaEntry> metum in _meta)
			{
				JObj jObj2 = new JObj();
				jObj2["m"] = metum.Value.Mtime;
				jObj2["s"] = metum.Value.Size;
				jObj2["t"] = metum.Value.Title;
				jObj2["y"] = metum.Value.Type;
				jObj[metum.Key] = jObj2;
			}
			File.WriteAllText(CachePath, Json.Write(jObj, pretty: false), new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
			_metaDirty = false;
		}
		catch
		{
		}
	}

	private static void ParseMetaFile(PendingMeta pm)
	{
		pm.Title = pm.Id;
		pm.Type = "";
		try
		{
			string text = File.ReadAllText(pm.Project, Encoding.UTF8);
			string title;
			string type;
			try
			{
				Json.ExtractTopLevel(text, out title, out type);
			}
			catch
			{
				JObj jObj = Json.ParseObj(text);
				title = ((!jObj.Has("title")) ? null : jObj.Str("title"));
				type = ((!jObj.Has("type")) ? null : jObj.Str("type"));
			}
			if (!string.IsNullOrEmpty(title))
			{
				pm.Title = title;
			}
			if (!string.IsNullOrEmpty(type))
			{
				pm.Type = type.ToLowerInvariant();
			}
		}
		catch
		{
		}
	}

	private void FillMeta(List<PendingMeta> misses)
	{
		if (misses.Count == 0)
		{
			return;
		}
		if (misses.Count < 8)
		{
			foreach (PendingMeta miss in misses)
			{
				ParseMetaFile(miss);
			}
		}
		else
		{
			Parallel.ForEach(misses, new ParallelOptions
			{
				MaxDegreeOfParallelism = Math.Min(8, Math.Max(2, Environment.ProcessorCount))
			}, delegate(PendingMeta pm)
			{
				ParseMetaFile(pm);
			});
		}
		foreach (PendingMeta miss2 in misses)
		{
			_meta[miss2.Id] = new MetaEntry
			{
				Mtime = miss2.Mtime,
				Size = miss2.Size,
				Title = miss2.Title,
				Type = miss2.Type
			};
		}
		_metaDirty = true;
		LastCacheMiss += misses.Count;
	}

	public List<Wallpaper> Scan(Paths paths)
	{
		lock (ScanLock)
		{
			Stopwatch stopwatch = Stopwatch.StartNew();
			LastCacheMiss = 0;
			List<Wallpaper> result = ScanInner(paths);
			LastScanMs = (int)stopwatch.ElapsedMilliseconds;
			return result;
		}
	}

	private List<Wallpaper> ScanInner(Paths paths)
	{
		Dictionary<string, DateTime> dictionary = new Dictionary<string, DateTime>();
		if (File.Exists(SeenPath))
		{
			try
			{
				JObj jObj = Json.ParseObj(File.ReadAllText(SeenPath, Encoding.UTF8));
				foreach (string key in jObj.Keys)
				{
					if (DateTime.TryParse(jObj.Str(key), CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var result))
					{
						dictionary[key] = ((result.Kind != DateTimeKind.Utc) ? result : result.ToLocalTime());
					}
				}
			}
			catch
			{
			}
		}
		bool flag = false;
		DateTime dateTime = _pf.Now();
		List<Wallpaper> list = new List<Wallpaper>();
		LoadMeta();
		List<PendingMeta> list2 = new List<PendingMeta>();
		List<PendingMeta> list3 = new List<PendingMeta>();
		foreach (string item in paths.Workshop)
		{
			if (!Directory.Exists(item))
			{
				continue;
			}
			string[] directories = Directory.GetDirectories(item);
			foreach (string text in directories)
			{
				string text2 = Path.Combine(text, "project.json");
				string fileName = Path.GetFileName(text);
				if (Cfg.ExcludeIds.Contains(fileName))
				{
					continue;
				}
				long num;
				long length;
				try
				{
					FileInfo fileInfo = new FileInfo(text2);
					if (!fileInfo.Exists)
					{
						continue;
					}
					num = fileInfo.LastWriteTimeUtc.Ticks / 10000000;
					length = fileInfo.Length;
					goto IL_0199;
				}
				catch
				{
				}
				continue;
				IL_0199:
				PendingMeta pendingMeta = new PendingMeta();
				pendingMeta.Id = fileName;
				pendingMeta.Project = text2;
				pendingMeta.Mtime = num;
				pendingMeta.Size = length;
				PendingMeta pendingMeta2 = pendingMeta;
				if (_meta.TryGetValue(fileName, out var value) && value.Mtime == num && value.Size == length)
				{
					pendingMeta2.Title = ((!string.IsNullOrEmpty(value.Title)) ? value.Title : fileName);
					pendingMeta2.Type = value.Type ?? "";
				}
				else
				{
					list3.Add(pendingMeta2);
				}
				list2.Add(pendingMeta2);
			}
		}
		FillMeta(list3);
		foreach (PendingMeta item2 in list2)
		{
			string directoryName = Path.GetDirectoryName(item2.Project);
			string project = item2.Project;
			string id = item2.Id;
			string title = item2.Title;
			string type = item2.Type;
			if (Cfg.IncludeTypes.Count > 0 && !Cfg.IncludeTypes.Contains(type))
			{
				continue;
			}
			DateTime dateTime2;
			try
			{
				dateTime2 = Directory.GetCreationTime(directoryName);
			}
			catch
			{
				dateTime2 = dateTime;
			}
			DateTime dateTime3;
			if (dictionary.ContainsKey(id))
			{
				dateTime3 = dictionary[id];
				if (dateTime2 < dateTime3)
				{
					dateTime3 = dateTime2;
					dictionary[id] = dateTime2;
					flag = true;
				}
			}
			else
			{
				dateTime3 = (dictionary[id] = ((!(dateTime2 > dateTime)) ? dateTime2 : dateTime));
				flag = true;
			}
			double totalDays = (dateTime - dateTime3).TotalDays;
			list.Add(new Wallpaper
			{
				Id = id,
				Title = title,
				Type = type,
				Project = project,
				Added = dateTime3,
				AgeDays = Math.Round(totalDays, 1),
				AgeMonths = Math.Round(totalDays / 30.4375, 2)
			});
		}
		if (flag)
		{
			try
			{
				JObj jObj2 = new JObj();
				foreach (string item3 in dictionary.Keys.OrderBy((string k) => k))
				{
					jObj2[item3] = dictionary[item3].ToString("o");
				}
				File.WriteAllText(SeenPath, Json.Write(jObj2), new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
			}
			catch
			{
			}
		}
		SaveMeta();
		SetWeights(list);
		return list;
	}

	public void SetWeights(List<Wallpaper> list)
	{
		List<Tier> list2 = Cfg.Tiers.OrderBy((Tier t) => t.MaxMonths).ToList();
		foreach (Wallpaper item in list)
		{
			item.Weight = Cfg.OlderWeight;
			item.Tier = ">" + ((list2.Count <= 0) ? "0" : Fmt(list2[list2.Count - 1].MaxMonths)) + "月";
			item.TierIndex = list2.Count;
			for (int i = 0; i < list2.Count; i++)
			{
				if (item.AgeMonths <= list2[i].MaxMonths)
				{
					item.Weight = list2[i].Weight;
					item.Tier = "≤" + Fmt(list2[i].MaxMonths) + "月";
					item.TierIndex = i;
					break;
				}
			}
		}
	}

	public static string Fmt(double d)
	{
		return d.ToString("0.##", CultureInfo.InvariantCulture);
	}

	/// <summary>Title of the last wallpaper put on (history), for the main window after a restart.</summary>
	public string LastPlayedTitle()
	{
		List<string> history = LoadHistory();
		if (history.Count == 0)
		{
			return "";
		}
		string id = history[history.Count - 1];
		Wallpaper w = Scan(ResolvePaths()).FirstOrDefault((Wallpaper x) => x.Id == id);
		return w != null ? w.Title : id;
	}

	private List<string> LoadHistory()
	{
		try
		{
			if (File.Exists(HistoryPath))
			{
				return ((Json.Parse(File.ReadAllText(HistoryPath, Encoding.UTF8)) as List<object>) ?? new List<object>()).Select((object o) => Convert.ToString(o)).ToList();
			}
		}
		catch
		{
		}
		return new List<string>();
	}

	private void SaveHistory(List<string> h)
	{
		try
		{
			File.WriteAllText(HistoryPath, Json.Write(h.Cast<object>().ToList()), new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
		}
		catch
		{
		}
	}

	public int Copies(double weight)
	{
		if (weight <= 0.0)
		{
			return 0;
		}
		int num = (int)Math.Floor(weight);
		double num2 = weight - (double)num;
		if (num2 > 0.0 && _rng.NextDouble() < num2)
		{
			num++;
		}
		return num;
	}

	private QueueState NewCycle(List<Wallpaper> candidates)
	{
		QueueState queueState = new QueueState();
		queueState.Started = _pf.Now();
		QueueState queueState2 = queueState;
		int num = 0;
		foreach (Wallpaper candidate in candidates)
		{
			int num2 = Copies(candidate.Weight);
			if (num2 > 0)
			{
				queueState2.Assigned[candidate.Id] = num2;
				queueState2.AssignedWeight[candidate.Id] = candidate.Weight;
				queueState2.Played[candidate.Id] = 0;
				if (num2 > num)
				{
					num = num2;
				}
			}
		}
		if (num == 0)
		{
			return queueState2;
		}
		queueState2.Queue = BuildSpread(queueState2.Assigned);
		queueState2.Members = queueState2.Assigned.Keys.ToList();
		queueState2.Length = queueState2.Queue.Count;
		return queueState2;
	}

	private List<string> BuildSpread(Dictionary<string, int> copies)
	{
		List<string> list = new List<string>();
		int num = 0;
		foreach (KeyValuePair<string, int> copy in copies)
		{
			if (copy.Value > num)
			{
				num = copy.Value;
			}
		}
		if (num == 0)
		{
			return list;
		}
		List<List<string>> list2 = new List<List<string>>();
		for (int i = 0; i < num; i++)
		{
			list2.Add(new List<string>());
		}
		foreach (KeyValuePair<string, int> copy2 in copies)
		{
			int value = copy2.Value;
			if (value <= 0)
			{
				continue;
			}
			double num2 = (double)num / (double)value;
			double num3 = _rng.NextDouble() * num2;
			for (int j = 0; j < value; j++)
			{
				int num4 = (int)Math.Floor(num3 + (double)j * num2);
				if (num4 >= num)
				{
					num4 = num - 1;
				}
				list2[num4].Add(copy2.Key);
			}
		}
		foreach (List<string> item in list2)
		{
			List<string> shuffled = item.OrderBy((string x) => _rng.Next()).ToList();
			if (list.Count > 0 && shuffled.Count > 1 && shuffled[0] == list[list.Count - 1])
			{
				int num5 = shuffled.FindIndex((string x) => x != shuffled[0]);
				if (num5 > 0)
				{
					string value2 = shuffled[0];
					shuffled[0] = shuffled[num5];
					shuffled[num5] = value2;
				}
			}
			list.AddRange(shuffled);
		}
		return list;
	}

	private QueueState NewGroupedCycle(List<Wallpaper> candidates)
	{
		QueueState queueState = new QueueState();
		queueState.Started = _pf.Now();
		queueState.Groups = new List<List<string>>();
		QueueState queueState2 = queueState;
		List<Dictionary<string, int>> list = new List<Dictionary<string, int>>();
		for (int i = 0; i < GroupCount; i++)
		{
			list.Add(new Dictionary<string, int>());
		}
		foreach (Wallpaper candidate in candidates)
		{
			int num = Copies(candidate.Weight);
			if (num > 0)
			{
				int index = Math.Min(candidate.TierIndex, GroupCount - 1);
				list[index][candidate.Id] = num;
				queueState2.Assigned[candidate.Id] = num;
				queueState2.AssignedWeight[candidate.Id] = candidate.Weight;
				queueState2.Played[candidate.Id] = 0;
			}
		}
		int num2 = 0;
		foreach (Dictionary<string, int> item in list)
		{
			List<string> list2 = BuildSpread(item);
			queueState2.Groups.Add(list2);
			num2 += list2.Count;
		}
		queueState2.Members = queueState2.Assigned.Keys.ToList();
		queueState2.Length = num2;
		return queueState2;
	}

	private QueueState GetGroupedQueue(List<Wallpaper> candidates)
	{
		Dictionary<string, Wallpaper> byId = candidates.ToDictionary((Wallpaper c) => c.Id);
		QueueState queueState = ReadQueue();
		if (queueState != null && (queueState.Groups == null || queueState.Groups.Count != GroupCount))
		{
			queueState = null;
		}
		if (queueState != null)
		{
			for (int i = 0; i < queueState.Groups.Count; i++)
			{
				queueState.Groups[i] = queueState.Groups[i].Where((string id) => byId.ContainsKey(id)).ToList();
			}
			if (queueState.Remaining == 0)
			{
				queueState = null;
			}
		}
		if (queueState != null)
		{
			int val = Math.Max(0, queueState.CurrentGroup);
			int num = 0;
			foreach (Wallpaper candidate in candidates)
			{
				if (!queueState.Members.Contains(candidate.Id))
				{
					int num2 = Copies(candidate.Weight);
					int index = Math.Max(Math.Min(candidate.TierIndex, GroupCount - 1), val);
					queueState.Length += AddToQueue(queueState.Groups[index], candidate.Id, num2);
					queueState.Members.Add(candidate.Id);
					queueState.Assigned[candidate.Id] = num2;
					queueState.AssignedWeight[candidate.Id] = candidate.Weight;
					queueState.Played[candidate.Id] = 0;
					num++;
				}
			}
			if (num > 0)
			{
				Log.Log("本轮中途发现 " + num + " 张新壁纸，已插入当前轮");
			}
			if (Cfg.RebalanceMidCycle)
			{
				foreach (Wallpaper c2 in candidates)
				{
					if (!queueState.AssignedWeight.TryGetValue(c2.Id, out var value) || value == c2.Weight)
					{
						continue;
					}
					int num3 = Copies(c2.Weight);
					int num4 = (queueState.Played.ContainsKey(c2.Id) ? queueState.Played[c2.Id] : 0);
					int num5 = 0;
					foreach (List<string> group in queueState.Groups)
					{
						num5 += group.RemoveAll((string x) => x == c2.Id);
					}
					int num6 = Math.Max(0, num3 - num4);
					int num7 = Math.Max(Math.Min(c2.TierIndex, GroupCount - 1), val);
					AddToQueue(queueState.Groups[num7], c2.Id, num6);
					queueState.Length += num6 - num5;
					int num8 = (queueState.Assigned.ContainsKey(c2.Id) ? queueState.Assigned[c2.Id] : 0);
					Log.Log($"跨档 [{c2.Id}] {c2.Title} -> {c2.Tier}：每轮 {num8} -> {num3}，已播 {num4}，剩余 {num5} -> {num6}（移入 {GroupName(num7)} 组）");
					queueState.Assigned[c2.Id] = num3;
					queueState.AssignedWeight[c2.Id] = c2.Weight;
				}
			}
			if (queueState.Remaining == 0)
			{
				queueState = null;
			}
		}
		if (queueState == null)
		{
			queueState = NewGroupedCycle(candidates);
			List<string> list = new List<string>();
			for (int j = 0; j < queueState.Groups.Count; j++)
			{
				list.Add(GroupName(j) + " " + queueState.Groups[j].Count);
			}
			Log.Log("开始新的一轮（分组）：共 " + queueState.Length + " 次切换，覆盖 " + queueState.Members.Count + " 张；各组：" + string.Join("，", list.ToArray()));
		}
		return queueState;
	}

	public string GroupName(int i)
	{
		List<Tier> list = Cfg.Tiers.OrderBy((Tier t) => t.MaxMonths).ToList();
		if (i < list.Count)
		{
			return "≤" + Fmt(list[i].MaxMonths) + "月";
		}
		return ">" + ((list.Count <= 0) ? "0" : Fmt(list[list.Count - 1].MaxMonths)) + "月";
	}

	private string PopNextGrouped(QueueState st, string lastId)
	{
		int currentGroup = st.CurrentGroup;
		if (currentGroup < 0)
		{
			return null;
		}
		List<string> list = st.Groups[currentGroup];
		int index = 0;
		if (list.Count > 1 && list[0] == lastId)
		{
			for (int i = 1; i < list.Count; i++)
			{
				if (list[i] != lastId)
				{
					index = i;
					break;
				}
			}
		}
		string text = list[index];
		list.RemoveAt(index);
		st.Played[text] = (st.Played.ContainsKey(text) ? st.Played[text] : 0) + 1;
		if (list.Count == 0)
		{
			Log.Log("第 " + (currentGroup + 1) + " 组（" + GroupName(currentGroup) + "）已放完" + ((st.CurrentGroup < 0) ? "，本轮结束" : ("，接下来放 " + GroupName(st.CurrentGroup))));
		}
		return text;
	}

	private int AddToQueue(List<string> q, string id, int count)
	{
		if (count <= 0)
		{
			return 0;
		}
		int count2 = q.Count;
		List<int> list = new List<int>();
		for (int i = 0; i < count; i++)
		{
			list.Add((int)Math.Floor(((double)i + _rng.NextDouble()) * (double)(count2 + 1) / (double)count));
		}
		foreach (int item in list.OrderByDescending((int x) => x))
		{
			q.Insert(Math.Min(item, q.Count), id);
		}
		return count;
	}

	private int RemoveFromQueue(List<string> q, string id, int count)
	{
		List<int> list = new List<int>();
		for (int i = 0; i < q.Count; i++)
		{
			if (q[i] == id)
			{
				list.Add(i);
			}
		}
		if (count <= 0 || list.Count == 0)
		{
			return 0;
		}
		count = Math.Min(count, list.Count);
		int num = list.Count - count;
		List<int> list2 = new List<int>();
		if (num <= 0)
		{
			list2 = list;
		}
		else
		{
			HashSet<int> hashSet = new HashSet<int>();
			for (int j = 0; j < num; j++)
			{
				hashSet.Add((int)Math.Floor(((double)j + 0.5) * (double)list.Count / (double)num));
			}
			for (int k = 0; k < list.Count; k++)
			{
				if (!hashSet.Contains(k))
				{
					list2.Add(list[k]);
				}
			}
		}
		foreach (int item in list2.OrderByDescending((int x) => x))
		{
			q.RemoveAt(item);
		}
		return list2.Count;
	}

	public QueueState ReadQueue()
	{
		if (!File.Exists(QueuePath))
		{
			return null;
		}
		try
		{
			JObj jObj = Json.ParseObj(File.ReadAllText(QueuePath, Encoding.UTF8));
			QueueState queueState = new QueueState();
			queueState.Queue = jObj.StrList("queue");
			queueState.Members = jObj.StrList("members");
			queueState.Length = jObj.Int("cycleLength");
			queueState.Started = ((!DateTime.TryParse(jObj.Str("cycleStarted"), CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var result)) ? _pf.Now() : result.ToLocalTime());
			JObj jObj2 = jObj.Obj("assigned");
			if (jObj2 != null)
			{
				foreach (string key in jObj2.Keys)
				{
					queueState.Assigned[key] = jObj2.Int(key);
				}
			}
			JObj jObj3 = jObj.Obj("assignedWeight");
			if (jObj3 != null)
			{
				foreach (string key2 in jObj3.Keys)
				{
					queueState.AssignedWeight[key2] = jObj3.Num(key2);
				}
			}
			JObj jObj4 = jObj.Obj("played");
			if (jObj4 != null)
			{
				foreach (string key3 in jObj4.Keys)
				{
					queueState.Played[key3] = jObj4.Int(key3);
				}
			}
			if (jObj.Has("groups"))
			{
				queueState.Groups = new List<List<string>>();
				foreach (object item in jObj.List("groups"))
				{
					List<string> list = new List<string>();
					foreach (object item2 in (item as List<object>) ?? new List<object>())
					{
						list.Add(Convert.ToString(item2));
					}
					queueState.Groups.Add(list);
				}
			}
			return queueState;
		}
		catch
		{
			return null;
		}
	}

	private void WriteQueue(QueueState st)
	{
		JObj jObj = new JObj();
		jObj["cycleStarted"] = st.Started.ToString("o");
		jObj["cycleLength"] = st.Length;
		jObj["members"] = st.Members.Cast<object>().ToList();
		jObj["queue"] = st.Queue.Cast<object>().ToList();
		JObj jObj2 = new JObj();
		foreach (KeyValuePair<string, int> item in st.Assigned)
		{
			jObj2[item.Key] = item.Value;
		}
		jObj["assigned"] = jObj2;
		JObj jObj3 = new JObj();
		foreach (KeyValuePair<string, double> item2 in st.AssignedWeight)
		{
			jObj3[item2.Key] = item2.Value;
		}
		jObj["assignedWeight"] = jObj3;
		JObj jObj4 = new JObj();
		foreach (KeyValuePair<string, int> item3 in st.Played)
		{
			jObj4[item3.Key] = item3.Value;
		}
		jObj["played"] = jObj4;
		if (st.Groups != null)
		{
			jObj["groups"] = ((IEnumerable<List<string>>)st.Groups).Select((Func<List<string>, object>)((List<string> g) => g.Cast<object>().ToList())).ToList();
		}
		File.WriteAllText(QueuePath, Json.Write(jObj), new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
	}

	public void ResetQueue()
	{
		try
		{
			if (File.Exists(QueuePath))
			{
				File.Delete(QueuePath);
			}
		}
		catch
		{
		}
		Log.Log("已清空轮播队列，下次切换时会开始新的一轮");
	}

	private QueueState GetQueue(List<Wallpaper> candidates)
	{
		Dictionary<string, Wallpaper> byId = candidates.ToDictionary((Wallpaper c) => c.Id);
		QueueState queueState = ReadQueue();
		if (queueState != null && queueState.Groups != null)
		{
			queueState = null;
		}
		if (queueState != null)
		{
			queueState.Queue = queueState.Queue.Where((string id) => byId.ContainsKey(id)).ToList();
			int num = 0;
			foreach (Wallpaper candidate in candidates)
			{
				if (!queueState.Members.Contains(candidate.Id))
				{
					int num2 = Copies(candidate.Weight);
					queueState.Length += AddToQueue(queueState.Queue, candidate.Id, num2);
					queueState.Members.Add(candidate.Id);
					queueState.Assigned[candidate.Id] = num2;
					queueState.AssignedWeight[candidate.Id] = candidate.Weight;
					queueState.Played[candidate.Id] = 0;
					num++;
				}
			}
			if (num > 0)
			{
				Log.Log("本轮中途发现 " + num + " 张新壁纸，已插入当前轮");
			}
			if (Cfg.RebalanceMidCycle)
			{
				foreach (Wallpaper c2 in candidates)
				{
					if (queueState.AssignedWeight.TryGetValue(c2.Id, out var value) && value != c2.Weight)
					{
						int num3 = Copies(c2.Weight);
						int num4 = (queueState.Played.ContainsKey(c2.Id) ? queueState.Played[c2.Id] : 0);
						int num5 = queueState.Queue.Count((string x) => x == c2.Id);
						int num6 = Math.Max(0, num3 - num4);
						int num7 = num6 - num5;
						if (num7 < 0)
						{
							queueState.Length -= RemoveFromQueue(queueState.Queue, c2.Id, -num7);
						}
						else if (num7 > 0)
						{
							queueState.Length += AddToQueue(queueState.Queue, c2.Id, num7);
						}
						int num8 = (queueState.Assigned.ContainsKey(c2.Id) ? queueState.Assigned[c2.Id] : 0);
						Log.Log($"档位变化 [{c2.Id}] {c2.Title}：每轮 {num8} -> {num3}，已播 {num4}，本轮剩余 {num5} -> {num6}");
						queueState.Assigned[c2.Id] = num3;
						queueState.AssignedWeight[c2.Id] = c2.Weight;
					}
				}
			}
			if (queueState.Queue.Count == 0)
			{
				queueState = null;
			}
		}
		if (queueState == null)
		{
			queueState = NewCycle(candidates);
			Log.Log("开始新的一轮：共 " + queueState.Length + " 次切换，覆盖 " + queueState.Members.Count + " 张壁纸");
		}
		return queueState;
	}

	private string PopNext(QueueState st, string lastId)
	{
		if (st.Queue.Count == 0)
		{
			return null;
		}
		int index = 0;
		if (st.Queue.Count > 1 && st.Queue[0] == lastId)
		{
			for (int i = 1; i < st.Queue.Count; i++)
			{
				if (st.Queue[i] != lastId)
				{
					index = i;
					break;
				}
			}
		}
		string text = st.Queue[index];
		st.Queue.RemoveAt(index);
		st.Played[text] = (st.Played.ContainsKey(text) ? st.Played[text] : 0) + 1;
		return text;
	}

	private Wallpaper SelectWeighted(List<Wallpaper> c)
	{
		double num = c.Sum((Wallpaper x) => x.Weight);
		if (num <= 0.0)
		{
			return null;
		}
		double num2 = _rng.NextDouble() * num;
		double num3 = 0.0;
		foreach (Wallpaper item in c)
		{
			num3 += item.Weight;
			if (num2 < num3)
			{
				return item;
			}
		}
		return c[c.Count - 1];
	}

	public string SwitchNow()
	{
		Paths paths = ResolvePaths();
		List<Wallpaper> list = Scan(paths);
		if (list.Count == 0)
		{
			Log.Log("创意工坊目录里没有找到壁纸");
			return null;
		}
		List<string> list2 = LoadHistory();
		string lastId = ((list2.Count <= 0) ? "" : list2[list2.Count - 1]);
		List<Wallpaper> list3 = list.Where((Wallpaper w) => w.Weight > 0.0).ToList();
		bool flag = Cfg.Mode == "random";
		if (flag)
		{
			List<string> recent = list2.Skip(Math.Max(0, list2.Count - Cfg.AvoidRecentCount)).ToList();
			List<Wallpaper> list4 = list3.Where((Wallpaper w) => !recent.Contains(w.Id)).ToList();
			if (list4.Count > 0)
			{
				list3 = list4;
			}
		}
		if (list3.Count == 0)
		{
			Log.Log("没有可播放的壁纸（权重都是 0？）");
			return null;
		}
		Dictionary<string, Wallpaper> dictionary = list3.ToDictionary((Wallpaper c) => c.Id);
		bool flag2 = Cfg.Mode == "grouped";
		QueueState queueState = (flag ? null : ((!flag2) ? GetQueue(list3) : GetGroupedQueue(list3)));
		List<int> list5 = Cfg.MonitorList(_pf.MonitorCount());
		Wallpaper chosen = null;
		string text = null;
		foreach (int item in list5)
		{
			if (chosen == null || !Cfg.SameOnAllMonitors)
			{
				if (flag)
				{
					chosen = SelectWeighted(list3);
					if (chosen != null && !Cfg.SameOnAllMonitors)
					{
						list3 = list3.Where((Wallpaper x) => x.Id != chosen.Id).ToList();
					}
				}
				else
				{
					string text2 = ((!flag2) ? PopNext(queueState, lastId) : PopNextGrouped(queueState, lastId));
					chosen = ((text2 == null || !dictionary.ContainsKey(text2)) ? null : dictionary[text2]);
				}
				if (chosen == null)
				{
					break;
				}
			}
			_pf.OpenWallpaper(paths.Exe, chosen.Project, item);
			string text3 = ((!flag) ? ("本轮剩余 " + queueState.Remaining + "/" + queueState.Length) : ("权重 " + Fmt(chosen.Weight)));
			Log.Log($"显示器 {item} -> [{chosen.Id}] {chosen.Title}  (添加于 {chosen.Added:yyyy-MM-dd}, {chosen.Tier}, {text3})");
			list2.Remove(chosen.Id);
			list2.Add(chosen.Id);
			lastId = chosen.Id;
			if (text == null)
			{
				text = chosen.Title;
			}
		}
		int num = Math.Max(Cfg.AvoidRecentCount, 1) * 2;
		if (list2.Count > num)
		{
			list2 = list2.Skip(list2.Count - num).ToList();
		}
		SaveHistory(list2);
		if (queueState != null)
		{
			WriteQueue(queueState);
		}
		if (text != null)
		{
			LastTitle = text;
		}
		return text;
	}

	public Preview BuildPreview()
	{
		Preview preview = new Preview();
		try
		{
			preview.Paths = ResolvePaths();
			preview.All = (from w in Scan(preview.Paths)
				orderby w.AgeDays
				select w).ToList();
			List<Wallpaper> source = preview.All.Where((Wallpaper w) => w.Weight > 0.0).ToList();
			preview.CycleLength = (int)Math.Round(source.Sum((Wallpaper w) => w.Weight));
			preview.EstimatedDays = (double)(preview.CycleLength * Cfg.IntervalMinutes) / 60.0 / 8.0;
			preview.Queue = ReadQueue();
		}
		catch (Exception ex)
		{
			preview.Error = ex.Message;
		}
		return preview;
	}
}
