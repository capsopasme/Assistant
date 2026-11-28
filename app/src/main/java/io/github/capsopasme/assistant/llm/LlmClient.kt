package io.github.capsopasme.assistant.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ToolCall(val id: String, val name: String, val arguments: String)

data class LlmReply(val text: String?, val toolCalls: List<ToolCall>, val message: JSONObject)

/**
 * Minimal OpenAI-compatible chat client on HttpURLConnection: no extra libraries, nothing kept
 * alive between requests, and a cancelled coroutine aborts the connection right away.
 */
class LlmClient(
    private val provider: Provider,
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
) {
    suspend fun chat(messages: JSONArray, tools: JSONArray?, maxTokens: Int = 1024): LlmReply {
        if (apiKey.isBlank()) throw LlmException("还没有填写 ${provider.label} 的 API Key（在设置里填写）")
        val extrasKey = "${provider.id}/$model"
        val useExtras = extrasKey !in extrasRejected
        val body = buildBody(messages, tools, useExtras, maxTokens)
        val (code, text) = try {
            postWithRetry("$baseUrl/chat/completions", body)
        } catch (e: IOException) {
            throw LlmException(networkMessage(e), e)
        }
        if (code in 200..299) return parse(text)

        // fields such as "thinking" are not accepted by every model: try once more without them
        if (useExtras && (code == 400 || code == 422) &&
            Regex("thinking|reasoning|unknown|unrecognized|not support|invalid.*param|extra", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            extrasRejected += extrasKey
            val (code2, text2) = try {
                postWithRetry("$baseUrl/chat/completions", buildBody(messages, tools, false, maxTokens))
            } catch (e: IOException) {
                throw LlmException(networkMessage(e), e)
            }
            if (code2 in 200..299) return parse(text2)
            throw LlmException(httpMessage(code2, text2))
        }
        throw LlmException(httpMessage(code, text))
    }

    /** Model ids the service offers, for the settings screen. */
    suspend fun listModels(): List<String> {
        if (apiKey.isBlank()) throw LlmException("请先填写 API Key")
        val (code, text) = try {
            request("$baseUrl/models", null)
        } catch (e: IOException) {
            throw LlmException(networkMessage(e), e)
        }
        if (code !in 200..299) throw LlmException(httpMessage(code, text))
        val data = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.removePrefix("models/") }
            .filter { it.isNotEmpty() }.sorted()
    }

    private fun buildBody(messages: JSONArray, tools: JSONArray?, extras: Boolean, maxTokens: Int) = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        if (tools != null && tools.length() > 0) {
            put("tools", tools)
            put("tool_choice", "auto")
        }
        put("temperature", 0.3)
        put("max_tokens", maxTokens)
        put("stream", false)
        if (extras) provider.addExtras(this)
    }

    private fun parse(text: String): LlmReply {
        val root = try { JSONObject(text) } catch (e: Exception) { throw LlmException("模型返回了无法解析的内容") }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmException(root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: "模型没有返回结果")
        val msg = choice.optJSONObject("message") ?: throw LlmException("模型没有返回结果")
        val content = when (val c = msg.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text").orEmpty() }
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        val calls = mutableListOf<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = o.optJSONObject("function") ?: continue
                val name = f.optString("name").takeIf { it.isNotBlank() } ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: "call_${i}_${System.nanoTime() % 100000}"
                val args = when (val a = f.opt("arguments")) {
                    is String -> a
                    is JSONObject -> a.toString()
                    else -> "{}"
                }
                calls += ToolCall(id, name, args.ifBlank { "{}" })
            }
        }
        // what goes back into the history: only the fields every service accepts
        val clean = JSONObject().put("role", "assistant").put("content", content ?: JSONObject.NULL)
        if (calls.isNotEmpty()) {
            clean.put("tool_calls", JSONArray().apply {
                for (c in calls) put(JSONObject().put("id", c.id).put("type", "function")
                    .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)))
            })
        }
        return LlmReply(content, calls, clean)
    }

    private suspend fun postWithRetry(url: String, body: JSONObject): Pair<Int, String> = try {
        request(url, body)
    } catch (e: IOException) {
        // a stale route or a reset right after waking up: one quick retry, nothing more
        if (e is ConnectException || (e is SocketException && e !is SocketTimeoutException)) request(url, body) else throw e
    }

    private suspend fun request(url: String, body: JSONObject?): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val conn = URL(url).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { conn.disconnect() } }
            io.execute {
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 90_000
                    conn.useCaches = false
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    conn.setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        val bytes = body.toString().toByteArray(Charsets.UTF_8)
                        conn.setFixedLengthStreamingMode(bytes.size)
                        conn.outputStream.use { it.write(bytes) }
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    // read to the end and close: the connection goes back to the system's pool,
                    // so the next request (a tool round, a follow-up) skips DNS + TCP + TLS
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (cont.isActive) cont.resume(code to text)
                } catch (e: Throwable) {
                    runCatching { conn.disconnect() }
                    if (cont.isActive) cont.resumeWithException(if (e is IOException) e else IOException(e))
                }
            }
        }

    private fun networkMessage(e: IOException): String = when (e) {
        is UnknownHostException -> if (provider == Provider.GEMINI) "连不上 Gemini（无网络，或代理没有开）" else "网络不可用，连不上 ${provider.label}"
        is SocketTimeoutException -> "${provider.label} 响应超时，请稍后再试"
        is ConnectException, is NoRouteToHostException -> "连不上 ${provider.label}，请检查网络"
        is SSLException -> "与 ${provider.label} 的安全连接失败（网络或代理问题）"
        else -> "网络错误：${e.message ?: e.javaClass.simpleName}"
    }

    private fun httpMessage(code: Int, body: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
                ?: o.optString("msg").takeIf { it.isNotBlank() }
        }.getOrNull()?.take(160)
        val base = when (code) {
            401, 403 -> "API Key 无效或没有权限"
            402 -> "${provider.label} 账户余额不足"
            404 -> "模型名或接口地址不对（$model）"
            429 -> "请求太频繁，或免费额度已用完"
            in 500..599 -> "${provider.label} 服务暂时不可用（$code）"
            else -> "请求失败（$code）"
        }
        return if (detail != null && code != 404) "$base：$detail" else base
    }

    companion object {
        /** provider/model pairs that refused the speed-up fields; kept for the process lifetime */
        private val extrasRejected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val io = Executors.newCachedThreadPool { r -> Thread(r, "llm-http").apply { isDaemon = true } }

        @Volatile private var lastWarmUp = 0L
        @Volatile private var lastWarmUpHost: String? = null

        /**
         * Opens the connection to the service ahead of the request (a HEAD on the API's host, no
         * key sent), while the user is still finishing the sentence: DNS, TCP and TLS take a few
         * hundred ms, more on mobile data. The connection then waits in the system's pool.
         * At most once every 30 s; an open pooled connection makes it nearly free anyway.
         */
        fun warmUp(baseUrl: String) {
            val url = runCatching { URL(baseUrl) }.getOrNull() ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            if (url.host == lastWarmUpHost && now - lastWarmUp < 30_000) return
            lastWarmUp = now
            lastWarmUpHost = url.host
            io.execute {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(url.protocol, url.host, url.port, "/").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.useCaches = false
                    conn.requestMethod = "HEAD"
                    conn.responseCode
                    (conn.errorStream ?: runCatching { conn.inputStream }.getOrNull())?.close()
                } catch (_: Exception) {
                    runCatching { conn?.disconnect() }
                }
            }
        }
    }
}
