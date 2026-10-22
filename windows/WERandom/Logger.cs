using System;
using System.IO;
using System.Linq;
using System.Text;

namespace WERandom;

public class Logger
{
	private readonly string _path;

	private readonly object _lock = new object();

	public event Action<string> OnLine;

	public Logger(string path)
	{
		_path = path;
	}

	public void Log(string msg)
	{
		string text = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + msg;
		lock (_lock)
		{
			try
			{
				File.AppendAllText(_path, text + "\r\n", Encoding.UTF8);
				FileInfo fileInfo = new FileInfo(_path);
				if (fileInfo.Length > 524288)
				{
					string[] array = File.ReadAllLines(_path, Encoding.UTF8);
					File.WriteAllLines(_path, array.Skip(Math.Max(0, array.Length - 1500)).ToArray(), Encoding.UTF8);
				}
			}
			catch
			{
			}
		}
		Action<string> onLine = this.OnLine;
		if (onLine != null)
		{
			try
			{
				onLine(text);
			}
			catch
			{
			}
		}
	}
}
