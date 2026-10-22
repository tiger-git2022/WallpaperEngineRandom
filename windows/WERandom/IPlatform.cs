using System;
using System.Collections.Generic;

namespace WERandom;

public interface IPlatform
{
	bool IsWallpaperEngineRunning();

	int MonitorCount();

	void OpenWallpaper(string exe, string projectJson, int monitor);

	List<string> SteamLibraries();

	DateTime Now();
}
