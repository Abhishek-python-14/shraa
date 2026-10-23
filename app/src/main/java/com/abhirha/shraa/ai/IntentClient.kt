package com.abhirha.shraa.ai

import com.abhirha.shraa.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * SHRAA's AI layer. Only providers with a saved key are used; the main provider goes first and
 * the next one answers if it is busy, rate limited or its key fails. Inside one provider, server
 * errors are retried and then that provider's backup models are tried.
 * Keeps a short rolling conversation so follow-ups ("ab ... chalao") have context.
 */
class IntentClient(
    private val keyStore: ApiKeyStore,
    private val settings: SettingsStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val history = ArrayDeque<Pair<String, String>>() // (role, text); committed only on success
    private var lastTurnAt = 0L

    private sealed interface Attempt {
        data class Ok(val plan: ShraaPlan) : Attempt
        data class Fail(val reason: FailureReason, val detail: String? = null) : Attempt
    }

    /** Forget the conversation (e.g. the user said "bas"). */
    fun resetContext() {
        history.clear()
        lastTurnAt = 0L
    }

    suspend fun interpret(transcript: String): AiResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastTurnAt > CONTEXT_MS) history.clear() // conversation went cold
        val providers = activeProviders()
        if (providers.isEmpty()) return@withContext AiResult.Failure(FailureReason.NO_API_KEY)

        var last = AiResult.Failure(FailureReason.UNKNOWN)
        for (provider in providers) {
            val apiKey = keyStore.get(provider) ?: continue
            when (val out = tryProvider(provider, apiKey, primaryModel(provider), transcript)) {
                is Attempt.Ok -> {
                    history.addLast("user" to transcript)
                    history.addLast("assistant" to (out.plan.speech + actionNote(out.plan)))
                    lastTurnAt = now
                    while (history.size > MAX_HISTORY) history.removeFirst()
                    return@withContext AiResult.Success(out.plan)
                }
                is Attempt.Fail -> {
                    last = AiResult.Failure(out.reason, out.detail)
                    // No internet: another provider cannot help either.
                    if (out.reason == FailureReason.NO_INTERNET) return@withContext last
                }
            }
        }
        last
    }

    /** Tiny request with the saved key/model; reports the real result (Settings "Test" button). */
    suspend fun test(provider: AiProvider): String = withContext(Dispatchers.IO) {
        val apiKey = keyStore.get(provider) ?: return@withContext "${provider.label} ki key save nahi hai."
        val masked = if (apiKey.length > 6) apiKey.take(6) + "****" + apiKey.takeLast(4) else "****"
        val problems = mutableListOf<String>()
        for (model in modelChain(provider, primaryModel(provider))) {
            when (val out = callOnce(provider, apiKey, model, "hi")) {
                is Attempt.Ok -> {
                    val note = if (problems.isEmpty()) "" else "\n(" + problems.joinToString("; ") + ")"
                    return@withContext "OK ✓ ${provider.label} • $model$note"
                }
                is Attempt.Fail -> {
                    problems += out.detail ?: out.reason.name
                    if (out.reason == FailureReason.AUTH || out.reason == FailureReason.NO_INTERNET) break
                }
            }
        }
        "FAIL ${provider.label}\nKey sent: $masked (${apiKey.length} chars)\n" + problems.joinToString("\n")
    }

    // ---------- provider selection ----------

    /** Providers with a key: the main one first, the rest in their fixed order. */
    private fun activeProviders(): List<AiProvider> {
        val main = settings.primaryProvider
        return AiProvider.entries.filter { keyStore.get(it) != null }.sortedBy { if (it == main) 0 else 1 }
    }

    /** The model is built in: the provider's default model, with its backups tried if that one fails. */
    private fun primaryModel(provider: AiProvider): String = provider.defaultModel

    private fun modelChain(provider: AiProvider, primary: String): List<String> =
        (listOf(primary) + provider.fallbackModels).distinct()

    /** Short note of what was done, so "ab ..." follow-ups know the context. */
    private fun actionNote(plan: ShraaPlan): String =
        if (plan.actions.isEmpty()) "" else plan.actions.joinToString(prefix = " [actions: ", postfix = "]") {
            listOfNotNull(it.type, it.app, it.query ?: it.target).joinToString(" ")
        }

    // ---------- calls ----------

    /** One provider: retries plus its backup models. Returns the first success or the last failure. */
    private suspend fun tryProvider(provider: AiProvider, apiKey: String, primary: String, transcript: String): Attempt {
        var last: Attempt.Fail = Attempt.Fail(FailureReason.UNKNOWN)
        for (model in modelChain(provider, primary)) {
            for (attempt in 1..MAX_ATTEMPTS) {
                when (val out = callOnce(provider, apiKey, model, transcript)) {
                    is Attempt.Ok -> return out
                    is Attempt.Fail -> {
                        last = out
                        when (out.reason) {
                            // Overloaded: wait, retry the same model, then move on.
                            FailureReason.SERVER -> if (attempt < MAX_ATTEMPTS) delay(700L * attempt)
                            // Rate limit / bad output / model missing: next model right away.
                            FailureReason.RATE_LIMIT, FailureReason.PARSE, FailureReason.UNKNOWN -> break
                            // Wrong key or no internet: other models of this provider will not help.
                            else -> return out
                        }
                    }
                }
            }
        }
        return last
    }

    /** One HTTP call to one model. Network and parse problems never throw. */
    private fun callOnce(provider: AiProvider, apiKey: String, model: String, transcript: String): Attempt {
        val tag = "[${provider.label} • $model]"
        return try {
            val request = ProviderApi.request(provider, apiKey, model, history, transcript)
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Attempt.Fail(httpReason(response.code, raw), "$tag HTTP ${response.code}: ${errorMessage(raw)}")
                } else {
                    val plan = ProviderApi.parse(provider, raw)
                    if (plan != null) Attempt.Ok(plan) else Attempt.Fail(FailureReason.PARSE, "$tag ${raw.take(200)}")
                }
            }
        } catch (e: UnknownHostException) {
            Attempt.Fail(FailureReason.NO_INTERNET)
        } catch (e: SocketTimeoutException) {
            Attempt.Fail(FailureReason.SERVER, "$tag timeout")
        } catch (e: IOException) {
            Attempt.Fail(FailureReason.NO_INTERNET, e.message)
        } catch (e: JSONException) {
            Attempt.Fail(FailureReason.PARSE, e.message)
        }
    }

    private fun httpReason(code: Int, raw: String): FailureReason = when (code) {
        401, 403 -> FailureReason.AUTH
        400 -> if (AUTH_HINTS.any { raw.contains(it, ignoreCase = true) }) FailureReason.AUTH else FailureReason.UNKNOWN
        429 -> FailureReason.RATE_LIMIT
        in 500..599 -> FailureReason.SERVER
        else -> FailureReason.UNKNOWN
    }

    /** The human-readable message inside a provider's error body. */
    private fun errorMessage(raw: String): String = try {
        when (val err = JSONObject(raw).opt("error")) {
            is JSONObject -> err.optString("message", raw)
            is String -> err
            else -> raw
        }.take(300)
    } catch (e: Exception) {
        raw.take(300)
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val MAX_HISTORY = 12            // last 6 turns
        const val CONTEXT_MS = 180_000L       // follow-ups within 3 minutes keep the context
        val AUTH_HINTS = listOf("API_KEY_INVALID", "API key", "invalid authentication", "credentials", "unauthorized")
    }
}
