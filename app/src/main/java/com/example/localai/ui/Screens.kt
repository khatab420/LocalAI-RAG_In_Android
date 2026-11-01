package com.example.localai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localai.data.CatalogModel
import com.example.localai.data.CatalogSource
import java.io.File
import java.text.DateFormat
import java.util.Date

// ============================================================================================
// Models screen: downloadable models (cloud list), downloads, import from storage, load
// ============================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel, openChat: () -> Unit, openHistory: () -> Unit, openDocs: () -> Unit) {
    val ctx = LocalContext.current
    val works by vm.works.collectAsState()
    var showUrlDialog by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(ctx.contentResolver, uri)
    }

    if (showUrlDialog) UrlDialog(
        onDismiss = { showUrlDialog = false },
        onConfirm = { showUrlDialog = false; vm.downloadCustom(it) }
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Column {
                        Text("Models", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "On-device · ${vm.local.size} ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = openDocs) {
                        Icon(Icons.Rounded.Description, contentDescription = "Knowledge base")
                    }
                    IconButton(onClick = openHistory) {
                        Icon(Icons.Rounded.History, contentDescription = "Chat history")
                    }
                    IconButton(onClick = openChat) {
                        Icon(Icons.Rounded.ChatBubble, contentDescription = "Open chat")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            vm.busy?.let {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(it)
                }
            }
            vm.message?.let {
                item {
                    Card(Modifier.fillMaxWidth().clickable { vm.clearMessage() }) {
                        Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ---- on this device ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On this device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import .gguf") }
                }
            }
            if (vm.local.isEmpty()) item { Text("No models yet. Download one below, or import a .gguf file from your phone.") }
            items(vm.local, key = { it.name }) { f -> LocalRow(f, vm, openChat) }

            // ---- downloads that are not part of the catalog (custom links) ----
            val catalogFiles = vm.catalog.map { it.fileName }.toSet()
            val customActive = works
                .filter { vm.fileNameOf(it) !in catalogFiles }
                .mapNotNull { info -> vm.fileNameOf(info)?.let { it to vm.stateFor(works, it, false) } }
                .filter { it.second is DownloadState.Running }
            items(customActive, key = { "custom_" + it.first }) { (name, state) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(name)
                        DownloadBar(state as DownloadState.Running) { vm.cancelDownload(name) }
                    }
                }
            }

            // ---- cloud list ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Available to download", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.refreshCatalog() }) { Text("Refresh") }
                    TextButton(onClick = { showUrlDialog = true }) { Text("Custom URL") }
                }
                val note = when (vm.catalogSource) {
                    CatalogSource.REMOTE -> "Live list from the cloud"
                    CatalogSource.CACHE -> "Offline: showing the last saved list"
                    CatalogSource.BUNDLED -> "Built-in list"
                }
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
            items(vm.catalog, key = { it.id }) { m ->
                val exists = vm.local.any { it.name == m.fileName }
                CatalogRow(m, vm.stateFor(works, m.fileName, exists), vm)
            }
        }
    }
}

@Composable
private fun LocalRow(file: File, vm: ModelsViewModel, openChat: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            val warn = if (!vm.fitsRam(file)) "  ·  may not fit in RAM" else ""
            val kind = if (vm.isEmbeddingModel(file.name)) "  ·  embedding model" else ""
            Text("${fmtSize(file.length())}$kind$warn", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.delete(file) }) { Text("Delete") }
                if (vm.isEmbeddingModel(file.name)) {
                    Button(onClick = { vm.loadEmbedder(file) }) {
                        Text(if (vm.embedderName == file.name) "Active for RAG" else "Use for RAG")
                    }
                } else {
                    Button(onClick = { vm.load(file, openChat) }) { Text("Load & chat") }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(m: CatalogModel, state: DownloadState, vm: ModelsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${m.description}  ·  ~${fmtSize(m.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            when (state) {
                DownloadState.Done -> Text("Downloaded ✓")
                is DownloadState.Running -> DownloadBar(state) { vm.cancelDownload(m.fileName) }
                is DownloadState.Failed -> {
                    Text(state.reason ?: "Download failed", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.download(m) }) { Text("Retry") }
                }
                DownloadState.Idle -> Button(onClick = { vm.download(m) }) { Text("Download") }
            }
        }
    }
}

@Composable
private fun DownloadBar(state: DownloadState.Running, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val p = state.progress
        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f))
        else LinearProgressIndicator(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (p != null) Text("${(p * 100).toInt()}%")
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from URL") },
        text = {
            Column {
                Text("Paste a direct https link to a .gguf file (for example from Hugging Face).")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, singleLine = false, placeholder = { Text("https://…/model.gguf") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

// ============================================================================================
// History screen
// ============================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: ChatViewModel, onBack: () -> Unit, onOpen: () -> Unit) {
    val chats by vm.chats.collectAsState()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Column {
                        Text("Chat history", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (chats.isEmpty()) "No saved chats" else "${chats.size} saved chats",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (chats.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Chats you start are saved here automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(chats, key = { it.id }) { chat ->
                Surface(
                    onClick = { vm.openChat(chat.id); onOpen() },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                chat.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                fmt.format(Date(chat.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.deleteChat(chat.id) }) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete chat",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
