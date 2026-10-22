using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace WERandom;

public static class Json
{
	public static string Write(object v, bool pretty = true)
	{
		StringBuilder stringBuilder = new StringBuilder();
		WriteValue(stringBuilder, v, (!pretty) ? (-1) : 0);
		return stringBuilder.ToString();
	}

	private static void Indent(StringBuilder sb, int level)
	{
		if (level >= 0)
		{
			sb.Append('\n');
			for (int i = 0; i < level; i++)
			{
				sb.Append("  ");
			}
		}
	}

	private static void WriteValue(StringBuilder sb, object v, int level)
	{
		if (v == null)
		{
			sb.Append("null");
		}
		else if (v is bool)
		{
			sb.Append((!(bool)v) ? "false" : "true");
		}
		else if (v is string)
		{
			WriteString(sb, (string)v);
		}
		else if (v is int || v is long || v is float || v is double || v is decimal)
		{
			sb.Append(Convert.ToString(v, CultureInfo.InvariantCulture));
		}
		else if (v is DateTime)
		{
			WriteString(sb, ((DateTime)v).ToString("o"));
		}
		else if (v is JObj jObj)
		{
			if (jObj.Count == 0)
			{
				sb.Append("{}");
				return;
			}
			sb.Append('{');
			bool flag = true;
			foreach (string key in jObj.Keys)
			{
				if (!flag)
				{
					sb.Append(',');
				}
				flag = false;
				Indent(sb, (level >= 0) ? (level + 1) : (-1));
				WriteString(sb, key);
				sb.Append((level >= 0) ? ": " : ":");
				WriteValue(sb, jObj[key], (level >= 0) ? (level + 1) : (-1));
			}
			Indent(sb, level);
			sb.Append('}');
		}
		else if (v is IDictionary<string, object> dictionary)
		{
			JObj jObj2 = new JObj();
			foreach (KeyValuePair<string, object> item in dictionary)
			{
				jObj2[item.Key] = item.Value;
			}
			WriteValue(sb, jObj2, level);
		}
		else if (v is IEnumerable enumerable)
		{
			sb.Append('[');
			bool flag2 = true;
			bool flag3 = false;
			foreach (object item2 in enumerable)
			{
				flag3 = true;
				if (!flag2)
				{
					sb.Append(',');
				}
				flag2 = false;
				Indent(sb, (level >= 0) ? (level + 1) : (-1));
				WriteValue(sb, item2, (level >= 0) ? (level + 1) : (-1));
			}
			if (flag3)
			{
				Indent(sb, level);
			}
			sb.Append(']');
		}
		else
		{
			WriteString(sb, v.ToString());
		}
	}

	private static void WriteString(StringBuilder sb, string s)
	{
		sb.Append('"');
		foreach (char c in s)
		{
			switch (c)
			{
			case '\n':
				sb.Append("\\n");
				continue;
			case '\r':
				sb.Append("\\r");
				continue;
			case '\t':
				sb.Append("\\t");
				continue;
			}
			if (c != '"')
			{
				if (c == '\\')
				{
					sb.Append("\\\\");
				}
				else if (c < ' ')
				{
					StringBuilder stringBuilder = sb.Append("\\u");
					int num = c;
					stringBuilder.Append(num.ToString("x4"));
				}
				else
				{
					sb.Append(c);
				}
			}
			else
			{
				sb.Append("\\\"");
			}
		}
		sb.Append('"');
	}

	public static void ExtractTopLevel(string text, out string title, out string type)
	{
		title = null;
		type = null;
		if (string.IsNullOrEmpty(text))
		{
			return;
		}
		int p = 0;
		if (text[0] == '\ufeff')
		{
			p = 1;
		}
		SkipWs(text, ref p);
		if (p >= text.Length || text[p] != '{')
		{
			throw new FormatException("not an object");
		}
		p++;
		while (true)
		{
			SkipWs(text, ref p);
			if (p >= text.Length || text[p] == '}')
			{
				break;
			}
			if (text[p] == ',')
			{
				p++;
				continue;
			}
			string text2 = ParseString(text, ref p);
			SkipWs(text, ref p);
			if (p >= text.Length || text[p] != ':')
			{
				throw new FormatException("expected ':'");
			}
			p++;
			SkipWs(text, ref p);
			if (p >= text.Length)
			{
				break;
			}
			if (text2 == "title" && text[p] == '"')
			{
				title = ParseString(text, ref p);
			}
			else if (text2 == "type" && text[p] == '"')
			{
				type = ParseString(text, ref p);
			}
			else
			{
				SkipValue(text, ref p);
			}
			if (title == null || type == null)
			{
				continue;
			}
			break;
		}
	}

	private static void SkipValue(string s, ref int p)
	{
		if (p >= s.Length)
		{
			return;
		}
		switch (s[p])
		{
		case '"':
			ParseString(s, ref p);
			return;
		case '[':
		case '{':
		{
			int num = 0;
			while (p < s.Length)
			{
				switch (s[p])
				{
				case '"':
					ParseString(s, ref p);
					continue;
				case '[':
				case '{':
					num++;
					break;
				case ']':
				case '}':
					num--;
					if (num == 0)
					{
						p++;
						return;
					}
					break;
				}
				p++;
			}
			return;
		}
		}
		while (p < s.Length && s[p] != ',' && s[p] != '}' && s[p] != ']')
		{
			p++;
		}
	}

