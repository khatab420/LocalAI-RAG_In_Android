package com.example.localai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.localai.llm.ChatMessage
import com.example.localai.ui.markdown.MarkdownText
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Explain it like I'm five",
    "Summarize the key points",
    "Write a Kotlin function",
    "Draft a short email"
)

/**
 * Conversation surface. Answers are rendered as formatted text (no bubble), the way
 * mainstream assistants do it, with copy / regenerate actions and streamed output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenDocs: () -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    var autoScroll by remember { mutableStateOf(true) }
    val lastIndex = vm.messages.lastIndex
    val lastLength = vm.messages.lastOrNull()?.content?.length ?: 0
    val streaming = vm.generating
    // trailing status / error rows sit after the messages inside the same list
    val tailRows = (if (vm.status != null) 1 else 0) + (if (vm.error != null) 1 else 0)
    val lastRow = lastIndex + tailRows

    LaunchedEffect(Unit) { vm.refreshModel() }

    // Follow the output only while the reader is parked at the bottom of the list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) autoScroll = !listState.canScrollForward
        }
    }

    LaunchedEffect(vm.messages.size, tailRows) {
        if (vm.messages.isEmpty()) {
            autoScroll = true
        } else {
            autoScroll = true
            listState.animateScrollToItem(lastRow)
        }
    }

    LaunchedEffect(lastLength) {
        if (autoScroll && vm.messages.isNotEmpty()) listState.scrollToItem(lastRow)
    }

    val canScrollDown by remember(listState) { derivedStateOf { listState.canScrollForward } }

    fun submit() {
        if (input.isBlank() || streaming) return
        val text = input
        input = ""
        autoScroll = true
        vm.send(text)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to models")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = prettyModelName(vm.modelName) ?: "Local AI",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (vm.modelName == null) "No model loaded" else "On-device · private",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    if (vm.modelName != null) {
                        FilterChip(
                            selected = vm.ragEnabled,
                            onClick = { vm.setRag(!vm.ragEnabled) },
                            label = { Text("Docs") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("New chat") },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                onClick = {
                                    menuOpen = false
                                    focusManager.clearFocus()
                                    vm.newChat()
                                    input = ""
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Knowledge base") },
                                leadingIcon = { Icon(Icons.Rounded.Description, null) },
                                onClick = { menuOpen = false; onOpenDocs() }
                            )
                            DropdownMenuItem(
                                text = { Text("Chat history") },
                                leadingIcon = { Icon(Icons.Rounded.History, null) },
                                onClick = { menuOpen = false; onOpenHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text("Models") },
                                leadingIcon = { Icon(Icons.Rounded.Memory, null) },
                                onClick = { menuOpen = false; onBack() }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Composer(
                input = input,
                onInputChange = { input = it },
                generating = streaming,
                enabled = vm.modelName != null,
                modelName = vm.modelName,
                focusRequester = focusRequester,
                onSend = { submit() },
                onStop = vm::stop
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                vm.modelName == null && vm.messages.isEmpty() -> NoModelState(onBrowse = onBack)

                vm.messages.isEmpty() -> EmptyState(
                    hasDocs = vm.ragEnabled,
                    onSuggestion = {
                        input = it
                        focusRequester.requestFocus()
                    }
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    itemsIndexed(vm.messages) { index, message ->
                        // the streaming row changes every few milliseconds: animating it would
                        // re-run placement animations on top of each new token batch
                        val rowModifier = if (streaming && index == lastIndex) Modifier
                        else Modifier.animateItem()
                        MessageRow(
                            modifier = rowModifier,
                            message = message,
                            isStreaming = streaming && index == lastIndex,
                            isLastAssistant = index == lastIndex && message.role == "assistant",
                            caretAlpha = if (streaming && index == lastIndex) rememberCaretAlpha() else null,
                            onRegenerate = vm::regenerate
                        )
                    }
                    vm.status?.let { status ->
                        item {
                            StatusRow(status)
                        }
                    }
                    vm.error?.let { error ->
                        item {
                            ErrorCard(error, onDismiss = vm::clearError)
                        }
                    }
                }
            }

            if (vm.error != null && vm.messages.isEmpty() && vm.modelName != null) {
                ErrorCard(
                    error = vm.error!!,
                    onDismiss = vm::clearError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }

            if (canScrollDown && vm.messages.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = {
                        autoScroll = true
                        scope.launch { listState.animateScrollToItem(lastRow) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "Scroll to latest",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- messages

@Composable
private fun MessageRow(
    modifier: Modifier,
    message: ChatMessage,
    isStreaming: Boolean,
    isLastAssistant: Boolean,
    caretAlpha: Float?,
    onRegenerate: () -> Unit
) {
    Column(modifier) {
        if (message.role == "user") {
            UserMessage(message)
        } else {
            AssistantMessage(
                message = message,
                isStreaming = isStreaming,
                isLastAssistant = isLastAssistant,
                caretAlpha = caretAlpha,
                onRegenerate = onRegenerate
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    isStreaming: Boolean,
    isLastAssistant: Boolean,
    caretAlpha: Float?,
    onRegenerate: () -> Unit
) {
    Row(Modifier.fillMaxWidth()) {
        AssistantAvatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (message.content.isEmpty() && isStreaming) {
                TypingIndicator()
            } else if (isStreaming) {
                // Selection is pointless mid-answer and its gesture plumbing competes with the
                // Stop tap, so it is only installed once the answer is complete.
                MarkdownText(
                    markdown = message.content,
                    caretAlpha = caretAlpha,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            } else {
                SelectionContainer {
                    MarkdownText(
                        markdown = message.content,
                        caretAlpha = caretAlpha,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }

            if (message.sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SourceChips(message.sources)
            }

            if (!isStreaming && message.content.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                MessageActions(
                    content = message.content,
                    canRegenerate = isLastAssistant,
                    onRegenerate = onRegenerate
                )
            }
        }
    }
}

@Composable
private fun AssistantAvatar() {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun UserMessage(message: ChatMessage) {
    Row(Modifier.fillMaxWidth()) {
        // keeps the bubble content-sized while never taking more than 85% of the row
        Spacer(Modifier.weight(0.15f))
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.weight(0.85f, fill = false)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun MessageActions(content: String, canRegenerate: Boolean, onRegenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(content) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(content))
                copied = true
            },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy answer",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Rounded.Autorenew,
                    contentDescription = "Regenerate answer",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (copied) {
            Text(
                "Copied",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceChips(sources: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Sources",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sources.forEach { source ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Description,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ transient ui

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "Thinking…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusRow(status: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorCard(error: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                error,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------- empty states

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(hasDocs: Boolean, onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "How can I help you today?",
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasDocs) "Answers will be grounded in your documents."
            else "Everything stays on this device. Nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SUGGESTIONS.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestion(suggestion) },
                    label = { Text(suggestion, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }
    }
}

@Composable
private fun NoModelState(onBrowse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Memory,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("No model loaded", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Download a model or import a .gguf file, then tap \"Load & chat\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBrowse) { Text("Browse models") }
    }
}

// ------------------------------------------------------------------------------------ composer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    generating: Boolean,
    enabled: Boolean,
    modelName: String?,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "composerBorder"
    )
    val sendEnabled = input.isNotBlank() && enabled

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(start = 18.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 9.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused },
                        enabled = enabled || generating,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send,
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                        keyboardActions = KeyboardActions(onSend = { if (sendEnabled) onSend() }),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = if (modelName == null) "Load a model to start"
                                        else "Ask anything…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(
                        onClick = { if (generating) onStop else if (sendEnabled) onSend() },
                        enabled = generating || sendEnabled,
                        modifier = Modifier.size(38.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = if (generating) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (generating) "Stop generating" else "Send",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "On-device model · answers can be wrong",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ------------------------------------------------------------------------------------ helpers

/**
 * Blinks the streaming caret twice a second. Deliberately not a per-frame animation: the
 * caret lives inside the answer text, so every frame used to re-parse and re-lay out the
 * whole message (which is what made the UI feel stuck, and Stop with it).
 */
@Composable
private fun rememberCaretAlpha(): Float {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(530)
            visible = !visible
        }
    }
    return if (visible) 1f else 0f
}

private fun prettyModelName(name: String?): String? =
    name?.substringAfterLast('/')
        ?.removeSuffix(".gguf")
        ?.takeIf { it.isNotBlank() }
