using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Numerics;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace WERandom;

/// <summary>
/// 找内容相同的壁纸（同一张图、同一个人物换了姿势、不同作者做的同一个素材）：
/// 用图像模型（DINOv2）把每个壁纸的封面变成一个特征向量，GIF 封面取几帧各算一个；两个壁纸的相似度 = 各帧之间余弦相似度的最大值。
/// 模型第一次用时下载到 dupes\ 目录；算过的向量按封面文件的大小和修改时间缓存，以后只算新增的。
/// </summary>
public sealed class DupeFinder
{
	// Xenova/dinov2-small：输出 pooler_output（384 维）。比 CLIP 更看重“画面里是不是同一个东西”，不只是“同一类东西”。
	private const string ModelName = "dinov2-small";

	private static readonly string[] ModelUrls =
	{
		"https://huggingface.co/Xenova/dinov2-small/resolve/main/onnx/model.onnx",
		"https://hf-mirror.com/Xenova/dinov2-small/resolve/main/onnx/model.onnx"
	};

	private const long ModelBytes = 88459888;

	private const int Size = 224;

	private static readonly float[] Mean = { 0.485f, 0.456f, 0.406f };

	private static readonly float[] Std = { 0.229f, 0.224f, 0.225f };

	/// <summary>GIF 封面最多取几帧（均匀分布）。</summary>
	private const int Frames = 4;

	private readonly string _dir;

	private readonly Logger _log;

	private readonly Dictionary<string, Entry> _cache = new Dictionary<string, Entry>();

	private bool _cacheLoaded;

	private InferenceSession _session;

	public DupeFinder(string dataDir, Logger log)
	{
		_dir = Path.Combine(dataDir, "dupes");
		_log = log;
	}

	public string ModelPath => Path.Combine(_dir, ModelName + ".onnx");

	private string CachePath => Path.Combine(_dir, ModelName + ".vectors.bin");

	/// <summary>Steam 上下载来的封面（只在平板 / 手机上、电脑里没有的壁纸）。</summary>
	public string RemoteDir => Path.Combine(_dir, "previews");

	public sealed class Entry
	{
		public string Key;

		public string Stamp;

		public float[][] Vectors;

		/// <summary>每帧的颜色分布（HSV 12×4×4 格，和为 1）。</summary>
		public float[][] Hists;

		/// <summary>前几帧是封面，后面的是视频画面（比较时用来说明是哪种相似；不存进缓存）。</summary>
		public int CoverCount;
	}

	/// <summary>
	/// 要比较的一个壁纸：Key 是 id（电脑里的）或 "r:" + id（Steam 上下载的封面），Image 是封面文件。
	/// Video = true 时 Image 是视频文件，Key 是 "v:" + id：用 ffmpeg 截 4 帧画面。
	/// </summary>
	public sealed class Source
	{
		public string Key;

		public string Image;

		public bool Video;
	}

	public sealed class Pair
	{
		public int A;

		public int B;

		public float Score;

		/// <summary>只看封面没这么像，是视频画面像。</summary>
		public bool Video;
	}

	// ---- ffmpeg (video frames) ----

	private static string _ffmpeg;

	private static bool _ffmpegLooked;

