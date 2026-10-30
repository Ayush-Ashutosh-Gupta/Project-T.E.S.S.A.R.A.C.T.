package com.example.tessaract

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Project T.E.S.S.A.R.A.C.T. - Nvidia Nemotron AI Engine
 * Connects the voice assistant to Nvidia Nemotron hosted on Nebius Cloud / Token Factory.
 *
 * Privacy & Security Architecture:
 * - Uses Bring-Your-Own-Token (BYOT) architecture.
 * - API keys are stored exclusively in private on-device SharedPreferences.
 * - Prevents centralized credential leak, payment detail exposure, and credit card risks.
 * - Compatible with hackathon evaluation: judges and users can plug in their own
 *   Nebius Token Factory key without sharing payment accounts.
 */
object NebiusNemotronClient {

    private const val TAG = "NemotronClient"
    private const val PREFS_NAME = "tessaract_nebius_nemotron_prefs"

    private const val KEY_API_KEY = "nebius_api_key"
    private const val KEY_ENDPOINT = "nebius_endpoint_url"
    private const val KEY_MODEL = "nebius_model_id"
    private const val KEY_ENABLED = "nebius_ai_enabled"
    private const val KEY_SYSTEM_PROMPT = "nebius_system_prompt"
    private const val KEY_TEMPERATURE = "nebius_temperature"

    const val DEFAULT_ENDPOINT = "https://api.tokenfactory.nebius.ai/v1"
    const val STUDIO_ENDPOINT = "https://api.studio.nebius.ai/v1"
    const val DEFAULT_MODEL = "nvidia/nemotron-4-340b-instruct"

    val AVAILABLE_MODELS = listOf(
        "nvidia/nemotron-4-340b-instruct",
        "nvidia/nemotron-mini-4b-instruct",
        "nvidia/llama-3.1-nemotron-70b-instruct"
    )

    const val DEFAULT_SYSTEM_PROMPT =
        "You are T.E.S.S.A.R.A.C.T., an advanced voice-activated cybernetic intelligence running locally on Android, augmented by Nvidia Nemotron. Provide concise, direct, helpful spoken responses under 2-3 sentences. Never use markdown formatting or bullet points since your response will be read aloud via Text-to-Speech."

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "")?.trim() ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun getEndpoint(context: Context): String {
        val endpoint = getPrefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT)?.trim() ?: DEFAULT_ENDPOINT
        return if (endpoint.isBlank()) DEFAULT_ENDPOINT else endpoint.trimEnd('/')
    }

    fun setEndpoint(context: Context, endpoint: String) {
        val clean = endpoint.trim().trimEnd('/')
        getPrefs(context).edit().putString(KEY_ENDPOINT, if (clean.isBlank()) DEFAULT_ENDPOINT else clean).apply()
    }

    fun getModel(context: Context): String {
        val model = getPrefs(context).getString(KEY_MODEL, DEFAULT_MODEL)?.trim() ?: DEFAULT_MODEL
        return if (model.isBlank()) DEFAULT_MODEL else model
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    fun isAiEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setAiEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getSystemPrompt(context: Context): String {
        return getPrefs(context).getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    }

    fun setSystemPrompt(context: Context, prompt: String) {
        getPrefs(context).edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
    }

    fun getTemperature(context: Context): Float {
        return getPrefs(context).getFloat(KEY_TEMPERATURE, 0.6f)
    }

    fun setTemperature(context: Context, temp: Float) {
        getPrefs(context).edit().putFloat(KEY_TEMPERATURE, temp).apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * Executes OpenAI-compatible chat completion inference against the user's
     * Nvidia Nemotron deployment on Nebius Cloud / Token Factory.
     */
    suspend fun queryNemotron(context: Context, userQuery: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Nebius API key is not configured in Settings."))
        }

        val endpoint = getEndpoint(context)
        val model = getModel(context)
        val systemPrompt = getSystemPrompt(context)
        val temperature = getTemperature(context)

        try {
            val url = URL("$endpoint/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 12000
                readTimeout = 25000
                doOutput = true
            }

            val requestJson = JSONObject().apply {
                put("model", model)
                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userQuery)
                    })
                }
                put("messages", messagesArray)
                put("temperature", temperature.toDouble())
                put("max_tokens", 256)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val responseText = reader.readText()
                reader.close()

                val jsonResponse = JSONObject(responseText)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    val content = message?.optString("content", "")?.trim() ?: ""
                    if (content.isNotBlank()) {
                        Log.i(TAG, "Nemotron response received (${content.length} chars)")
                        return@withContext Result.success(content)
                    }
                }
                return@withContext Result.failure(Exception("Received empty response from Nvidia Nemotron."))
            } else {
                val errStream = conn.errorStream
                val errMsg = if (errStream != null) {
                    BufferedReader(InputStreamReader(errStream)).readText()
                } else {
                    "HTTP Error $responseCode"
                }

                val readableError = when (responseCode) {
                    401 -> "Authentication failed (HTTP 401). Please check your Nebius Token Factory API Key in Settings."
                    404 -> "Model '$model' or endpoint not found (HTTP 404) at $endpoint."
                    429 -> "Rate limit or quota exceeded (HTTP 429) on Nebius Cloud."
                    500, 502, 503 -> "Nebius Cloud service temporarily unavailable (HTTP $responseCode)."
                    else -> "Nebius API Error ($responseCode): $errMsg"
                }
                Log.w(TAG, readableError)
                return@withContext Result.failure(Exception(readableError))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nemotron network request error: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Pings the Nebius Token Factory API with the provided credentials to test connectivity and model readiness.
     * Returns a pair of (Response text, Latency in milliseconds).
     */
    suspend fun testConnection(
        apiKey: String,
        endpoint: String,
        model: String
    ): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a Nebius API Key first."))
        }

        val cleanEndpoint = endpoint.trim().trimEnd('/')
        val startTime = System.currentTimeMillis()

        try {
            val url = URL("$cleanEndpoint/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
            }

            val requestJson = JSONObject().apply {
                put("model", model.trim())
                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Ping! Reply with 'Nemotron Online' in 2 words.")
                    })
                }
                put("messages", messagesArray)
                put("temperature", 0.1)
                put("max_tokens", 30)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            val latency = System.currentTimeMillis() - startTime

            if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val responseText = reader.readText()
                reader.close()

                val jsonResponse = JSONObject(responseText)
                val choices = jsonResponse.optJSONArray("choices")
                val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: "Connected"
                return@withContext Result.success(Pair(content.trim(), latency))
            } else {
                val errStream = conn.errorStream
                val errMsg = if (errStream != null) BufferedReader(InputStreamReader(errStream)).readText() else "HTTP $responseCode"
                val reason = when (responseCode) {
                    401 -> "Invalid API Key (HTTP 401). Verify your token in Nebius Token Factory."
                    404 -> "Model '$model' not found at endpoint $cleanEndpoint."
                    else -> "HTTP $responseCode: $errMsg"
                }
                return@withContext Result.failure(Exception(reason))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
