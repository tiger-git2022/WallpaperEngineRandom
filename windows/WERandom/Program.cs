using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace WERandom;

public static class Program
{
	[DllImport("user32.dll")]
	private static extern bool SetProcessDPIAware();

	[STAThread]
	public static void Main(string[] args)
	{
		try
		{
			if (Environment.OSVersion.Version.Major >= 6)
			{
				SetProcessDPIAware();
			}
		}
		catch
		{
		}
		bool createdNew;
		using Mutex obj2 = new Mutex(initiallyOwned: true, "WERandom_SingleInstance_" + Environment.UserName, out createdNew);
		Application.EnableVisualStyles();
		Application.SetCompatibleTextRenderingDefault(defaultValue: false);
		if (!createdNew)
		{
			MessageBox.Show("程序已经在运行了，请看屏幕右下角的托盘图标（可能藏在 ^ 里）。", "WE 加权轮播", MessageBoxButtons.OK, MessageBoxIcon.Asterisk);
			return;
		}
		string directoryName = Path.GetDirectoryName(Application.ExecutablePath);
		Engine engine = new Engine(directoryName, new WindowsPlatform());
		Application.ThreadException += delegate(object s, ThreadExceptionEventArgs e)
		{
			try
			{
				engine.Log.Log("未处理异常：" + e.Exception);
			}
			catch
			{
			}
		};
		TrayApp trayApp = new TrayApp(engine);
		if (args.Contains("--show") || (!File.Exists(engine.QueuePath) && !File.Exists(engine.HistoryPath) && !args.Contains("--minimized")))
		{
			trayApp.ShowSettings();
		}
		if (args.Contains("--menu"))
		{
			// testing aid: opens the tray menu at the mouse a moment after start
			System.Windows.Forms.Timer t = new System.Windows.Forms.Timer { Interval = 3000 };
			t.Tick += delegate
			{
				t.Stop();
				trayApp.ShowTrayMenu();
			};
			t.Start();
		}
		Application.Run(trayApp);
		GC.KeepAlive(obj2);
	}
}