	public static object Parse(string text)
	{
		if (text == null)
		{
			throw new FormatException("empty json");
		}
		int p = 0;
		if (text.Length > 0 && text[0] == '\ufeff')
		{
			p = 1;
		}
		SkipWs(text, ref p);
		object result = ParseValue(text, ref p);
		SkipWs(text, ref p);
		return result;
	}

	public static JObj ParseObj(string text)
	{
		return (Parse(text) as JObj) ?? new JObj();
	}

	private static void SkipWs(string s, ref int p)
	{
		while (p < s.Length)
		{
			char c = s[p];
			switch (c)
			{
			case '\t':
			case '\n':
			case '\r':
			case ' ':
				p++;
				continue;
			case '/':
				if (p + 1 < s.Length && s[p + 1] == '/')
				{
					while (p < s.Length && s[p] != '\n')
					{
						p++;
					}
					continue;
				}
				break;
			}
			if (c == '/' && p + 1 < s.Length && s[p + 1] == '*')
			{
				p += 2;
				while (p + 1 < s.Length && (s[p] != '*' || s[p + 1] != '/'))
				{
					p++;
				}
				p += 2;
				continue;
			}
			break;
		}
	}

	private static object ParseValue(string s, ref int p)
	{
		if (p >= s.Length)
		{
			throw new FormatException("unexpected end of json");
		}
		switch (s[p])
		{
		case '{':
			return ParseObject(s, ref p);
		case '[':
			return ParseArray(s, ref p);
		case '"':
			return ParseString(s, ref p);
		default:
		{
			if (s.Length - p >= 4 && string.CompareOrdinal(s, p, "true", 0, 4) == 0)
			{
				p += 4;
				return true;
			}
			if (s.Length - p >= 5 && string.CompareOrdinal(s, p, "false", 0, 5) == 0)
			{
				p += 5;
				return false;
			}
			if (s.Length - p >= 4 && string.CompareOrdinal(s, p, "null", 0, 4) == 0)
			{
				p += 4;
				return null;
			}
			int num = p;
			while (p < s.Length && "+-0123456789.eE".IndexOf(s[p]) >= 0)
			{
				p++;
			}
			if (p == num)
			{
				throw new FormatException("bad json at " + p);
			}
			return double.Parse(s.Substring(num, p - num), NumberStyles.Float, CultureInfo.InvariantCulture);
		}
		}
	}

	private static JObj ParseObject(string s, ref int p)
	{
		JObj jObj = new JObj();
		p++;
		SkipWs(s, ref p);
		if (p < s.Length && s[p] == '}')
		{
			p++;
			return jObj;
		}
		while (true)
		{
			SkipWs(s, ref p);
			if (p < s.Length && s[p] == '}')
			{
				p++;
				return jObj;
			}
			string k = ParseString(s, ref p);
			SkipWs(s, ref p);
			if (p >= s.Length || s[p] != ':')
			{
				throw new FormatException("expected ':' at " + p);
			}
			p++;
			SkipWs(s, ref p);
			jObj[k] = ParseValue(s, ref p);
			SkipWs(s, ref p);
			if (p >= s.Length || s[p] != ',')
			{
				break;
			}
			p++;
		}
		if (p < s.Length && s[p] == '}')
		{
			p++;
			return jObj;
		}
		throw new FormatException("expected ',' or '}' at " + p);
	}

	private static List<object> ParseArray(string s, ref int p)
	{
		List<object> list = new List<object>();
		p++;
		SkipWs(s, ref p);
		if (p < s.Length && s[p] == ']')
		{
			p++;
			return list;
		}
		while (true)
		{
			SkipWs(s, ref p);
			if (p < s.Length && s[p] == ']')
			{
				p++;
				return list;
			}
			list.Add(ParseValue(s, ref p));
			SkipWs(s, ref p);
			if (p >= s.Length || s[p] != ',')
			{
				break;
			}
			p++;
		}
		if (p < s.Length && s[p] == ']')
		{
			p++;
			return list;
		}
		throw new FormatException("expected ',' or ']' at " + p);
	}

	private static string ParseString(string s, ref int p)
	{
		if (p >= s.Length || s[p] != '"')
		{
			throw new FormatException("expected string at " + p);
		}
		p++;
		StringBuilder stringBuilder = new StringBuilder();
		while (p < s.Length)
		{
			char c = s[p++];
			switch (c)
			{
			case '"':
				return stringBuilder.ToString();
			default:
				stringBuilder.Append(c);
				continue;
			case '\\':
				break;
			}
			if (p >= s.Length)
			{
				break;
			}
			char c2 = s[p++];
			switch (c2)
			{
			case '"':
				stringBuilder.Append('"');
				break;
			case '\\':
				stringBuilder.Append('\\');
				break;
			case '/':
				stringBuilder.Append('/');
				break;
			case 'b':
				stringBuilder.Append('\b');
				break;
			case 'f':
				stringBuilder.Append('\f');
				break;
			case 'n':
				stringBuilder.Append('\n');
				break;
			case 'r':
				stringBuilder.Append('\r');
				break;
			case 't':
				stringBuilder.Append('\t');
				break;
			case 'u':
				if (p + 4 <= s.Length)
				{
					stringBuilder.Append((char)Convert.ToInt32(s.Substring(p, 4), 16));
					p += 4;
				}
				break;
			default:
				stringBuilder.Append(c2);
				break;
			}
		}
		throw new FormatException("unterminated string");
	}
}
