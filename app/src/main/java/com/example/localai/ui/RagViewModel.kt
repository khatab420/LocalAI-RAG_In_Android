package com.example.localai.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.AppContainer
import com.example.localai.rag.DocumentEntity
import com.example.localai.rag.FolderEntity
import com.example.localai.rag.Hit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RagViewModel(private val c: AppContainer) : ViewModel() {

    val docs = c.rag.documents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders = c.rag.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val chunkCount = c.rag.chunkCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val embeddedCount = c.rag.embeddedCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    var busy by mutableStateOf<String?>(null); private set
    var problems by mutableStateOf<List<String>>(emptyList()); private set
    var results by mutableStateOf<List<Hit>?>(null); private set
    var embedderName by mutableStateOf(c.embedder.loadedName); private set

    private var job: Job? = null

    fun refreshEmbedder() { embedderName = c.embedder.loadedName }

    private fun launchJob(initial: String, block: suspend (report: (String) -> Unit) -> List<String>) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            busy = initial
            problems = emptyList()
            try {
                problems = block { busy = it }
            } catch (e: CancellationException) {
                problems = listOf("Cancelled")
                throw e
            } catch (e: Throwable) {
                problems = listOf(e.message ?: "Failed")
            } finally {
                busy = null
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun addFiles(uris: List<Uri>) = launchJob("Adding files…") { report -> c.rag.addFiles(uris, report) }
    fun addFolder(uri: Uri) = launchJob("Adding folder…") { report -> c.rag.addFolder(uri, report) }
    fun syncFolders() = launchJob("Syncing folders…") { report -> c.rag.syncFolders(report) }
    fun buildEmbeddings() = launchJob("Building embeddings…") { report -> c.rag.embedMissing(report) }

    fun deleteDoc(doc: DocumentEntity) {
        viewModelScope.launch { c.rag.deleteDocument(doc.id) }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch { c.rag.deleteFolder(folder) }
    }

    /** Lets the user check what retrieval returns for a question. */
    fun testSearch(query: String) {
        viewModelScope.launch {
            results = runCatching { c.rag.search(query, 5) }.getOrElse {
                problems = listOf("Search failed: ${it.message}")
                emptyList()
            }
        }
    }

    fun clearResults() { results = null }
}
