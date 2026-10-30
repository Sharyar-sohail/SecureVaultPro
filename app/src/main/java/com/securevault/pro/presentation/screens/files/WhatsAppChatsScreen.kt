package com.securevault.pro.presentation.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.imports.StoredWhatsAppChat
import com.securevault.pro.data.imports.WhatsAppChatReader
import com.securevault.pro.data.imports.WhatsAppTranscript
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.presentation.components.ChatGlyphBoard
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.WhatsAppChatView
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.theme.AbyssElevated
import com.securevault.pro.presentation.theme.NeonCoral
import com.securevault.pro.presentation.theme.TextMuted
import com.securevault.pro.presentation.theme.TextPrimary
import com.securevault.pro.presentation.theme.TextSecondary
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One conversation as the screen draws it: the bubbles it has loaded and whether more precede. */
data class OpenedWhatsAppChat(
    val chat: StoredWhatsAppChat,
    val messages: List<WhatsAppTranscript.ChatMessage>,
    val attachments: List<NoteAttachment>,
    val hasOlder: Boolean
)

data class WhatsAppChatsState(
    val open: OpenedWhatsAppChat? = null,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val error: String? = null
)

/**
 * The read side of the conversation tables.
 *
 * A chat is opened from the rows, not from a note: the first page is the newest
 * [WhatsAppChatReader.PAGE_BUBBLES] bubbles, so a three hundred thousand message export opens in
 * the same time a small one does, and older pages arrive only when the owner asks for them.
 */
@HiltViewModel
class WhatsAppChatsViewModel @Inject constructor(
    private val reader: WhatsAppChatReader,
    private val repository: VaultRepository
) : ViewModel() {

    val chats: StateFlow<List<StoredWhatsAppChat>> = reader.observeChats()
        // A database that is still migrating must not take the screen down; the list is the only
        // thing lost, and reopening the screen reads it again.
        .catch { emit(emptyList()) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(WhatsAppChatsState())
    val state: StateFlow<WhatsAppChatsState> = _state.asStateFlow()

    fun open(chatId: String) {
        if (chatId.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val chat = reader.chat(chatId)
                    ?: throw IllegalStateException("That conversation is no longer in the vault.")
                val offset = (chat.messageCount - WhatsAppChatReader.PAGE_BUBBLES).coerceAtLeast(0)
                val bubbles = reader.bubbles(chat.chatId, WhatsAppChatReader.PAGE_BUBBLES, offset)
                val attachments = filesForChat(chatId)
                OpenedWhatsAppChat(
                    chat = chat,
                    messages = bubbles.map { it.asMessage() },
                    attachments = attachments,
                    hasOlder = offset > 0
                )
            }.fold(
                onSuccess = { opened ->
                    _state.value = WhatsAppChatsState(open = opened)
                },
                onFailure = { failure ->
                    _state.value = WhatsAppChatsState(
                        error = failure.message ?: "That conversation could not be opened."
                    )
                }
            )
        }
    }

    fun loadOlder() {
        val current = _state.value.open ?: return
        if (_state.value.loadingOlder || !current.hasOlder) return
        viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            val offset = (current.messages.size).let { loaded ->
                (current.chat.messageCount - loaded - WhatsAppChatReader.PAGE_BUBBLES).coerceAtLeast(0)
            }
            val page = runCatching {
                reader.bubbles(current.chat.chatId, WhatsAppChatReader.PAGE_BUBBLES, offset)
            }.getOrDefault(emptyList())
            _state.update { state ->
                val open = state.open ?: return@update state
                state.copy(
                    loadingOlder = false,
                    open = open.copy(
                        messages = page.map { it.asMessage() } + open.messages,
                        hasOlder = offset > 0
                    )
                )
            }
        }
    }

    fun close() {
        _state.value = WhatsAppChatsState()
    }

    suspend fun attachmentFile(id: String): File? = repository.fileVault.exportStreamToCache(id)

    private suspend fun filesForChat(chatId: String): List<NoteAttachment> =
        repository.fileVault.listFiles()
            .filter { it.noteId == chatId }
            .map { file -> file.toAttachment() }
}

