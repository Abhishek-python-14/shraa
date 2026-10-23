package com.abhirha.shraa.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Builds the Gemini generateContent request and reads the tool call out of the reply. */
internal object ProviderApi {
    private val JSON = "application/json".toMediaType()

    /** [history] = (role, text) pairs with role "user" or "assistant". */
    fun request(
        provider: AiProvider,
        apiKey: String,
        model: String,
        history: List<Pair<String, String>>,
        transcript: String
    ): Request = geminiRequest(provider, apiKey, model, history, transcript)

    private fun geminiRequest(
        provider: AiProvider,
        apiKey: String,
        model: String,
        history: List<Pair<String, String>>,
        transcript: String
    ): Request {
        fun turn(role: String, text: String) = JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

        val contents = JSONArray()
        history.forEach { (role, text) -> contents.put(turn(if (role == "assistant") "model" else "user", text)) }
        contents.put(turn("user", transcript))

        val tool = planTool()
        val declaration = JSONObject()
            .put("name", TOOL_NAME)
            .put("description", tool.getString("description"))
            .put("parameters", geminiSchema(tool.getJSONObject("input_schema")))

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.2))
            .put("tools", JSONArray().put(JSONObject().put("function_declarations", JSONArray().put(declaration))))
            .put(
                "tool_config", JSONObject().put(
                    "function_calling_config", JSONObject()
                        .put("mode", "ANY")
                        .put("allowed_function_names", JSONArray().put(TOOL_NAME))
                )
            )
        return Request.Builder()
            .url("${provider.baseUrl}/$model:generateContent")
            .header("content-type", "application/json")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    /** The plan inside Gemini's function call, or null if the reply has none. */
    fun parse(@Suppress("UNUSED_PARAMETER") provider: AiProvider, raw: String): ShraaPlan? = parseGemini(raw)

    private fun parseGemini(raw: String): ShraaPlan? {
        val parts = JSONObject(raw).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val call = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
            if (call.optString("name") == TOOL_NAME) return planFromInput(call.optJSONObject("args") ?: JSONObject())
        }
        return null
    }

    /** Gemini wants upper-case type names (STRING, OBJECT, ...). */
    private fun geminiSchema(o: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.get(k)
            out.put(
                k, when {
                    k == "type" && v is String -> v.uppercase()
                    v is JSONObject -> geminiSchema(v)
                    else -> v
                }
            )
        }
        return out
    }
}
