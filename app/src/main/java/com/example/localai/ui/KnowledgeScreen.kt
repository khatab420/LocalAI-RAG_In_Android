package com.example.localai.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(vm: RagViewModel, onBack: () -> Unit, openModels: () -> Unit) {
    val ctx = LocalContext.current
    val docs by vm.docs.collectAsState()
    val folders by vm.folders.collectAsState()
    val chunks by vm.chunkCount.collectAsState()
    val embedded by vm.embeddedCount.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshEmbedder() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addFolder(uri)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Knowledge base") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
        )
    }) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- status ----
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${docs.size} documents · $chunks passages · $embedded with embeddings")
                        Spacer(Modifier.height(4.dp))
                        if (vm.embedderName != null) {
                            Text("Semantic search ON (${vm.embedderName})", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "Keyword search only. For much better results download the small " +
                                    "embedding model (BGE Small, 37 MB) in Models and tap \"Use for RAG\".",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = openModels) { Text("Open Models") }
                        }
                    }
                }
            }

            // ---- actions ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = vm.busy == null) { Text("Add files") }
                    Button(onClick = { folderPicker.launch(null) }, enabled = vm.busy == null) { Text("Add folder") }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (folders.isNotEmpty()) {
                        OutlinedButton(onClick = vm::syncFolders, enabled = vm.busy == null) { Text("Re-sync folders") }
                    }
                    if (vm.embedderName != null && embedded < chunks) {
                        OutlinedButton(onClick = vm::buildEmbeddings, enabled = vm.busy == null) { Text("Build embeddings") }
                    }
                }
                Text(
                    "Supported: ${com.example.localai.rag.TextExtractor.supportedHint}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            vm.busy?.let {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
            }
            if (vm.problems.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            vm.problems.take(8).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            // ---- folders ----
            if (folders.isNotEmpty()) {
                item { Text("Folders", style = MaterialTheme.typography.titleMedium) }
                items(folders, key = { it.uri }) { f ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.deleteFolder(f) }) { Text("Remove") }
                        }
                    }
                }
            }

            // ---- test search ----
            item {
                Text("Test search", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask something…") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.testSearch(query) }, enabled = query.isNotBlank() && docs.isNotEmpty()) { Text("Search") }
                }
            }
            vm.results?.let { hits ->
                if (hits.isEmpty()) item { Text("No matching passages found.") }
                items(hits) { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(h.docName, style = MaterialTheme.typography.labelMedium)
                            Text(h.text.take(350) + if (h.text.length > 350) "…" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---- documents ----
            item { Text("Documents", style = MaterialTheme.typography.titleMedium) }
            if (docs.isEmpty()) item { Text("Nothing here yet. Add files or a folder.") }
            items(docs, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, maxLines = 1)
                            Text("${d.chunkCount} passages", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.deleteDoc(d) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
