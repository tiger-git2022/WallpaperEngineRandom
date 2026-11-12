package com.tiger.steamwallpaper

import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 翻译设置的快照：在后台线程用，不碰 Compose 状态。 */
class AiConfig(
    val format: String,
    val baseUrl: String,
    val keyEnc: String,
    val model: String,
    val thinkModel: String,
    val think: String,
    val thinkWords: Int,
    val stream: Boolean,
    val target: String,
)

/** 一篇新闻的译文。 */
class PostTranslation(val title: String, val summary: String, val body: String)

/**
 * 用大模型翻译新闻和评论，照搬电脑版 SteamNewsFeed 的 Translator.cs。两种接口：
 * OpenAI 兼容（DeepSeek、通义千问、Kimi、智谱、OpenRouter……，直接发 HTTP，SSE 流式）和 Anthropic Claude（官方 Java SDK）。
 * 可选思考 / 不思考 / 超过多少词才思考，流式 / 非流式。
 */
object Translator {
    const val DEFAULT_OPENAI_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_OPENAI_MODEL = "deepseek-chat"
    const val DEFAULT_CLAUDE_BASE = "https://api.anthropic.com"
    const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
    val TARGETS = listOf("简体中文", "繁體中文", "English", "日本語")

    /** 同时最多 3 个请求。 */
    private val gate = Semaphore(3)

    /** 翻译接口用自己的连接（不带 Steam 的 cookie）；流式时两段数据之间最多等 5 分钟。 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).writeTimeout(1, TimeUnit.MINUTES).build()
    }
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun model(c: AiConfig) = c.model.trim().ifEmpty { if (c.format == "anthropic") DEFAULT_CLAUDE_MODEL else DEFAULT_OPENAI_MODEL }
    fun baseUrl(c: AiConfig) = c.baseUrl.trim().ifEmpty { if (c.format == "anthropic") DEFAULT_CLAUDE_BASE else DEFAULT_OPENAI_BASE }.trimEnd('/')
    private fun target(c: AiConfig) = c.target.ifBlank { "简体中文" }

    // ---- 密钥：用 Android Keystore 里的 AES-GCM 密钥加密（密钥本身不出 Keystore），存加密后的 Base64 ----

    private const val KEY_ALIAS = "news_translate_api_key"
    private const val IV_BYTES = 12

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun protect(key: String): String {
        if (key.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            Base64.encodeToString(iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("加密 API 密钥失败", e)
            ""
        }
    }

    fun unprotect(enc: String): String {
        if (enc.isEmpty()) return ""
        return try {
            val data = Base64.decode(enc, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 0, IV_BYTES))
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("解开 API 密钥失败", e)
            ""
        }
    }

    // ---- 要不要思考 ----

    private val BB_TAG = Regex("\\[[^\\]]{1,200}\\]")
    private val CJK = Regex("[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** 粗略的「词数」：中日韩的字一个算一个词，其他按空格分的词算。 */
    fun countWords(text: String): Int {
        val t = BB_TAG.replace(text, " ")
        return CJK.findAll(t).count() + WORD.findAll(t).count { !CJK.containsMatchIn(it.value) }
    }

    private fun shouldThink(c: AiConfig, text: String) = when (c.think) {
        "on" -> true
        "auto" -> countWords(text) > c.thinkWords.coerceAtLeast(0)
        else -> false
    }

    // ---- 需不需要翻译（和电脑版页面的 needsTr 一样，粗略看文字是不是已经是目标语言）----

    private val URL_RE = Regex("https?://\\S+")
    private val HAN = Regex("[\u3400-\u9fff]")
    private val KANA = Regex("[\u3040-\u30ff]")
    private val HANGUL = Regex("[\uac00-\ud7af]")
    private val LATIN = Regex("[A-Za-z\u00c0-\u024f\u0400-\u04ff]{2,}")

