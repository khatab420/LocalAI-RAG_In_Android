package com.example.localai.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String = "",
    val fileName: String,
    val sizeBytes: Long = 0,
    val url: String,
    val kind: String = "chat"        // "chat" or "embedding" (embedding models power RAG search)
)

@Serializable
data class Catalog(val models: List<CatalogModel>)

enum class CatalogSource { REMOTE, CACHE, BUNDLED }

data class CatalogResult(val models: List<CatalogModel>, val source: CatalogSource)

/**
 * Loads the list of downloadable models.
 *  1. If [remoteUrl] is set: fetch it (and cache it for offline use).
 *  2. If that fails: use the cached copy.
 *  3. Otherwise: use the list bundled in assets/models.json.
 */
class CatalogRepository(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val remoteUrl: String,
    private val bundledJson: () -> String
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): CatalogResult = withContext(Dispatchers.IO) {
        if (remoteUrl.isNotBlank()) {
            try {
                http.newCall(Request.Builder().url(remoteUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body!!.string()
                    val parsed = json.decodeFromString<Catalog>(body)   // validate before caching
                    cacheFile.writeText(body)
                    return@withContext CatalogResult(sanitize(parsed.models), CatalogSource.REMOTE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // fall through to cache / bundled
            }
            if (cacheFile.exists()) {
                try {
                    return@withContext CatalogResult(
                        sanitize(json.decodeFromString<Catalog>(cacheFile.readText()).models),
                        CatalogSource.CACHE
                    )
                } catch (e: Exception) { /* corrupt cache, ignore */ }
            }
        }
        CatalogResult(sanitize(json.decodeFromString<Catalog>(bundledJson()).models), CatalogSource.BUNDLED)
    }

    // never trust file names coming from the network
    private fun sanitize(list: List<CatalogModel>) = list
        .map { it.copy(fileName = File(it.fileName).name) }
        .filter { it.fileName.endsWith(".gguf", true) && it.url.startsWith("https://") }
}
