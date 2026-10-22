using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace WERandom;

/// <summary>
/// Keeps the tablet's portrait layout table (wer_portrait.json in the program folder) up to date and
/// hands it out on the LAN.
///
/// * Analysis: the Python script android_patch\transcode\seg_align.py (anime character segmentation)
///   is run with --incremental over the workshop folders, two minutes after start and then every
///   30 minutes, below-normal priority, only when there are wallpapers missing from the table.
/// * Sharing: HTTP on TCP 48712 serves GET /wer_portrait.json; UDP 48713 answers the tablet's
///   "WERANDOM_DISCOVER" broadcast with "WERANDOM 48712" so the tablet finds the PC by itself.
///   The tablet only fetches when the user taps "sync" there.
/// * 30 fps: Wallpaper Engine sends videos at their own resolution untouched (its fps option only works
///   together with scaling down). When the tablet syncs it POSTs the video entries of its packages to
///   /fps30/check; android_patch\transcode\fps30_cache.py then converts the ones that are still the
///   original above 30 fps into fps30\ (index.json, status.json, <id>.mp4), and the tablet downloads
///   them from /fps30/... and swaps them into its packages.
/// </summary>
public sealed class PortraitService
{
	public const int HttpPort = 48712;
	public const int DiscoveryPort = 48713;
	private const string TableName = "wer_portrait.json";
	private static readonly TimeSpan FirstRun = TimeSpan.FromMinutes(2);
	private static readonly TimeSpan Period = TimeSpan.FromMinutes(30);

	private readonly Engine _engine;
	private readonly object _lock = new object();
	private Timer _timer;
	private bool _running;
	private bool _transcoding;
	private bool _transcodeAgain;
	private string _state = "";
	private string _fpsState = "";

	public event Action StateChanged;

	public PortraitService(Engine engine)
	{
		_engine = engine;
	}

	public string TablePath => Path.Combine(_engine.Dir, TableName);

	/// <summary>Python tools folder: tools\transcode next to the exe (release layout), else the older android_patch\transcode.</summary>
	public string ToolsDir
	{
		get
		{
			string tools = Path.Combine(_engine.Dir, "tools", "transcode");
			return Directory.Exists(tools) ? tools : Path.Combine(_engine.Dir, "android_patch", "transcode");
		}
	}

	public string ScriptPath => Path.Combine(ToolsDir, "seg_align.py");

	public string Fps30Dir => Path.Combine(_engine.Dir, "fps30");

	public string Fps30Script => Path.Combine(ToolsDir, "fps30_cache.py");

	private string Fps30Request => Path.Combine(Fps30Dir, "request.json");

	/// <summary>Status of the 30 fps conversion for the tray menu; empty until the tablet asked once.</summary>
	public string Fps30State
	{
		get
		{
			lock (_lock)
			{
				return _fpsState;
			}
		}
	}

	/// <summary>Runs the conversion for the last tablet request; a request during a run queues one more run.</summary>
	private void TranscodeForTablet()
	{
		lock (_lock)
		{
			if (_transcoding)
			{
				_transcodeAgain = true;
				return;
			}
			_transcoding = true;
		}
		StartThread(delegate
		{
			while (true)
			{
				try
				{
					Transcode();
				}
				catch (Exception ex)
				{
					SetFpsState("30 帧转码失败：" + ex.Message);
					_engine.Log.Log("30 帧转码失败：" + ex.Message);
				}
				lock (_lock)
				{
					if (!_transcodeAgain)
					{
						_transcoding = false;
						return;
					}
					_transcodeAgain = false;
				}
			}
		}, "fps30-transcode");
	}

	private void SetFpsState(string text)
	{
		lock (_lock)
		{
			_fpsState = text;
		}
		StateChanged?.Invoke();
	}