    fun needsTranslation(text: String, to: String): Boolean {
        val t = BB_TAG.replace(URL_RE.replace(text, ""), " ")
        val han = HAN.findAll(t).count()
        val kana = KANA.findAll(t).count()
        val hangul = HANGUL.findAll(t).count()
        val latin = LATIN.findAll(t).count()
        if (han + kana + hangul + latin < 2) return false
        return when {
            to.contains("中文") -> kana > han * 0.15 || hangul > 0 || latin * 2 > han
            to == "English" -> han + kana + hangul > latin
            to == "日本語" -> kana < 3
            else -> true
        }
    }

    // ---- 翻译 ----

    /** 翻译一篇新闻：标题、摘要、正文（BBCode）。[onText] 收到的是到目前为止的原始输出（流式时在后台线程多次调用）。 */
    suspend fun translatePost(c: AiConfig, title: String, summary: String, body: String, onText: ((String) -> Unit)?): PostTranslation {
        val system = "你是专业的游戏新闻翻译。把用户给出的 Steam 新闻翻译成" + target(c) + "。\n" +
            "规则：\n" +
            "1. 原样保留所有 BBCode 标签（方括号里的内容，例如 [b]、[/b]、[url=...]、[img]...[/img]、[list]、[*]、[h2]）、网址、{STEAM_CLAN_IMAGE} 和换行，只翻译标签之间的文字。\n" +
            "2. 游戏名、人名、公司名有通行译名就用译名，没有就保留原文。\n" +
            "3. 保留 ===TITLE===、===SUMMARY===、===BODY=== 这三个分隔行，每部分的译文放在对应分隔行下面；原文某部分为空，译文也留空。\n" +
            "4. 原文已经是目标语言的部分照抄。只输出译文，不要任何解释。"
        val user = "===TITLE===\n$title\n===SUMMARY===\n$summary\n===BODY===\n$body"
        val output = run(c, system, user, shouldThink(c, "$title\n$summary\n$body"), onText)
        return splitPost(output)
    }

    private val PARTS = listOf("TITLE", "SUMMARY", "BODY").associateWith { name ->
        Regex("===$name===\\s*\\n?([\\s\\S]*?)(?=\\n?===(?:TITLE|SUMMARY|BODY)===|\$)")
    }
    private val HALF_SEPARATOR = Regex("\\n?=+[A-Z]*=*\\s*\$")

    /** 把输出分成三部分；[partial]（流式还没翻完）时末尾可能有半截分隔行，去掉。 */
    fun splitPost(output: String, partial: Boolean = false): PostTranslation {
        fun part(name: String) = PARTS.getValue(name).find(output)?.groupValues?.get(1)
            ?.let { if (partial) it.replace(HALF_SEPARATOR, "") else it }?.trim() ?: ""
        return PostTranslation(part("TITLE"), part("SUMMARY"), part("BODY"))
    }

    /** 一次翻译几条评论（纯文字），按原来的顺序返回。 */
    suspend fun translateTexts(c: AiConfig, texts: List<String>): List<String> {
        val system = "你是专业的翻译。把用户给出的几段 Steam 玩家评论分别翻译成" + target(c) + "，语气口语化、自然。\n" +
            "保留 ===1===、===2=== 这样的分隔行，每段译文放在对应分隔行下面；原文已经是目标语言的照抄。只输出译文，不要解释。"
        val user = buildString { texts.forEachIndexed { i, t -> append("===").append(i + 1).append("===\n").append(t).append('\n') } }
        val output = run(c, system, user, shouldThink(c, user), null)
        return texts.indices.map { i ->
            Regex("===${i + 1}===\\s*\\n?([\\s\\S]*?)(?=\\n?===\\d+===|\$)").find(output)?.groupValues?.get(1)?.trim() ?: ""
        }
    }

