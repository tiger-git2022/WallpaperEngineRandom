using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;

namespace WERandom;

/// <summary>
/// 单文件发布：发布时第二次编译把第一次编译出的所有 dll（托管的和原生的）作为资源 lib/&lt;文件名&gt; 嵌进 exe（见 WERandom.csproj 的 EmbedFrom）。
/// 启动时解到 %LOCALAPPDATA%\WERandom\lib\&lt;这个 exe 的版本、大小和时间&gt;\，原生 dll 用 SetDllDirectory 找到，托管 dll 由 AssemblyResolve 加载。
/// 本地普通编译的 exe 里没有这些资源，什么都不做（dll 就在 exe 旁边）。
/// </summary>
public static class Bundle
{
	private const string Prefix = "lib/";

	[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
	private static extern bool SetDllDirectory(string path);

	private static readonly Dictionary<string, Assembly> Loaded = new Dictionary<string, Assembly>(StringComparer.OrdinalIgnoreCase);

	public static string LibDir { get; private set; }

	/// <summary>在 Main 的第一行调用，要在用到任何依赖（WPF-UI、WebView2、ONNX Runtime…）之前。</summary>
	public static void Init()
	{
		Assembly self = typeof(Bundle).Assembly;
		List<string> names = self.GetManifestResourceNames().Where(n => n.StartsWith(Prefix, StringComparison.Ordinal)).ToList();
		if (names.Count == 0)
		{
			return;
		}
		var exe = new FileInfo(self.Location);
		string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WERandom", "lib");
		string key = Version(self) + "-" + exe.Length + "-" + exe.LastWriteTimeUtc.Ticks;
		LibDir = Path.Combine(root, key);
		Directory.CreateDirectory(LibDir);
		foreach (string name in names)
		{
			string file = Path.Combine(LibDir, name.Substring(Prefix.Length));
			using Stream src = self.GetManifestResourceStream(name);
			if (File.Exists(file) && new FileInfo(file).Length == src.Length)
			{
				continue;
			}
			string tmp = file + "." + Guid.NewGuid().ToString("N") + ".tmp";
			using (FileStream dst = File.Create(tmp))
			{
				src.CopyTo(dst);
			}
			if (File.Exists(file))
			{
				File.Delete(file);
			}
			File.Move(tmp, file);
		}
		SetDllDirectory(LibDir);
		AppDomain.CurrentDomain.AssemblyResolve += Resolve;
		RemoveLegacyFiles(exe.DirectoryName, names.Select(n => n.Substring(Prefix.Length)));
		RemoveOldVersions(root, key);
	}

	private static Assembly Resolve(object sender, ResolveEventArgs e)
	{
		string name = new AssemblyName(e.Name).Name;
		lock (Loaded)
		{
			if (Loaded.TryGetValue(name, out Assembly a))
			{
				return a;
			}
			string file = Path.Combine(LibDir, name + ".dll");
			if (!File.Exists(file))
			{
				return null;
			}
			// 版本号不同也给（以前 exe.config 里的绑定重定向就是这么做的）
			a = Assembly.LoadFrom(file);
			Loaded[name] = a;
			return a;
		}
	}

	/// <summary>
	/// 以前的 zip 版在 exe 旁边放了一堆 dll 和 WERandom.exe.config。它们还在的话会先于嵌入的版本被加载，以后依赖升级时会冲突，
	/// 所以删掉（只删这个 exe 里嵌入了的同名文件；这时还没加载它们）。删不掉就算了。
	/// </summary>
	private static void RemoveLegacyFiles(string dir, IEnumerable<string> embedded)
	{
		var files = embedded.Select(n => Path.Combine(dir, n)).ToList();
		files.Add(Path.Combine(dir, "WERandom.exe.config"));
		files.Add(Path.Combine(dir, "onnxruntime.lib"));
		files.Add(Path.Combine(dir, "onnxruntime_providers_shared.lib"));
		files.Add(Path.Combine(dir, "runtimes", "win-x64", "native", "WebView2Loader.dll"));
		foreach (string f in files)
		{
			try
			{
				if (File.Exists(f))
				{
					File.Delete(f);
				}
			}
			catch
			{
			}
		}
		foreach (string d in new[] { Path.Combine(dir, "runtimes", "win-x64", "native"), Path.Combine(dir, "runtimes", "win-x64"), Path.Combine(dir, "runtimes") })
		{
			try
			{
				if (Directory.Exists(d) && !Directory.EnumerateFileSystemEntries(d).Any())
				{
					Directory.Delete(d);
				}
			}
			catch
			{
			}
		}
	}

	private static void RemoveOldVersions(string root, string keep)
	{
		try
		{
			foreach (string d in Directory.GetDirectories(root))
			{
				if (!string.Equals(Path.GetFileName(d), keep, StringComparison.OrdinalIgnoreCase))
				{
					try
					{
						Directory.Delete(d, true);
					}
					catch
					{
					}
				}
			}
		}
		catch
		{
		}
	}

	private static string Version(Assembly a)
	{
		string v = a.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? a.GetName().Version.ToString();
		return string.Concat(v.Select(c => char.IsLetterOrDigit(c) || c == '.' || c == '-' ? c : '_'));
	}
}
