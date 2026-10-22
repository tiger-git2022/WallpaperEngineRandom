using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Win32;

namespace WERandom;

public class WindowsPlatform : IPlatform
{
	private struct LASTINPUTINFO
	{
		public uint cbSize;

		public uint dwTime;
	}

	private struct RECT
	{
		public int Left;

		public int Top;

		public int Right;

		public int Bottom;
	}

	private struct MONITORINFO
	{
		public int cbSize;

		public RECT rcMonitor;

		public RECT rcWork;

		public uint dwFlags;
	}

	public static string LastFullscreenInfo = "";

	public bool IsWallpaperEngineRunning()
	{
		try
		{
			return Process.GetProcessesByName("wallpaper64").Length > 0 || Process.GetProcessesByName("wallpaper32").Length > 0;
		}
		catch
		{
			return false;
		}
	}

	public int MonitorCount()
	{
		try
		{
			return Screen.AllScreens.Length;
		}
		catch
		{
			return 1;
		}
	}

	public void OpenWallpaper(string exe, string projectJson, int monitor)
	{
		ProcessStartInfo processStartInfo = new ProcessStartInfo();
		processStartInfo.FileName = exe;
		processStartInfo.Arguments = "-control openWallpaper -file \"" + projectJson + "\" -monitor " + monitor;
		processStartInfo.UseShellExecute = false;
		processStartInfo.CreateNoWindow = true;
		processStartInfo.WorkingDirectory = Path.GetDirectoryName(exe);
		ProcessStartInfo startInfo = processStartInfo;
		using Process process = Process.Start(startInfo);
		process?.WaitForExit(15000);
	}

	public List<string> SteamLibraries()
	{
		List<string> list = new List<string>();
		string text = null;
		try
		{
			using RegistryKey registryKey = Registry.CurrentUser.OpenSubKey("Software\\Valve\\Steam");
			if (registryKey != null)
			{
				text = registryKey.GetValue("SteamPath") as string;
			}
		}
		catch
		{
		}
		if (string.IsNullOrEmpty(text))
		{
			string[] array = new string[4]
			{
				Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Steam"),
				Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Steam"),
				"C:\\Steam",
				"D:\\Steam"
			};
			foreach (string text2 in array)
			{
				if (Directory.Exists(text2))
				{
					text = text2;
					break;
				}
			}
		}
		if (string.IsNullOrEmpty(text))
		{
			return list;
		}
		text = text.Replace('/', '\\');
		list.Add(text);
		string path = Path.Combine(text, "steamapps", "libraryfolders.vdf");
		if (File.Exists(path))
		{
			try
			{
				foreach (Match item2 in Regex.Matches(File.ReadAllText(path), "\"path\"\\s+\"([^\"]+)\""))
				{
					string item = item2.Groups[1].Value.Replace("\\\\", "\\");
					if (!list.Contains(item))
					{
						list.Add(item);
					}
				}
			}
			catch
			{
			}
		}
		return list;
	}

	public DateTime Now()
	{
		return DateTime.Now;
	}

	[DllImport("user32.dll")]
	private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

	[DllImport("user32.dll")]
	private static extern IntPtr GetForegroundWindow();

	[DllImport("user32.dll")]
	private static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);

	[DllImport("user32.dll")]
	private static extern IntPtr MonitorFromWindow(IntPtr hWnd, uint flags);

	[DllImport("user32.dll", CharSet = CharSet.Unicode)]
	private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO info);

	[DllImport("user32.dll", CharSet = CharSet.Unicode)]
	private static extern int GetClassName(IntPtr hWnd, StringBuilder sb, int max);

	[DllImport("user32.dll")]
	private static extern IntPtr GetShellWindow();

	[DllImport("user32.dll")]
	private static extern IntPtr GetDesktopWindow();

	[DllImport("user32.dll")]
	private static extern int GetWindowLong(IntPtr hWnd, int index);

	[DllImport("user32.dll", CharSet = CharSet.Unicode)]
	private static extern int GetWindowText(IntPtr hWnd, StringBuilder sb, int max);

	public static bool IsFullscreenAppInForeground()
	{
		try
		{
			IntPtr foregroundWindow = GetForegroundWindow();
			if (foregroundWindow == IntPtr.Zero || foregroundWindow == GetShellWindow() || foregroundWindow == GetDesktopWindow())
			{
				return false;
			}
			StringBuilder stringBuilder = new StringBuilder(256);
			GetClassName(foregroundWindow, stringBuilder, 256);
			string text = stringBuilder.ToString();
			switch (text)
			{
			case "Progman":
			case "WorkerW":
			case "Shell_TrayWnd":
			case "Shell_SecondaryTrayWnd":
				return false;
			default:
			{
				int windowLong = GetWindowLong(foregroundWindow, -16);
				if ((windowLong & 0xC00000) == 12582912)
				{
					return false;
				}
				int windowLong2 = GetWindowLong(foregroundWindow, -20);
				if (((uint)windowLong2 & 0x80u) != 0)
				{
					return false;
				}
				if (!GetWindowRect(foregroundWindow, out var rect))
				{
					return false;
				}
				MONITORINFO info = default(MONITORINFO);
				info.cbSize = Marshal.SizeOf(info);
				if (!GetMonitorInfo(MonitorFromWindow(foregroundWindow, 2u), ref info))
				{
					return false;
				}
				bool flag = rect.Left <= info.rcMonitor.Left && rect.Top <= info.rcMonitor.Top && rect.Right >= info.rcMonitor.Right && rect.Bottom >= info.rcMonitor.Bottom;
				if (flag)
				{
					StringBuilder stringBuilder2 = new StringBuilder(128);
					GetWindowText(foregroundWindow, stringBuilder2, 128);
					LastFullscreenInfo = "[" + text + "] " + stringBuilder2;
				}
				return flag;
			}
			}
		}
		catch
		{
			return false;
		}
	}

	public static TimeSpan IdleTime()
	{
		try
		{
			LASTINPUTINFO plii = default(LASTINPUTINFO);
			plii.cbSize = (uint)Marshal.SizeOf(plii);
			if (!GetLastInputInfo(ref plii))
			{
				return TimeSpan.Zero;
			}
			uint num = (uint)Environment.TickCount - plii.dwTime;
			return TimeSpan.FromMilliseconds(num);
		}
		catch
		{
			return TimeSpan.Zero;
		}
	}
}