	private void Transcode()
	{
		if (!File.Exists(Fps30Script))
		{
			SetFpsState("30 帧转码脚本缺失");
			return;
		}
		SetFpsState("30 帧转码：正在核对平板上的壁纸…");
		StringBuilder args = new StringBuilder("-X utf8 \"" + Fps30Script + "\"");
		foreach (string w in _engine.ResolvePaths().Workshop)
		{
			args.Append(" --workshop \"" + w + "\"");
		}
		args.Append(" --out \"" + Fps30Dir + "\" --request \"" + Fps30Request + "\" --encoder nvenc");
		ProcessStartInfo info = new ProcessStartInfo("python", args.ToString())
		{
			UseShellExecute = false,
			CreateNoWindow = true,
			RedirectStandardOutput = true,
			RedirectStandardError = true,
			StandardOutputEncoding = Encoding.UTF8,
			StandardErrorEncoding = Encoding.UTF8,
			WorkingDirectory = Path.GetDirectoryName(Fps30Script)
		};
		info.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";
		using Process process = Process.Start(info);
		try
		{
			process.PriorityClass = ProcessPriorityClass.BelowNormal;
		}
		catch
		{
		}
		process.ErrorDataReceived += delegate { };
		process.BeginErrorReadLine();
		string last = "";
		string line;
		while ((line = process.StandardOutput.ReadLine()) != null)
		{
			line = line.Trim();
			if (line.Length == 0)
			{
				continue;
			}
			last = line;
			if (line.StartsWith("to check:", StringComparison.Ordinal))
			{
				SetFpsState("30 帧转码：平板上待处理 " + line.Substring(9).Trim() + " 张");
			}
			else if (char.IsDigit(line[0]) && line.Contains("/"))
			{
				SetFpsState("30 帧转码：" + line);
			}
		}
		process.WaitForExit();
		string summary = process.ExitCode == 0 ? last : "出错（" + process.ExitCode + "）";
		SetFpsState("30 帧转码：" + summary);
		_engine.Log.Log("30 帧转码：" + summary);
	}

	/// <summary>Short status for the tray menu.</summary>
	public string State
	{
		get
		{
			lock (_lock)
			{
				return _state;
			}
		}
	}

	public void Start()
	{
		UpdateState(null);
		_timer = new Timer(delegate { AnalyseNow(); }, null, FirstRun, Period);
		StartThread(ServeHttp, "portrait-http");
		StartThread(ServeDiscovery, "portrait-discovery");
	}

	private static void StartThread(ThreadStart body, string name)
	{
		new Thread(body)
		{
			IsBackground = true,
			Name = name,
			Priority = ThreadPriority.BelowNormal
		}.Start();
	}

	private bool AnalysisRunning
	{
		get
		{
			lock (_lock)
			{
				return _running;
			}
		}
	}

	/// <summary>Runs the incremental analysis unless one is already running. Safe from any thread.</summary>
	public void AnalyseNow()
	{
		lock (_lock)
		{
			if (_running)
			{
				return;
			}
			_running = true;
		}
		StartThread(delegate
		{
			try
			{
				Analyse();
			}
			catch (Exception ex)
			{
				_engine.Log.Log("人物对齐分析失败：" + ex.Message);
			}
			finally
			{
				lock (_lock)
				{
					_running = false;
				}
				UpdateState(null);
			}
		}, "portrait-analysis");
	}

	private void Analyse()
	{
		if (!File.Exists(ScriptPath))
		{
			UpdateState("分析脚本缺失");
			return;
		}
		List<string> workshop = _engine.ResolvePaths().Workshop;
		int missing = CountMissing(workshop);
		if (missing == 0)
		{
			UpdateState(null);
			return;
		}
		UpdateState("正在分析 " + missing + " 张新壁纸");
		_engine.Log.Log("人物对齐分析：" + missing + " 张新壁纸");
		StringBuilder args = new StringBuilder("-X utf8 \"" + ScriptPath + "\"");
		foreach (string w in workshop)
		{
			args.Append(" --workshop \"" + w + "\"");
		}
		args.Append(" --incremental --out \"" + TablePath + "\"");
		ProcessStartInfo info = new ProcessStartInfo("python", args.ToString())
		{
			UseShellExecute = false,
			CreateNoWindow = true,
			RedirectStandardOutput = true,
			RedirectStandardError = true,
			StandardOutputEncoding = Encoding.UTF8,
			StandardErrorEncoding = Encoding.UTF8,
			WorkingDirectory = Path.GetDirectoryName(ScriptPath)
		};
		info.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";
		using Process process = Process.Start(info);
		try
		{
			process.PriorityClass = ProcessPriorityClass.BelowNormal;
		}
		catch
		{
		}
		string output = process.StandardOutput.ReadToEnd();
		string error = process.StandardError.ReadToEnd();
		process.WaitForExit();
		string last = output.Trim().Split('\n').LastOrDefault() ?? "";
		_engine.Log.Log(process.ExitCode == 0
			? "人物对齐分析完成：" + last.Trim()
			: "人物对齐分析出错（" + process.ExitCode + "）：" + error.Trim().Split('\n').LastOrDefault());
	}

