using System;
using System.Runtime.InteropServices;

namespace WERandom;

public static class Dwm
{
	[DllImport("dwmapi.dll")]
	private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

	public static void RoundCorners(IntPtr hwnd, bool small)
	{
		try
		{
			int value = ((!small) ? 2 : 3);
			DwmSetWindowAttribute(hwnd, 33, ref value, 4);
		}
		catch
		{
		}
	}
}
