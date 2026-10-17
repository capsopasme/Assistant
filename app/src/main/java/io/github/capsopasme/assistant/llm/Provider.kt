package io.github.capsopasme.assistant.llm

import org.json.JSONObject

/**
 * The three model services, all spoken to through their OpenAI-compatible chat API.
 * Only the selected one is ever used; there is deliberately no fallback to another.
 *
 * [extras]: request fields that keep answers fast (no long "thinking"). Model names change often,
 * so if a service rejects them the request is repeated once without (see [LlmClient]).
 */
enum class Provider(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val keyHelpUrl: String,
) {
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", "https://platform.deepseek.com/api_keys"),
    GLM("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash", "https://open.bigmodel.cn/usercenter/apikeys"),
    GEMINI("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-latest", "https://aistudio.google.com/apikey");

    val keyPref get() = "key_$id"
    val modelPref get() = "model_$id"
    val urlPref get() = "url_$id"

    fun addExtras(body: JSONObject) {
        when (this) {
            DEEPSEEK, GLM -> body.put("thinking", JSONObject().put("type", "disabled"))
            GEMINI -> body.put("reasoning_effort", "low")
        }
    }

    companion object {
        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: DEEPSEEK
    }
}