	/// <summary>Workshop items without an entry in the table.</summary>
	private int CountMissing(List<string> workshop)
	{
		HashSet<string> known = KnownIds();
		int missing = 0;
		foreach (string root in workshop)
		{
			if (!Directory.Exists(root))
			{
				continue;
			}
			foreach (string dir in Directory.GetDirectories(root))
			{
				if (File.Exists(Path.Combine(dir, "project.json")) && !known.Contains(Path.GetFileName(dir) + ".mpkg"))
				{
					missing++;
				}
			}
		}
		return missing;
	}

	private HashSet<string> KnownIds()
	{
		HashSet<string> ids = new HashSet<string>();
		try
		{
			if (File.Exists(TablePath))
			{
				// keys are the only quoted strings ending in .mpkg
				string text = File.ReadAllText(TablePath, Encoding.UTF8);
				int at = 0;
				while ((at = text.IndexOf(".mpkg\"", at, StringComparison.Ordinal)) >= 0)
				{
					int start = text.LastIndexOf('"', at) + 1;
					ids.Add(text.Substring(start, at + 5 - start));
					at += 6;
				}
			}
		}
		catch
		{
		}
		return ids;
	}

	private void UpdateState(string busy)
	{
		string text = busy ?? ("人物对齐数据：" + KnownIds().Count + " 张");
		lock (_lock)
		{
			_state = text;
		}
		StateChanged?.Invoke();
	}

	/// <summary>Minimal HTTP/1.0 server for the table; everything else is 404.</summary>
	private void ServeHttp()
	{
		TcpListener listener;
		try
		{
			listener = new TcpListener(IPAddress.Any, HttpPort);
			listener.Start();
		}
		catch (Exception ex)
		{
			_engine.Log.Log("人物对齐数据服务启动失败（端口 " + HttpPort + "）：" + ex.Message);
			return;
		}
		while (true)
		{
			try
			{
				using TcpClient client = listener.AcceptTcpClient();
				client.ReceiveTimeout = 5000;
				client.SendTimeout = 30000;
				using NetworkStream stream = client.GetStream();
				byte[] posted;
				string request = ReadRequest(stream, out posted);
				if (request.StartsWith("GET /analyse", StringComparison.Ordinal))
				{
					// the tablet's sync: analyse wallpapers missing from the table first, then it downloads the table
					AnalyseNow();
					DateTime until = DateTime.Now.AddMinutes(10);
					while (AnalysisRunning && DateTime.Now < until)
					{
						Thread.Sleep(500);
					}
					Reply(stream, "200 OK", "application/json", new MemoryStream(Encoding.ASCII.GetBytes("{\"ok\": true}")));
					continue;
				}
				if (request.StartsWith("POST /fps30/check ", StringComparison.Ordinal))
				{
					Directory.CreateDirectory(Fps30Dir);
					File.WriteAllBytes(Fps30Request, posted);
					File.WriteAllText(Path.Combine(Fps30Dir, "status.json"), "{\"state\": \"queued\", \"total\": 0, \"done\": 0}");
					TranscodeForTablet();
					Reply(stream, "200 OK", "application/json", new MemoryStream(Encoding.ASCII.GetBytes("{\"ok\": true}")));
					continue;
				}
				string path = RequestedFile(request);
				bool wanted = path != null && File.Exists(path);
				string type = path != null && path.EndsWith(".mp4", StringComparison.OrdinalIgnoreCase) ? "video/mp4"
					: "application/json; charset=utf-8";
				using (Stream body = wanted ? (Stream)File.OpenRead(path) : new MemoryStream(Encoding.ASCII.GetBytes("not found")))
				{
					Reply(stream, wanted ? "200 OK" : "404 Not Found", wanted ? type : "text/plain", body);
				}
				if (wanted && path == TablePath)
				{
					_engine.Log.Log("平板同步人物对齐数据：" + ((IPEndPoint)client.Client.RemoteEndPoint).Address);
				}
			}
			catch
			{
			}
		}
	}