	/// <summary>找 ffmpeg.exe：scoop 装的、PATH 里的、常见目录。找不到返回 null（那就只比封面和文件）。</summary>
	public static string Ffmpeg
	{
		get
		{
			if (_ffmpegLooked)
			{
				return _ffmpeg;
			}
			_ffmpegLooked = true;
			string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
			var dirs = new List<string> { Path.Combine(home, "scoop", "apps", "ffmpeg", "current", "bin") };
			dirs.AddRange((Environment.GetEnvironmentVariable("PATH") ?? "").Split(';').Where(d => d.Trim().Length > 0));
			dirs.Add(Path.Combine(home, "scoop", "shims"));
			dirs.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "ffmpeg", "bin"));
			dirs.Add(@"C:\ffmpeg\bin");
			foreach (string d in dirs)
			{
				try
				{
					string p = Path.Combine(d.Trim().Trim('"'), "ffmpeg.exe");
					if (File.Exists(p))
					{
						return _ffmpeg = p;
					}
				}
				catch
				{
				}
			}
			return null;
		}
	}

	/// <summary>运行 ffmpeg，返回标准输出（截的图）和错误输出（时长在里面）。超时 60 秒。</summary>
	private static byte[] RunFfmpeg(string args, out string err)
	{
		var info = new System.Diagnostics.ProcessStartInfo(Ffmpeg, args)
		{
			UseShellExecute = false,
			CreateNoWindow = true,
			RedirectStandardOutput = true,
			RedirectStandardError = true,
			StandardErrorEncoding = Encoding.UTF8
		};
		using var p = System.Diagnostics.Process.Start(info);
		try
		{
			p.PriorityClass = System.Diagnostics.ProcessPriorityClass.BelowNormal;
		}
		catch
		{
		}
		Task<string> errTask = p.StandardError.ReadToEndAsync();
		using var ms = new MemoryStream();
		Task copy = p.StandardOutput.BaseStream.CopyToAsync(ms);
		if (!p.WaitForExit(60000))
		{
			try
			{
				p.Kill();
			}
			catch
			{
			}
			err = "";
			return null;
		}
		copy.Wait(5000);
		err = errTask.Wait(5000) ? errTask.Result : "";
		return ms.ToArray();
	}

	/// <summary>视频在 15%、40%、65%、90% 处各截一帧（短边缩到 320），跳过纯色帧。</summary>
	private static List<(float[] px, float[] hist)> VideoFramePixels(string path)
	{
		if (Ffmpeg == null)
		{
			throw new IOException("没找到 ffmpeg");
		}
		RunFfmpeg("-hide_banner -i \"" + path + "\"", out string info);
		System.Text.RegularExpressions.Match m = System.Text.RegularExpressions.Regex.Match(info ?? "", @"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)");
		double dur = m.Success ? int.Parse(m.Groups[1].Value) * 3600 + int.Parse(m.Groups[2].Value) * 60 + double.Parse(m.Groups[3].Value, System.Globalization.CultureInfo.InvariantCulture) : 0;
		var list = new List<(float[] px, float[] hist)>();
		foreach (double f in new[] { 0.15, 0.40, 0.65, 0.90 })
		{
			string at = (dur * f).ToString("0.00", System.Globalization.CultureInfo.InvariantCulture);
			byte[] png = RunFfmpeg("-v error -ss " + at + " -i \"" + path + "\" -frames:v 1 -vf \"scale='if(gt(iw,ih),-2,320)':'if(gt(iw,ih),320,-2)'\" -f image2pipe -vcodec png -", out _);
			if (png == null || png.Length == 0)
			{
				continue;
			}
			using var ms = new MemoryStream(png);
			using Image img = Image.FromStream(ms);
			float[] px = Pixels(img, out float spread);
			if (spread >= 0.05f)
			{
				list.Add((px, Histogram(img)));
			}
		}
		return list;
	}

	// ---- same files ----

	private readonly Dictionary<string, (long len, long ticks, string hash)> _fileHashes = new Dictionary<string, (long, long, string)>(StringComparer.OrdinalIgnoreCase);

	private bool _fileHashesLoaded;

	private string FileHashPath => Path.Combine(_dir, "files.bin");

	/// <summary>
	/// 大文件的指纹（大小 + 开头 1 MB + 结尾 1 MB 的 MD5），按路径、大小、修改时间缓存。
	/// 两个壁纸有相同的大文件（视频本体、场景包、网页素材），多半是换了封面重新上传的同一个壁纸。
	/// </summary>
	public string FileHash(string path, FileInfo fi)
	{
		lock (_fileHashes)
		{
			if (!_fileHashesLoaded)
			{
				_fileHashesLoaded = true;
				LoadFileHashes();
			}
			if (_fileHashes.TryGetValue(path, out var c) && c.len == fi.Length && c.ticks == fi.LastWriteTimeUtc.Ticks)
			{
				return c.hash;
			}
		}
		string hash;
		using (var md5 = System.Security.Cryptography.MD5.Create())
		using (FileStream fs = File.OpenRead(path))
		{
			byte[] head = Encoding.ASCII.GetBytes(fi.Length.ToString());
			md5.TransformBlock(head, 0, head.Length, null, 0);
			var buf = new byte[1 << 20];
			int n = fs.Read(buf, 0, buf.Length);
			md5.TransformBlock(buf, 0, n, null, 0);
			if (fi.Length > 2 << 20)
			{
				fs.Seek(-(1 << 20), SeekOrigin.End);
				n = fs.Read(buf, 0, buf.Length);
				md5.TransformBlock(buf, 0, n, null, 0);
			}
			md5.TransformFinalBlock(new byte[0], 0, 0);
			hash = BitConverter.ToString(md5.Hash).Replace("-", "");
		}
		lock (_fileHashes)
		{
			_fileHashes[path] = (fi.Length, fi.LastWriteTimeUtc.Ticks, hash);
		}
		return hash;
	}

	private void LoadFileHashes()
	{
		try
		{
			if (!File.Exists(FileHashPath))
			{
				return;
			}
			using var r = new BinaryReader(File.OpenRead(FileHashPath), Encoding.UTF8);
			if (r.ReadString() != "WERFILES1")
			{
				return;
			}
			int count = r.ReadInt32();
			for (int i = 0; i < count; i++)
			{
				string p = r.ReadString();
				_fileHashes[p] = (r.ReadInt64(), r.ReadInt64(), r.ReadString());
			}
		}
		catch (Exception ex)
		{
			_fileHashes.Clear();
			_log.Log("查重：读文件指纹缓存失败，重新计算：" + ex.Message);
		}
	}

	public void SaveFileHashes()
	{
		try
		{
			Directory.CreateDirectory(_dir);
			string tmp = FileHashPath + ".tmp";
			lock (_fileHashes)
			{
				using var w = new BinaryWriter(File.Create(tmp), Encoding.UTF8);
				w.Write("WERFILES1");
				w.Write(_fileHashes.Count);
				foreach (KeyValuePair<string, (long len, long ticks, string hash)> kv in _fileHashes)
				{
					w.Write(kv.Key);
					w.Write(kv.Value.len);
					w.Write(kv.Value.ticks);
					w.Write(kv.Value.hash);
				}
			}
			if (File.Exists(FileHashPath))
			{
				File.Replace(tmp, FileHashPath, null);
			}
			else
			{
				File.Move(tmp, FileHashPath);
			}
		}
		catch (Exception ex)
		{
			_log.Log("查重：保存文件指纹缓存失败：" + ex.Message);
		}
	}

	/// <summary>
	/// 有相同大文件的组合：相同文件的大小占两者中较小那个的比例 ≥ minShare。一个文件被 7 个以上壁纸共用时不算（通用素材）。
	/// files：id → 它的大文件（指纹, 大小）。返回 (id, id, 比例)。
	/// </summary>
	public static List<(string a, string b, float share)> SameFiles(Dictionary<string, List<(string hash, long len)>> files, float minShare)
	{
		var owners = new Dictionary<string, (long len, List<string> ids)>();
		var total = new Dictionary<string, long>();
		foreach (KeyValuePair<string, List<(string hash, long len)>> kv in files)
		{
			total[kv.Key] = kv.Value.Sum(x => x.len);
			foreach ((string hash, long len) in kv.Value)
			{
				if (!owners.TryGetValue(hash, out var o))
				{
					owners[hash] = o = (len, new List<string>());
				}
				if (!o.ids.Contains(kv.Key))
				{
					o.ids.Add(kv.Key);
				}
			}
		}
		var common = new Dictionary<(string, string), long>();
		foreach ((long len, List<string> ids) in owners.Values)
		{
			if (ids.Count < 2 || ids.Count > 6)
			{
				continue;
			}
			for (int i = 0; i < ids.Count; i++)
			{
				for (int j = i + 1; j < ids.Count; j++)
				{
					var k = string.CompareOrdinal(ids[i], ids[j]) < 0 ? (ids[i], ids[j]) : (ids[j], ids[i]);
					common[k] = (common.TryGetValue(k, out long c) ? c : 0) + len;
				}
			}
		}
		var result = new List<(string, string, float)>();
		foreach (KeyValuePair<(string, string), long> kv in common)
		{
			float share = (float)kv.Value / Math.Max(1, Math.Min(total[kv.Key.Item1], total[kv.Key.Item2]));
			if (share >= minShare)
			{
				result.Add((kv.Key.Item1, kv.Key.Item2, Math.Min(1, share)));
			}
		}
		return result;
	}

	// ---- model ----

	public async Task EnsureModelAsync(HttpClient http, Action<string> progress, CancellationToken ct)
	{
		if (File.Exists(ModelPath) && new FileInfo(ModelPath).Length == ModelBytes)
		{
			return;
		}
		Directory.CreateDirectory(_dir);
		Exception last = null;
		foreach (string url in ModelUrls)
		{
			string part = ModelPath + ".part";
			try
			{
				using HttpResponseMessage resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
				resp.EnsureSuccessStatusCode();
				using (Stream src = await resp.Content.ReadAsStreamAsync())
				using (var dst = new FileStream(part, FileMode.Create, FileAccess.Write))
				{
					var buf = new byte[1 << 16];
					long got = 0, shown = 0;
					int n;
					while ((n = await src.ReadAsync(buf, 0, buf.Length, ct)) > 0)
					{
						await dst.WriteAsync(buf, 0, n, ct);
						got += n;
						if (got - shown > 2 << 20)
						{
							shown = got;
							progress("正在下载图像识别模型（只需要一次）：" + got * 100 / ModelBytes + "%");
						}
					}
				}
				if (new FileInfo(part).Length != ModelBytes)
				{
					throw new IOException("模型文件大小不对");
				}
				if (File.Exists(ModelPath))
				{
					File.Delete(ModelPath);
				}
				File.Move(part, ModelPath);
				_log.Log("查重：已下载模型 " + ModelName + "（" + url + "）");
				return;
			}
			catch (OperationCanceledException)
			{
				throw;
			}
			catch (Exception ex)
			{
				last = ex;
				_log.Log("查重：从 " + url + " 下载模型失败：" + ex.Message);
				try
				{
					File.Delete(part);
				}
				catch
				{
				}
			}
		}
		throw new IOException("下载图像识别模型失败：" + last?.Message);
	}

	private InferenceSession Session()
	{
		if (_session == null)
		{
			var opts = new SessionOptions { GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL };
			opts.IntraOpNumThreads = Math.Max(1, Environment.ProcessorCount / 2);
			_session = new InferenceSession(ModelPath, opts);
		}
		return _session;
	}

	// ---- vectors ----

	/// <summary>算出（或从缓存取）每个壁纸的向量。读不了的封面跳过（返回的列表里没有它）。</summary>
	public List<Entry> Embed(IList<Source> sources, Action<int, int> progress, CancellationToken ct)
	{
		LoadCache();
		var result = new List<Entry>();
		var todo = new List<(Source src, string stamp)>();
		foreach (Source s in sources)
		{
			string stamp = Stamp(s.Image);
			if (stamp == null)
			{
				continue;
			}
			if (_cache.TryGetValue(s.Key, out Entry e) && e.Stamp == stamp)
			{
				result.Add(e);
			}
			else
			{
				todo.Add((s, stamp));
			}
		}
		if (todo.Count == 0)
		{
			return result;
		}
		InferenceSession session = Session();
		string input = session.InputMetadata.Keys.First();
		string output = new[] { "pooler_output", "image_embeds" }.FirstOrDefault(session.OutputMetadata.ContainsKey) ?? session.OutputMetadata.Keys.First();
		const int batch = 16;
		var pendingFrames = new List<(int item, float[] pixels)>();
		var vectors = new List<float[]>[todo.Count];
		var hists = new List<float[]>[todo.Count];
		int done = 0, failed = 0;
		DateTime lastSave = DateTime.UtcNow;
		void Flush()
		{
			if (pendingFrames.Count == 0)
			{
				return;
			}
			var tensor = new DenseTensor<float>(new[] { pendingFrames.Count, 3, Size, Size });
			Span<float> buf = tensor.Buffer.Span;
			int stride = 3 * Size * Size;
			for (int i = 0; i < pendingFrames.Count; i++)
			{
				pendingFrames[i].pixels.AsSpan().CopyTo(buf.Slice(i * stride, stride));
			}
			using IDisposableReadOnlyCollection<DisposableNamedOnnxValue> res = session.Run(new[] { NamedOnnxValue.CreateFromTensor(input, tensor) });
			Tensor<float> outT = res.First(r => r.Name == output).AsTensor<float>();
			int dim = outT.Dimensions[outT.Dimensions.Length - 1];
			// last_hidden_state 形状是 [n, tokens, dim]，取第一个（CLS）
			int per = (int)(outT.Length / pendingFrames.Count);
			float[] flat = outT.ToArray();
			for (int i = 0; i < pendingFrames.Count; i++)
			{
				var v = new float[dim];
				Array.Copy(flat, i * per, v, 0, dim);
				Normalize(v);
				(vectors[pendingFrames[i].item] ??= new List<float[]>()).Add(v);
			}
			pendingFrames.Clear();
		}
		var finished = new List<int>();
		// pictures are read (and videos decoded by ffmpeg) several at a time; the model then takes them in batches
		const int chunk = 24;
		var read = new List<(float[] px, float[] hist)>[chunk];
		var errors = new Exception[chunk];
		var popts = new ParallelOptions { MaxDegreeOfParallelism = Math.Max(2, Math.Min(6, Environment.ProcessorCount / 2)), CancellationToken = ct };
		for (int k = 0; k < todo.Count; k++)
		{
			ct.ThrowIfCancellationRequested();
			int slot = k % chunk;
			if (slot == 0)
			{
				int start = k, end = Math.Min(todo.Count, k + chunk);
				Parallel.For(start, end, popts, i =>
				{
					try
					{
						read[i - start] = todo[i].src.Video ? VideoFramePixels(todo[i].src.Image) : FramePixels(todo[i].src.Image);
						errors[i - start] = null;
					}
					catch (Exception ex)
					{
						read[i - start] = null;
						errors[i - start] = ex;
					}
				});
			}
			if (errors[slot] != null)
			{
				failed++;
				if (failed <= 5)
				{
					_log.Log("查重：读不了" + (todo[k].src.Video ? "视频 " : "封面 ") + todo[k].src.Image + "：" + errors[slot].Message);
				}
			}
			else
			{
				foreach ((float[] px, float[] hist) in read[slot])
				{
					pendingFrames.Add((k, px));
					(hists[k] ??= new List<float[]>()).Add(hist);
				}
			}
			read[slot] = null;
			finished.Add(k);
			if (pendingFrames.Count >= batch || k == todo.Count - 1)
			{
				Flush();
				foreach (int f in finished)
				{
					if (vectors[f] != null)
					{
						var e = new Entry { Key = todo[f].src.Key, Stamp = todo[f].stamp, Vectors = vectors[f].ToArray(), Hists = hists[f].ToArray() };
						_cache[e.Key] = e;
						result.Add(e);
					}
					else if (todo[f].src.Video && Ffmpeg != null)
					{
						// no usable frame (all black, or ffmpeg can't read it): remember that, don't decode it every time
						_cache[todo[f].src.Key] = new Entry { Key = todo[f].src.Key, Stamp = todo[f].stamp, Vectors = new float[0][], Hists = new float[0][] };
					}
				}
				done += finished.Count;
				finished.Clear();
				progress(done, todo.Count);
				if ((DateTime.UtcNow - lastSave).TotalSeconds > 30)
				{
					SaveCache();
					lastSave = DateTime.UtcNow;
				}
			}
		}
		SaveCache();
		int videos = todo.Count(t => t.src.Video);
		_log.Log("查重：新算了 " + (done - failed) + " 个（封面 " + (todo.Count - videos) + "、视频 " + videos + "）" + (failed > 0 ? "，" + failed + " 个读不了" : ""));
		return result;
	}

	private static string Stamp(string path)
	{
		try
		{
			var fi = new FileInfo(path);
			return fi.Exists ? fi.Length + ":" + fi.LastWriteTimeUtc.Ticks : null;
		}
		catch
		{
			return null;
		}
	}

	private static void Normalize(float[] v)
	{
		double s = 0;
		foreach (float x in v)
		{
			s += x * x;
		}
		float inv = s > 0 ? (float)(1 / Math.Sqrt(s)) : 0;
		for (int i = 0; i < v.Length; i++)
		{
			v[i] *= inv;
		}
	}

	/// <summary>封面的几帧，每帧缩放到短边 224 再取中间的正方形，按模型要求归一化，排成 CHW。</summary>
	private static List<(float[] px, float[] hist)> FramePixels(string path)
	{
		using var ms = new MemoryStream(File.ReadAllBytes(path));
		using var img = Image.FromStream(ms);
		int count = 1;
		FrameDimension dim = null;
		try
		{
			if (img.FrameDimensionsList.Length > 0)
			{
				dim = new FrameDimension(img.FrameDimensionsList[0]);
				count = Math.Max(1, img.GetFrameCount(dim));
			}
		}
		catch
		{
			count = 1;
		}
		// 均匀取 8 帧，跳过全黑 / 纯色的帧（很多 GIF 开头是淡入的黑帧，黑帧之间一比就是“一模一样”），最多留 4 帧
		var picks = new SortedSet<int>();
		for (int i = 0; i < 8; i++)
		{
			picks.Add(count > 1 ? i * count / 8 : 0);
		}
		var list = new List<(float[] px, float[] hist)>();
		foreach (int f in picks)
		{
			if (count > 1 && dim != null)
			{
				img.SelectActiveFrame(dim, f);
			}
			float[] px = Pixels(img, out float spread);
			if (spread >= 0.05f)
			{
				list.Add((px, Histogram(img)));
			}
		}
		if (list.Count > Frames)
		{
			list = Enumerable.Range(0, Frames).Select(i => list[i * list.Count / Frames]).ToList();
		}
		return list;
	}

	/// <summary>
	/// 整张图（缩到 96×96）的颜色分布：色相 12 格 × 饱和度 4 格 × 亮度 4 格。换了动作、换了构图，发色和衣服颜色还在，
	/// 用来区分“同一个画师画的不同人物”（图像模型觉得很像，颜色却完全不同）。
	/// </summary>
	private static float[] Histogram(Image img)
	{
		const int n = 96;
		var hist = new float[192];
		using var bmp = new Bitmap(n, n, PixelFormat.Format24bppRgb);
		using (Graphics g = Graphics.FromImage(bmp))
		{
			g.InterpolationMode = InterpolationMode.HighQualityBilinear;
			g.DrawImage(img, new Rectangle(0, 0, n, n));
		}
		BitmapData data = bmp.LockBits(new Rectangle(0, 0, n, n), ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
		try
		{
			var row = new byte[data.Stride];
			for (int y = 0; y < n; y++)
			{
				System.Runtime.InteropServices.Marshal.Copy(data.Scan0 + y * data.Stride, row, 0, data.Stride);
				for (int x = 0; x < n; x++)
				{
					int b = row[x * 3], gr = row[x * 3 + 1], r = row[x * 3 + 2];
					int max = Math.Max(r, Math.Max(gr, b)), min = Math.Min(r, Math.Min(gr, b)), c = max - min;
					double h = 0;
					if (c > 0)
					{
						h = max == r ? (gr - b) / (double)c : max == gr ? 2 + (b - r) / (double)c : 4 + (r - gr) / (double)c;
						h = (h / 6 + 1) % 1;
					}
					int s = max == 0 ? 0 : c * 255 / max;
					int hb = Math.Min(11, (int)(h * 12)), sb = Math.Min(3, s * 4 / 256), vb = Math.Min(3, max * 4 / 256);
					hist[hb * 16 + sb * 4 + vb]++;
				}
			}
		}
		finally
		{
			bmp.UnlockBits(data);
		}
		for (int i = 0; i < hist.Length; i++)
		{
			hist[i] /= n * n;
		}
		return hist;
	}

	/// <summary>spread：像素亮度（0~1）的标准差，太小说明是一张纯色图。</summary>
	private static float[] Pixels(Image img, out float spread)
	{
		int side = Math.Min(img.Width, img.Height);
		var src = new Rectangle((img.Width - side) / 2, (img.Height - side) / 2, side, side);
		using var bmp = new Bitmap(Size, Size, PixelFormat.Format24bppRgb);
		using (Graphics g = Graphics.FromImage(bmp))
		using (var attr = new ImageAttributes())
		{
			attr.SetWrapMode(WrapMode.TileFlipXY);
			g.InterpolationMode = InterpolationMode.HighQualityBicubic;
			g.PixelOffsetMode = PixelOffsetMode.HighQuality;
			g.Clear(Color.Black);
			g.DrawImage(img, new Rectangle(0, 0, Size, Size), src.X, src.Y, src.Width, src.Height, GraphicsUnit.Pixel, attr);
		}
		var px = new float[3 * Size * Size];
		BitmapData data = bmp.LockBits(new Rectangle(0, 0, Size, Size), ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
		try
		{
			var row = new byte[data.Stride];
			int plane = Size * Size;
			double sum = 0, sq = 0;
			for (int y = 0; y < Size; y++)
			{
				System.Runtime.InteropServices.Marshal.Copy(data.Scan0 + y * data.Stride, row, 0, data.Stride);
				for (int x = 0; x < Size; x++)
				{
					for (int c = 0; c < 3; c++)
					{
						double v = row[x * 3 + c] / 255.0;
						sum += v;
						sq += v * v;
					}
					int o = y * Size + x;
					// 内存里是 BGR
					px[o] = (row[x * 3 + 2] / 255f - Mean[0]) / Std[0];
					px[plane + o] = (row[x * 3 + 1] / 255f - Mean[1]) / Std[1];
					px[2 * plane + o] = (row[x * 3] / 255f - Mean[2]) / Std[2];
				}
			}
			double n = 3.0 * plane, mean = sum / n;
			spread = (float)Math.Sqrt(Math.Max(0, sq / n - mean * mean));
		}
		finally
		{
			bmp.UnlockBits(data);
		}
		return px;
	}

	// ---- comparing ----

	/// <summary>
	/// 所有相似度不低于 min 的组合（按相似度从高到低，最多 limit 个）。
	/// 相似度 =（图像模型的相似度 + 颜色分布的重合度）/ 2，各自取所有帧组合里最高的。
	/// 在本机 1618 个壁纸上看过：0.75 以上基本是同一张图或同一个人物换了动作，0.72 以下开始混进画风相近的不同人物。
	/// </summary>
	public static List<Pair> Pairs(IList<Entry> entries, float min, int limit)
	{
		int n = entries.Count;
		var found = new List<Pair>[n];
		Parallel.For(0, n, i =>
		{
			float[][] a = entries[i].Vectors;
			for (int j = i + 1; j < n; j++)
			{
				float d = MaxDot(a, entries[j].Vectors);
				if (d < 2 * min - 1)
				{
					continue;   // 颜色再像（最多 1）也到不了 min
				}
				float s = (d + MaxOverlap(entries[i].Hists, entries[j].Hists)) / 2;
				if (s >= min)
				{
					// with video frames: would the covers alone have been as close?
					bool video = false;
					Entry ei = entries[i], ej = entries[j];
					if (ei.CoverCount < ei.Vectors.Length || ej.CoverCount < ej.Vectors.Length)
					{
						float[][] ci = ei.Vectors.Take(ei.CoverCount).ToArray(), cj = ej.Vectors.Take(ej.CoverCount).ToArray();
						float dc = ci.Length > 0 && cj.Length > 0 ? MaxDot(ci, cj) : -1;
						float sc = dc < 2 * min - 1 ? -1 : (dc + MaxOverlap(ei.Hists.Take(ei.CoverCount).ToArray(), ej.Hists.Take(ej.CoverCount).ToArray())) / 2;
						video = sc < min;
					}
					(found[i] ??= new List<Pair>()).Add(new Pair { A = i, B = j, Score = s, Video = video });
				}
			}
		});
		return found.Where(l => l != null).SelectMany(l => l).OrderByDescending(p => p.Score).Take(limit).ToList();
	}

	private static float MaxDot(float[][] a, float[][] b)
	{
		float best = -1;
		foreach (float[] x in a)
		{
			foreach (float[] y in b)
			{
				float d = Dot(x, y);
				if (d > best)
				{
					best = d;
				}
			}
		}
		return best;
	}

	private static float MaxOverlap(float[][] a, float[][] b)
	{
		float best = 0;
		foreach (float[] x in a)
		{
			foreach (float[] y in b)
			{
				float s = 0;
				for (int i = 0; i < x.Length; i++)
				{
					s += Math.Min(x[i], y[i]);
				}
				if (s > best)
				{
					best = s;
				}
			}
		}
		return best;
	}

	private static float Dot(float[] x, float[] y)
	{
		int w = Vector<float>.Count, i = 0;
		var acc = Vector<float>.Zero;
		for (; i <= x.Length - w; i += w)
		{
			acc += new Vector<float>(x, i) * new Vector<float>(y, i);
		}
		float s = Vector.Dot(acc, Vector<float>.One);
		for (; i < x.Length; i++)
		{
			s += x[i] * y[i];
		}
		return s;
	}

	// ---- cache file ----

	private void LoadCache()
	{
		if (_cacheLoaded)
		{
			return;
		}
		_cacheLoaded = true;
		try
		{
			if (!File.Exists(CachePath))
			{
				return;
			}
			using var r = new BinaryReader(File.OpenRead(CachePath), Encoding.UTF8);
			if (r.ReadString() != "WERDUPE2" || r.ReadString() != ModelName)
			{
				return;
			}
			int count = r.ReadInt32();
			for (int i = 0; i < count; i++)
			{
				var e = new Entry { Key = r.ReadString(), Stamp = r.ReadString() };
				int frames = r.ReadInt32(), dim = r.ReadInt32();
				e.Vectors = new float[frames][];
				e.Hists = new float[frames][];
				for (int f = 0; f < frames; f++)
				{
					e.Vectors[f] = ReadFloats(r, dim);
					e.Hists[f] = ReadFloats(r, 192);
				}
				_cache[e.Key] = e;
			}
		}
		catch (Exception ex)
		{
			_log.Log("查重：读缓存失败，重新计算：" + ex.Message);
			_cache.Clear();
		}
	}

	private static float[] ReadFloats(BinaryReader r, int count)
	{
		var v = new float[count];
		byte[] bytes = r.ReadBytes(count * 4);
		Buffer.BlockCopy(bytes, 0, v, 0, bytes.Length);
		return v;
	}

	private static void WriteFloats(BinaryWriter w, float[] v)
	{
		var bytes = new byte[v.Length * 4];
		Buffer.BlockCopy(v, 0, bytes, 0, bytes.Length);
		w.Write(bytes);
	}

	private void SaveCache()
	{
		try
		{
			Directory.CreateDirectory(_dir);
			string tmp = CachePath + ".tmp";
			using (var w = new BinaryWriter(File.Create(tmp), Encoding.UTF8))
			{
				w.Write("WERDUPE2");
				w.Write(ModelName);
				w.Write(_cache.Count);
				foreach (Entry e in _cache.Values)
				{
					w.Write(e.Key);
					w.Write(e.Stamp);
					w.Write(e.Vectors.Length);
					w.Write(e.Vectors.Length > 0 ? e.Vectors[0].Length : 0);   // a video with no usable frame is kept empty, so it isn't decoded again
					for (int f = 0; f < e.Vectors.Length; f++)
					{
						WriteFloats(w, e.Vectors[f]);
						WriteFloats(w, e.Hists[f]);
					}
				}
			}
			if (File.Exists(CachePath))
			{
				File.Replace(tmp, CachePath, null);
			}
			else
			{
				File.Move(tmp, CachePath);
			}
		}
		catch (Exception ex)
		{
			_log.Log("查重：保存缓存失败：" + ex.Message);
		}
	}
}
