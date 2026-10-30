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
 * Project T.E.S.S.A.R.A.C.T. - Tavily Web Search & Direct Answer Client
 * Implements Bring-Your-Own-Key (BYOK) architecture for fast, live AI web search.
 * When users ask factual, live, or search-based queries, Tavily generates direct answers
 * locally without burdening the heavy LLM. If Tavily cannot answer or fails, the router
 * falls back seamlessly to Nvidia Nemotron on Nebius Cloud.
 */
object TavilySearchClient {

    private const val TAG = "TavilySearchClient"
    private const val PREFS_NAME = "tessaract_tavily_prefs"
    private const val KEY_API_KEY = "tavily_api_key"
    private const val KEY_ENABLED = "tavily_enabled"
    private const val TAVILY_ENDPOINT = "https://api.tavily.com/search"

    data class TavilySearchResultItem(
        val title: String,
        val url: String,
        val content: String
    )

    data class TavilyResponse(
        val query: String,
        val answer: String?,
        val items: List<TavilySearchResultItem>
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "")?.trim() ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun isSearchEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setSearchEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * Executes a live web search via Tavily and retrieves synthesized AI answers.
     */
    suspend fun search(context: Context, query: String): Result<TavilyResponse> {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Tavily API Key is not configured."))
        }
        return executeSearchRequest(apiKey, query)
    }

    /**
     * Tests live connectivity and API key validity against Tavily Search API.
     */
    suspend fun testConnection(apiKey: String): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key cannot be blank"))
        }

        val start = System.currentTimeMillis()
        try {
            val url = URL(TAVILY_ENDPOINT)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val requestBody = JSONObject().apply {
                put("api_key", apiKey.trim())
                put("query", "test connectivity")
                put("search_depth", "basic")
                put("max_results", 1)
                put("include_answer", false)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val latency = System.currentTimeMillis() - start

            if (responseCode in 200..299) {
                Result.success(Pair("Tavily Search Engine Online", latency))
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                val errText = BufferedReader(InputStreamReader(errorStream)).readText()
                Log.w(TAG, "Tavily connection test failed ($responseCode): $errText")
                Result.failure(Exception("HTTP $responseCode: $errText"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tavily connection test exception", e)
            Result.failure(e)
        }
    }

    private suspend fun executeSearchRequest(apiKey: String, query: String): Result<TavilyResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL(TAVILY_ENDPOINT)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val requestBody = JSONObject().apply {
                put("api_key", apiKey.trim())
                put("query", query)
                put("search_depth", "basic")
                put("include_answer", true)
                put("max_results", 3)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).readText()
                val json = JSONObject(responseText)

                val answer = json.optString("answer", "").takeIf { it.isNotBlank() }
                val resultsArray = json.optJSONArray("results") ?: JSONArray()
                val items = mutableListOf<TavilySearchResultItem>()

                for (i in 0 until resultsArray.length()) {
                    val obj = resultsArray.optJSONObject(i) ?: continue
                    items.add(
                        TavilySearchResultItem(
                            title = obj.optString("title", ""),
                            url = obj.optString("url", ""),
                            content = obj.optString("content", "")
                        )
                    )
                }

                Result.success(
                    TavilyResponse(
                        query = query,
                        answer = answer,
                        items = items
                    )
                )
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                val errText = BufferedReader(InputStreamReader(errorStream, "UTF-8")).readText()
                Log.w(TAG, "Tavily search failed ($responseCode): $errText")
                Result.failure(Exception("Tavily error HTTP $responseCode: $errText"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tavily search exception", e)
            Result.failure(e)
        }
    }

    /**
     * Extracts a spoken-friendly answer from the Tavily search response.
     */
    fun extractSpokenAnswer(response: TavilyResponse): String {
        val directAnswer = response.answer?.trim()
        if (!directAnswer.isNullOrBlank()) {
            return directAnswer
        }

        // If no direct synthesized answer, summarize the top search result
        val topResult = response.items.firstOrNull()
        if (topResult != null && topResult.content.isNotBlank()) {
            val snippet = topResult.content.take(220).replace("\n", " ")
            return "According to ${topResult.title}: $snippet"
        }

        return "Search completed for '${response.query}', but no direct answer was found."
    }
}
