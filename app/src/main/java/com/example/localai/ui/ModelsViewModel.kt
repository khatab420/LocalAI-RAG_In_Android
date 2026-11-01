package com.example.localai.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.example.localai.AppContainer
import com.example.localai.data.CatalogModel
import com.example.localai.data.CatalogSource
import com.example.localai.data.ModelDownloadWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Done : DownloadState
    data class Running(val progress: Float?) : DownloadState
    data class Failed(val reason: String?) : DownloadState
}

class ModelsViewModel(private val c: AppContainer) : ViewModel() {

    var catalog by mutableStateOf<List<CatalogModel>>(emptyList()); private set
    var catalogSource by mutableStateOf(CatalogSource.BUNDLED); private set
    var local by mutableStateOf<List<File>>(c.store.list()); private set
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set

    var embedderName by mutableStateOf(c.embedder.loadedName); private set

    val totalRam: Long get() = c.totalRamBytes

    val works: StateFlow<List<WorkInfo>> = c.workManager
        .getWorkInfosByTagFlow(TAG_ALL)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refreshCatalog()
        viewModelScope.launch { works.collect { local = c.store.list() } }
    }

    fun clearMessage() { message = null }

    fun refreshCatalog() = viewModelScope.launch {
        runCatching { c.catalog.load() }
            .onSuccess { catalog = it.models; catalogSource = it.source }
            .onFailure { message = "Could not read the model list: ${it.message}" }
        local = c.store.list()
    }

    // ---- downloads -------------------------------------------------------------------------

    fun download(m: CatalogModel) = enqueue(m.url, m.fileName)

    fun downloadCustom(rawUrl: String) {
        var url = rawUrl.trim()
        // Hugging Face "blob" page links -> direct file links
        if (url.contains("huggingface.co") && url.contains("/blob/")) url = url.replace("/blob/", "/resolve/")
        url = url.substringBefore("?download=")
        val name = (runCatching { Uri.parse(url).lastPathSegment }.getOrNull() ?: "")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!url.startsWith("https://") || !name.endsWith(".gguf", ignoreCase = true)) {
            message = "Enter a direct https:// link to a .gguf file"
            return
        }
        enqueue(url, name)
    }

    private fun enqueue(url: String, fileName: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf("url" to url, "fileName" to fileName))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG_ALL)
            .addTag("$TAG_FILE$fileName")
            .build()
        c.workManager.enqueueUniqueWork("dl_$fileName", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelDownload(fileName: String) {
        c.workManager.cancelUniqueWork("dl_$fileName")
    }

    fun fileNameOf(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(TAG_FILE) }?.removePrefix(TAG_FILE)

    fun stateFor(infos: List<WorkInfo>, fileName: String, existsLocally: Boolean): DownloadState {
        if (existsLocally) return DownloadState.Done
        val info = infos.firstOrNull { "$TAG_FILE$fileName" in it.tags } ?: return DownloadState.Idle
        return when (info.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val done = info.progress.getLong("done", 0)
                val total = info.progress.getLong("total", -1)
                DownloadState.Running(if (total > 0) done.toFloat() / total else null)
            }
            WorkInfo.State.FAILED -> DownloadState.Failed(info.outputData.getString("error"))
            else -> DownloadState.Idle
        }
    }

    // ---- local files -----------------------------------------------------------------------

    fun importFile(cr: ContentResolver, uri: Uri) = viewModelScope.launch {
        busy = "Importing…"
        runCatching { c.store.import(cr, uri) { busy = "Importing… ${(it * 100).toInt()}%" } }
            .onFailure { message = it.message ?: "Import failed" }
        busy = null
        local = c.store.list()
    }

    fun isEmbeddingModel(name: String): Boolean {
        val n = name.lowercase()
        return listOf("embed", "bge-", "minilm", "e5-", "gte-", "nomic").any { n.contains(it) }
    }

    /** Loads a small embedding model used by the Docs (RAG) search. Chat model stays loaded. */
    fun loadEmbedder(file: File) {
        viewModelScope.launch {
            busy = "Loading embedding model…"
            val ok = c.embedder.load(file.absolutePath, file.name)
            busy = null
            if (ok) {
                c.prefs.edit().putString("embedder", file.name).apply()
                embedderName = file.name
                message = "Embedding model ready. Open Docs and tap \"Build embeddings\" for existing documents."
            } else {
                message = "Failed to load the embedding model."
            }
        }
    }

    fun delete(file: File) {
        if (c.embedder.loadedName == file.name) {
            viewModelScope.launch {
                c.embedder.unload()
                c.prefs.edit().remove("embedder").apply()
                embedderName = null
                file.delete(); local = c.store.list()
            }
            return
        }
        if (c.engine.loadedName == file.name) {
            viewModelScope.launch { c.engine.unload(); file.delete(); local = c.store.list() }
        } else {
            file.delete()
            local = c.store.list()
        }
    }

    fun fitsRam(file: File): Boolean = file.length() < totalRam * 0.55
    private fun hopeless(file: File): Boolean = file.length() > totalRam * 0.85

    fun load(file: File, onReady: () -> Unit) {
        if (hopeless(file)) {
            message = "This model is too large for this phone's RAM."
            return
        }
        viewModelScope.launch {
            busy = "Loading ${file.name}…"
            val ok = c.engine.load(file.absolutePath, file.name)
            busy = null
            if (ok) onReady() else message = "Failed to load the model (not enough memory or unsupported file)."
        }
    }

    companion object {
        const val TAG_ALL = "model_dl"
        const val TAG_FILE = "file:"
    }
}