	/// <summary>Request line + headers (ASCII), and the body when there is a Content-Length (max 8 MB).</summary>
	private static string ReadRequest(NetworkStream stream, out byte[] body)
	{
		MemoryStream all = new MemoryStream();
		byte[] buffer = new byte[16384];
		int headEnd = -1;
		while (headEnd < 0 && all.Length < 65536)
		{
			int n = stream.Read(buffer, 0, buffer.Length);
			if (n <= 0)
			{
				break;
			}
			all.Write(buffer, 0, n);
			byte[] data = all.GetBuffer();
			for (int i = 0; i + 3 < all.Length; i++)
			{
				if (data[i] == 13 && data[i + 1] == 10 && data[i + 2] == 13 && data[i + 3] == 10)
				{
					headEnd = i;
					break;
				}
			}
		}
		byte[] raw = all.ToArray();
		if (headEnd < 0)
		{
			body = new byte[0];
			return Encoding.ASCII.GetString(raw);
		}
		string head = Encoding.ASCII.GetString(raw, 0, headEnd);
		int length = 0;
		foreach (string h in head.Split('\n'))
		{
			if (h.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
			{
				int.TryParse(h.Substring(15).Trim(), out length);
			}
		}
		length = Math.Max(0, Math.Min(length, 8 << 20));
		MemoryStream content = new MemoryStream();
		content.Write(raw, headEnd + 4, raw.Length - headEnd - 4);
		while (content.Length < length)
		{
			int n = stream.Read(buffer, 0, buffer.Length);
			if (n <= 0)
			{
				break;
			}
			content.Write(buffer, 0, n);
		}
		body = content.ToArray();
		return head;
	}

	private static void Reply(NetworkStream stream, string status, string type, Stream body)
	{
		string head = "HTTP/1.0 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.Length
			+ "\r\nConnection: close\r\n\r\n";
		byte[] headBytes = Encoding.ASCII.GetBytes(head);
		stream.Write(headBytes, 0, headBytes.Length);
		body.CopyTo(stream, 1 << 16);
	}

	/// <summary>
	/// GET /wer_portrait.json, /fps30/index.json, /fps30/status.json or /fps30/<id>.mp4 mapped to a file,
	/// else null. Only plain file names inside fps30\ are served.
	/// </summary>
	private string RequestedFile(string request)
	{
		if (!request.StartsWith("GET /", StringComparison.Ordinal))
		{
			return null;
		}
		int end = request.IndexOfAny(new[] { ' ', '\r', '\n' }, 5);
		string target = end < 0 ? request.Substring(5) : request.Substring(5, end - 5);
		if (target == TableName)
		{
			return TablePath;
		}
		if (!target.StartsWith("fps30/", StringComparison.Ordinal))
		{
			return null;
		}
		string name = target.Substring(6);
		if (name.Length == 0 || name.Contains("/") || name.Contains("\\") || name.Contains("..")
			|| !(name == "index.json" || name == "status.json" || name.EndsWith(".mp4", StringComparison.OrdinalIgnoreCase)))
		{
			return null;
		}
		return Path.Combine(Fps30Dir, name);
	}

	/// <summary>Answers "WERANDOM_DISCOVER" broadcasts with "WERANDOM &lt;http port&gt;".</summary>
	private void ServeDiscovery()
	{
		UdpClient udp;
		try
		{
			udp = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
		}
		catch (Exception ex)
		{
			_engine.Log.Log("局域网发现服务启动失败（端口 " + DiscoveryPort + "）：" + ex.Message);
			return;
		}
		byte[] reply = Encoding.ASCII.GetBytes("WERANDOM " + HttpPort);
		while (true)
		{
			try
			{
				IPEndPoint from = new IPEndPoint(IPAddress.Any, 0);
				byte[] data = udp.Receive(ref from);
				if (Encoding.ASCII.GetString(data).StartsWith("WERANDOM_DISCOVER", StringComparison.Ordinal))
				{
					udp.Send(reply, reply.Length, from);
				}
			}
			catch
			{
			}
		}
	}
}
