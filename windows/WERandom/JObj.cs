using System;
using System.Collections.Generic;
using System.Globalization;

namespace WERandom;

public class JObj
{
	private readonly List<string> _keys = new List<string>();

	private readonly Dictionary<string, object> _map = new Dictionary<string, object>();

	public IEnumerable<string> Keys => _keys;

	public int Count => _keys.Count;

	public object this[string k]
	{
		get
		{
			object value;
			return (!_map.TryGetValue(k, out value)) ? null : value;
		}
		set
		{
			if (!_map.ContainsKey(k))
			{
				_keys.Add(k);
			}
			_map[k] = value;
		}
	}

	public bool Has(string k)
	{
		return _map.ContainsKey(k);
	}

	public void Remove(string k)
	{
		if (_map.Remove(k))
		{
			_keys.Remove(k);
		}
	}

	public string Str(string k, string def = "")
	{
		object obj = this[k];
		return (obj != null) ? Convert.ToString(obj, CultureInfo.InvariantCulture) : def;
	}

	public double Num(string k, double def = 0.0)
	{
		object obj = this[k];
		if (obj == null)
		{
			return def;
		}
		if (obj is double)
		{
			return (double)obj;
		}
		double result;
		return (!double.TryParse(Convert.ToString(obj, CultureInfo.InvariantCulture), NumberStyles.Any, CultureInfo.InvariantCulture, out result)) ? def : result;
	}

	public int Int(string k, int def = 0)
	{
		return (int)Math.Round(Num(k, def));
	}

	public bool Bool(string k, bool def = false)
	{
		object obj = this[k];
		if (obj == null)
		{
			return def;
		}
		if (obj is bool)
		{
			return (bool)obj;
		}
		string text = Convert.ToString(obj).ToLowerInvariant();
		return text == "true" || text == "1";
	}

	public List<object> List(string k)
	{
		object obj = this[k];
		return (obj as List<object>) ?? new List<object>();
	}

	public JObj Obj(string k)
	{
		return this[k] as JObj;
	}

	public List<string> StrList(string k)
	{
		List<string> list = new List<string>();
		foreach (object item in List(k))
		{
			if (item != null)
			{
				list.Add(Convert.ToString(item, CultureInfo.InvariantCulture));
			}
		}
		return list;
	}
}