private fun VaultFile.toAttachment() = NoteAttachment(
    id = id,
    name = name,
    mimeType = mimeType,
    size = size
)

/** WhatsApp's green, taken from the theme so every palette can restyle the cards. */
private val ChatGreen: Color get() = VaultColor.whatsAppBrand

@Composable
fun WhatsAppChatsScreen(
    initialChatId: String? = null,
    onBack: () -> Unit,
    viewModel: WhatsAppChatsViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(initialChatId) {
        if (!initialChatId.isNullOrBlank()) viewModel.open(initialChatId)
    }

    /** A bubble's photo opens through the system viewer, decrypted to cache for that one look. */
    val openAttachment: (NoteAttachment) -> Unit = { attachment ->
        scope.launch {
            val file = viewModel.attachmentFile(attachment.id) ?: return@launch
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, attachment.mimeType)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
    }

    val opened = state.open
    GlassScaffold(
        title = opened?.chat?.title?.takeIf { it.isNotBlank() } ?: "WhatsApp conversations",
        subtitle = opened?.let { "${it.chat.messageCount} messages · encrypted at rest" }
            ?: "${chats.size} conversation${if (chats.size == 1) "" else "s"} in this vault",
        onBack = {
            if (opened != null) viewModel.close() else onBack()
        }
    ) { padding ->
        when {
            state.loading -> Centered { CircularProgressIndicator(color = ChatGreen) }

            state.error != null -> Centered {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ThemedIcon(
                        glyph = Glyph.errorOutline,
                        contentDescription = null,
                        tint = NeonCoral,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    GhostButton(
                        text = "Back to the list",
                        icon = Glyph.chevronRight,
                        onClick = viewModel::close
                    )
                }
            }

            opened != null -> ChatBody(
                opened = opened,
                loadingOlder = state.loadingOlder,
                onLoadOlder = viewModel::loadOlder,
                onOpenAttachment = openAttachment,
                resolveFile = { attachment -> viewModel.attachmentFile(attachment.id) },
                modifier = Modifier.padding(padding)
            )

            chats.isEmpty() -> Centered {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ThemedIconTile(
                        icon = Glyph.chat,
                        contentDescription = null,
                        accent = ChatGreen,
                        size = 56.dp,
                        iconSize = 28.dp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "No conversations stored yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Import a WhatsApp export from the Files screen and it lands here, " +
                            "one bubble per row, sealed on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            else -> ChatList(chats = chats, onOpen = viewModel::open, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ChatList(
    chats: List<StoredWhatsAppChat>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(chats, key = { it.chatId }) { chat ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(chat.chatId) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIconTile(
                        icon = ChatGlyphBoard.glyphFor(chat.chatId),
                        contentDescription = null,
                        accent = ChatGreen,
                        size = 42.dp,
                        iconSize = 21.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = chat.title.ifBlank { chat.contactName },
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append("${chat.messageCount} messages")
                                if (chat.mediaCount > 0) append(" · ${chat.mediaCount} files")
                                if (chat.updatedAt > 0) append(" · ${formatShortDate(chat.updatedAt)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )
                    }
                    ThemedIcon(
                        glyph = Glyph.chevronRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBody(
    opened: OpenedWhatsAppChat,
    loadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onOpenAttachment: (NoteAttachment) -> Unit,
    resolveFile: suspend (NoteAttachment) -> File?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (opened.hasOlder) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AbyssElevated.copy(alpha = 0.55f))
                    .clickable(enabled = !loadingOlder, onClick = onLoadOlder)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (loadingOlder) {
                    CircularProgressIndicator(
                        color = ChatGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (loadingOlder) "Loading earlier messages…" else "Load earlier messages",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }
        WhatsAppChatView(
            messages = opened.messages,
            attachments = opened.attachments,
            onOpenAttachment = onOpenAttachment,
            resolveFile = resolveFile,
            modifier = Modifier
                .weight(1f)
                .clip(VaultShapes.card)
        )
    }
}