    /** 设置里的「测试」：翻一句话，返回译文和用时。 */
    suspend fun test(c: AiConfig): String {
        val start = SystemClock.elapsedRealtime()
        val text = run(c, "把用户的话翻译成" + target(c) + "，只输出译文。", "The new update is out now! Thanks for playing.", shouldThink(c, "short"), null)
        val sec = (SystemClock.elapsedRealtime() - start) / 1000.0
        return text.trim() + "（" + (if (c.format == "anthropic") "Claude" else "OpenAI 兼容") + " · " + model(c) + " · " + String.format(Locale.ROOT, "%.1f", sec) + " 秒）"
    }

    private suspend fun run(c: AiConfig, system: String, user: String, think: Boolean, onText: ((String) -> Unit)?): String {
        val key = withContext(Dispatchers.IO) { unprotect(c.keyEnc) }
        if (key.isEmpty()) throw IllegalStateException("还没有填 API 密钥（新闻设置 → 翻译）")
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val text = if (c.format == "anthropic") claude(c, key, system, user, think, onText) else openAi(c, key, system, user, think, onText)
                stripThink(text)
            }
        }
    }

    private val THINK_TAG = Regex("^\\s*<think>[\\s\\S]*?</think>\\s*")

    /** 有的推理模型把思考过程用 <think> 包着放在正文里，去掉。 */
    private fun stripThink(s: String) = THINK_TAG.replace(s, "")

    fun errText(e: Throwable): String = when (e) {
        is AnthropicServiceException -> "翻译接口出错（${e.statusCode()}）：" + (e.apiMessage().orElse(null) ?: e.message ?: "")
        is java.net.UnknownHostException -> "连不上翻译接口（${e.message}），请检查网络和接口地址"
        is java.net.SocketTimeoutException -> "翻译接口超时"
        else -> e.message ?: e.toString()
    }

    // ---- OpenAI 兼容 ----

    private val THINK_OPENAI_MODEL = Regex("^(o\\d|gpt-5)", RegexOption.IGNORE_CASE)

    private suspend fun openAi(c: AiConfig, key: String, system: String, user: String, think: Boolean, onText: ((String) -> Unit)?): String {
        val base = baseUrl(c)
        val host = base.toHttpUrlOrNull()?.host?.lowercase() ?: ""
        val model = if (think && c.thinkModel.isNotBlank()) c.thinkModel.trim() else model(c)
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
            .put("stream", c.stream)
        // 各家开关思考的参数不一样：按地址判断（没有对应参数的，靠「思考时换用的模型」切换）
        when {
            "dashscope" in host || "siliconflow" in host || "modelscope" in host -> body.put("enable_thinking", think)
            "bigmodel" in host || host.endsWith("z.ai") || "volces" in host || "ark." in host -> body.put("thinking", JSONObject().put("type", if (think) "enabled" else "disabled"))
            "openrouter" in host -> body.put("reasoning", JSONObject().put("enabled", think))
            "api.openai.com" in host && THINK_OPENAI_MODEL.containsMatchIn(model) -> body.put("reasoning_effort", if (think) "medium" else "low")
        }
        val req = Request.Builder().url("$base/chat/completions").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("翻译接口出错（${resp.code}）：" + errorText(resp.body.string()))
            if (!c.stream) {
                val message = JSONObject(resp.body.string()).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val text = if (message == null || message.isNull("content")) "" else message.optString("content")
                if (text.isBlank()) throw IllegalStateException("翻译接口没有返回译文")
                onText?.invoke(text)
                return text
            }
            val sb = StringBuilder()
            val source = resp.body.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val j = try { JSONObject(data) } catch (_: Exception) { continue }
                if (j.has("error")) throw IllegalStateException("翻译接口出错：" + errorText(data))
                val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val piece = if (delta.isNull("content")) "" else delta.optString("content")
                if (piece.isNotEmpty()) {
                    sb.append(piece)
                    onText?.invoke(sb.toString())
                }
            }
            return sb.toString()
        }
    }

    private fun errorText(raw: String): String {
        try {
            val j = JSONObject(raw)
            val e = j.opt("error")
            val m = when (e) {
                is String -> e
                is JSONObject -> e.optString("message")
                else -> j.optString("message")
            }
            if (m.isNotEmpty()) return m
        } catch (_: Exception) {
        }
        val t = raw.trim()
        return if (t.length > 200) t.take(200) + "…" else t
    }

    // ---- Anthropic Claude（官方 Java SDK）----

    /** 这些模型在官方接口上被安全分类器拒绝时，服务端自动换 Anthropic 推荐的模型重试（fallbacks: "default"）。 */
    private val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

    /** 不支持 effort 的老模型（中转上可能还在用）。 */
    private val NO_EFFORT = Regex("^claude-(3|haiku-4|sonnet-4-5|sonnet-4-0|sonnet-4-2|opus-4-0|opus-4-1|opus-4-2)")

    /** 不支持自适应思考的老模型（4.6 以前）。 */
    private val NO_ADAPTIVE = Regex("^claude-(3|haiku-4|sonnet-4-5|sonnet-4-0|sonnet-4-2|opus-4-0|opus-4-1|opus-4-2|opus-4-5)")

    private var claudeId = ""
    private var claudeClient: AnthropicClient? = null

    @Synchronized
    private fun claudeClient(key: String, base: String): AnthropicClient {
        val id = "$base\n$key"
        claudeClient?.takeIf { claudeId == id }?.let { return it }
        claudeClient?.close()
        val client = AnthropicOkHttpClient.builder().apiKey(key).baseUrl(base).timeout(Duration.ofMinutes(10)).build()
        claudeClient = client
        claudeId = id
        return client
    }

    private suspend fun claude(c: AiConfig, key: String, system: String, user: String, think: Boolean, onText: ((String) -> Unit)?): String {
        val base = baseUrl(c)
        val official = base.startsWith(DEFAULT_CLAUDE_BASE, ignoreCase = true)
        val client = claudeClient(key, base)
        val model = if (think && c.thinkModel.isNotBlank()) c.thinkModel.trim() else model(c)
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(if (c.stream) 64000L else 16000L)
            .system(system)
            .addUserMessage(user)
        // 思考：自适应、effort 高；不思考：不开思考、effort 调到最低（新模型思考关不掉，只能让它少想）。老模型不认这两个参数，不发
        if (!NO_EFFORT.containsMatchIn(model)) {
            builder.outputConfig(BetaOutputConfig.builder().effort(if (think) BetaOutputConfig.Effort.HIGH else BetaOutputConfig.Effort.LOW).build())
        }
        if (think && !NO_ADAPTIVE.containsMatchIn(model)) builder.thinking(BetaThinkingConfigAdaptive.builder().build())
        if (official && model in FALLBACK_MODELS) {
            // 官方接口上被安全分类器拒绝时，服务端按拒绝的类别自动换模型重试
            builder.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault()
        }
        val params = builder.build()
        if (!c.stream) {
            val msg = client.beta().messages().create(params)
            if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw IllegalStateException("模型拒绝翻译这条内容")
            val text = msg.content().joinToString("") { b -> b.text().map { it.text() }.orElse("") }
            onText?.invoke(text)
            return text
        }
        val sb = StringBuilder()
        var stop: BetaStopReason? = null
        client.beta().messages().createStreaming(params).use { stream ->
            val events = stream.stream().iterator()
            while (events.hasNext()) {
                currentCoroutineContext().ensureActive()
                val ev = events.next()
                val delta = ev.contentBlockDelta().orElse(null)
                if (delta != null) {
                    val t = delta.delta().text().orElse(null)
                    if (t != null && t.text().isNotEmpty()) {
                        sb.append(t.text())
                        onText?.invoke(sb.toString())
                    }
                    continue
                }
                ev.messageDelta().orElse(null)?.delta()?.stopReason()?.orElse(null)?.let { stop = it }
            }
        }
        // 最后还是拒绝（没有换模型，或者换了也拒绝）：已经输出的半截不能当译文用
        if (stop == BetaStopReason.REFUSAL) throw IllegalStateException("模型拒绝翻译这条内容")
        return sb.toString()
    }
}
