using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace WERandom;

/// <summary>
/// 通过 adb 读写平板 / 手机上打过补丁的 Wallpaper Engine 的壁纸库（每个壁纸一个 &lt;id&gt;.mpkg）。
/// 删除不是真删：挪到同一张卡上库旁边的 wer_trash 文件夹，挪回去就恢复。App 每次按文件夹里的实际文件重建列表。
/// </summary>
public static class Adb
{
	/// <summary>壁纸库可能在的位置：SD 卡上（平板）、共享存储（手机），最后是 App 自己的外部目录。</summary>
	private const string FindLibrary =
		"for d in /storage/*/WERandom/files/downloads /storage/emulated/0/WERandom/files/downloads /storage/*/Android/data/io.wallpaperengine.weclient/files/downloads /storage/emulated/0/Android/data/io.wallpaperengine.weclient/files/downloads; do [ -d \"$d\" ] && ls \"$d\" | grep -q '\\.mpkg$' && echo \"$d\"; done";

	public sealed class Device
	{
		public string Serial;

		public string Model;

		public string Library;

		public List<string> Ids = new List<string>();

		public string Error;
	}

	public static string Path()
	{
		var candidates = new List<string>
		{
			System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe")
		};
		string sdk = Environment.GetEnvironmentVariable("ANDROID_HOME") ?? Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
		if (!string.IsNullOrEmpty(sdk))
		{
			candidates.Add(System.IO.Path.Combine(sdk, "platform-tools", "adb.exe"));
		}
		foreach (string dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
		{
			if (dir.Trim().Length > 0)
			{
				candidates.Add(System.IO.Path.Combine(dir.Trim(), "adb.exe"));
			}
		}
		return candidates.FirstOrDefault(File.Exists);
	}

	public static async Task<(int code, string output)> RunAsync(string args, int timeoutMs = 30000)
	{
		string adb = Path() ?? throw new SteamException(0, "找不到 adb.exe（Android SDK 的 platform-tools）");
		var psi = new ProcessStartInfo(adb, args)
		{
			UseShellExecute = false,
			CreateNoWindow = true,
			RedirectStandardOutput = true,
			RedirectStandardError = true,
			StandardOutputEncoding = Encoding.UTF8,
			StandardErrorEncoding = Encoding.UTF8
		};
		using var p = Process.Start(psi);
		Task<string> o = p.StandardOutput.ReadToEndAsync(), e = p.StandardError.ReadToEndAsync();
		Task done = Task.Run(() => p.WaitForExit(timeoutMs));
		await done;
		if (!p.HasExited)
		{
			try
			{
				p.Kill();
			}
			catch
			{
			}
			throw new TimeoutException("adb 没有响应：" + args);
		}
		return (p.ExitCode, (await o) + (await e));
	}

	/// <summary>adb shell 里跑一条命令（整条命令作为一个参数传给设备上的 sh）。</summary>
	public static Task<(int code, string output)> ShellAsync(string serial, string command, int timeoutMs = 30000)
	{
		return RunAsync("-s " + serial + " shell " + Quote(command), timeoutMs);
	}

	/// <summary>Windows 命令行参数的引号规则：反斜杠只在引号前面才要翻倍。</summary>
	public static string Quote(string s)
	{
		var sb = new StringBuilder("\"");
		int slashes = 0;
		foreach (char c in s)
		{
			if (c == '\\')
			{
				slashes++;
				continue;
			}
			if (c == '"')
			{
				sb.Append('\\', slashes * 2 + 1);
			}
			else
			{
				sb.Append('\\', slashes);
			}
			slashes = 0;
			sb.Append(c);
		}
		sb.Append('\\', slashes * 2);
		return sb.Append('"').ToString();
	}

	/// <summary>设备上找壁纸库的命令（给测试用）。</summary>
	public static string FindLibraryCommand => FindLibrary;

	/// <summary>无线 adb：连接 ip:端口（设备重启后要在设备上重新开）。</summary>
	public static async Task<string> ConnectAsync(string address)
	{
		if (!Regex.IsMatch(address ?? "", "^[\\w.\\-]+(:\\d{1,5})?$"))
		{
			throw new ArgumentException("地址格式不对");
		}
		(int _, string output) = await RunAsync("connect " + address, 15000);
		return output.Trim();
	}

	public static async Task<List<Device>> DevicesAsync()
	{
		(int _, string output) = await RunAsync("devices -l", 15000);
		var list = new List<Device>();
		foreach (string line in output.Split('\n').Skip(1))
		{
			Match m = Regex.Match(line.Trim(), "^(\\S+)\\s+device\\b(.*)$");
			if (!m.Success)
			{
				continue;
			}
			var d = new Device { Serial = m.Groups[1].Value };
			Match model = Regex.Match(m.Groups[2].Value, "model:(\\S+)");
			d.Model = model.Success ? model.Groups[1].Value.Replace('_', ' ') : d.Serial;
			list.Add(d);
		}
		// 同一台设备既有 USB 又有无线连接时只留一个
		var seen = new HashSet<string>();
		var result = new List<Device>();
		foreach (Device d in list.OrderBy(x => x.Serial.Contains(":") ? 1 : 0))
		{
			try
			{
				(int _, string sn) = await ShellAsync(d.Serial, "getprop ro.serialno");
				if (!seen.Add(sn.Trim()))
				{
					continue;
				}
				(int _, string dirs) = await ShellAsync(d.Serial, FindLibrary);
				d.Library = dirs.Split('\n').Select(x => x.Trim()).FirstOrDefault(x => x.StartsWith("/"));
				if (d.Library == null)
				{
					d.Error = "没找到 Wallpaper Engine 的壁纸库";
				}
				else
				{
					(int _, string files) = await ShellAsync(d.Serial, "ls " + d.Library);
					d.Ids = files.Split('\n').Select(x => x.Trim()).Select(x => Regex.Match(x, "^(\\d+)\\.mpkg$")).Where(x => x.Success).Select(x => x.Groups[1].Value).ToList();
				}
			}
			catch (Exception ex)
			{
				d.Error = ex.Message;
			}
			result.Add(d);
		}
		return result;
	}

	/// <summary>把壁纸挪到库旁边的 wer_trash（同一张卡，瞬间完成，可恢复）。返回挪走了的 id。</summary>
	public static async Task<List<string>> TrashAsync(string serial, string library, IEnumerable<string> ids)
	{
		if (!Regex.IsMatch(library ?? "", "^/storage/[\\w./\\-]+/downloads$"))
		{
			throw new ArgumentException("壁纸库路径不对：" + library);
		}
		string trash = library.Substring(0, library.LastIndexOf('/')) + "/wer_trash";
		var moved = new List<string>();
		foreach (string id in ids)
		{
			if (!Regex.IsMatch(id ?? "", "^\\d{1,20}$"))
			{
				continue;
			}
			(int code, string output) = await ShellAsync(serial, "mkdir -p " + trash + " && mv -f " + library + "/" + id + ".mpkg " + trash + "/" + id + ".mpkg && echo OK");
			if (code == 0 && output.Contains("OK"))
			{
				moved.Add(id);
			}
		}
		return moved;
	}
}
