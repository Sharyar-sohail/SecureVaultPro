package com.securevault.pro.presentation.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.data.imports.WhatsAppTranscript
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultContrast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val WhatsAppTick = Color(0xFF53BDEB)
private val WhatsAppTeal = Color(0xFF00A884)

/**
 * The chat surface as WhatsApp paints it, in both of its appearances. The archived conversation
 * keeps WhatsApp's colour language on purpose — this is the clone the owner asked to recognise —
 * while the choice between the two is read from the active theme's surface.
 */
private data class ChatInk(
    val wallpaper: Color,
    val incoming: Color,
    val outgoing: Color,
    val text: Color,
    val meta: Color,
    val chip: Color,
    val chipText: Color,
    val card: Color
) {
    val dark: Boolean get() = text.red > 0.5f

    companion object {
        fun of(dark: Boolean): ChatInk = if (dark) {
            ChatInk(
                wallpaper = Color(0xFF0B141A),
                incoming = Color(0xFF1F2C34),
                outgoing = Color(0xFF005C4B),
                text = Color(0xFFE9EDEF),
                meta = Color(0xFF8696A0),
                chip = Color(0xFF182229),
                chipText = Color(0xFF8696A0),
                card = Color(0xFF2F3B45)
            )
        } else {
            ChatInk(
                wallpaper = Color(0xFFEFEAE2),
                incoming = Color(0xFFFFFFFF),
                outgoing = Color(0xFFD9FDD3),
                text = Color(0xFF111B21),
                meta = Color(0xFF667781),
                chip = Color(0xFFF0F0F0),
                chipText = Color(0xFF54656F),
                card = Color(0xFFEAE6DE)
            )
        }
    }
}

/** The hues WhatsApp hands out to group participants, one per speaker. */
private val SpeakerColors = listOf(
    Color(0xFFE5425E), Color(0xFFC4306F), Color(0xFF7E55D3), Color(0xFF3F73E7),
    Color(0xFF1F9B8F), Color(0xFF7FA300), Color(0xFFE08A00), Color(0xFF6D5BD6)
)

private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow
    data class Line(
        val message: WhatsAppTranscript.ChatMessage,
        val outgoing: Boolean,
        val speakerColor: Color?
    ) : ChatRow
}

/**
 * An archived WhatsApp export drawn the way WhatsApp drew it: dated bubbles, the speaker's name
 * above a group message, the time tucked into the corner, and the chat's own media inside the
 * bubble that carried it. It reads the note, it never rewrites it.
 */
@Composable
fun WhatsAppChatView(
    messages: List<WhatsAppTranscript.ChatMessage>,
    attachments: List<NoteAttachment>,
    onOpenAttachment: (NoteAttachment) -> Unit,
    resolveFile: suspend (NoteAttachment) -> File?,
    modifier: Modifier = Modifier
) {
    val ink = ChatInk.of(
        VaultContrast.luminance(VaultColor.surface) < 0.28
    )
    val byName = remember(attachments) {
        LinkedHashMap<String, NoteAttachment>().apply {
            attachments.forEach { put(it.name.lowercase(), it) }
            attachments.forEach { put(WhatsAppTranscript.equivalenceKey(it.name).lowercase(), it) }
        }
    }
    val speakers = remember(messages) {
        val seen = LinkedHashSet<String>()
        messages.forEach { message -> message.sender?.let { seen.add(it) } }
        seen.toList()
    }
    var mine by remember(speakers) {
        mutableStateOf(speakers.firstOrNull { it.equals("You", ignoreCase = true) })
    }
    val rows = remember(messages, mine) { rowsFor(messages, mine, speakers) }

    Column(modifier = modifier.fillMaxWidth().background(ink.wallpaper)) {
        if (speakers.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = "Your name", color = ink.meta, fontSize = 11.sp)
                speakers.forEach { speaker ->
                    val selected = speaker == mine
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) ink.outgoing else ink.card)
                            .clickable { mine = if (selected) null else speaker }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = speaker,
                            color = if (selected) ink.text else ink.meta,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(rows) { row ->
                when (row) {
                    is ChatRow.Day -> DayChip(row.label, ink)
                    is ChatRow.Line -> ChatBubble(
                        message = row.message,
                        outgoing = row.outgoing,
                        speakerColor = row.speakerColor,
                        ink = ink,
                        attachments = byName,
                        onOpenAttachment = onOpenAttachment,
                        resolveFile = resolveFile
                    )
                }
            }
        }
    }
}

/**
 * Group exports name their senders, so tapping one moves that person's bubbles to the right.
 * A 1:1 export names nobody: its lines stay on one side rather than inventing a split the file
 * never recorded.
 */
private fun rowsFor(
    messages: List<WhatsAppTranscript.ChatMessage>,
    mine: String?,
    speakers: List<String>
): List<ChatRow> {
    val colors = speakers.mapIndexed { index, name -> name to SpeakerColors[index % SpeakerColors.size] }
        .toMap()
    val rows = ArrayList<ChatRow>(messages.size + 4)
    var day = ""
    messages.forEach { message ->
        if (message.day.isNotBlank() && message.day != day) {
            day = message.day
            rows += ChatRow.Day(day)
        }
        rows += ChatRow.Line(
            message = message,
            outgoing = mine != null && message.sender == mine,
            speakerColor = message.sender?.let(colors::get)
        )
    }
    return rows
}

@Composable
private fun DayChip(label: String, ink: ChatInk) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(ink.chip)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(text = label.uppercase(), color = ink.chipText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChatBubble(
    message: WhatsAppTranscript.ChatMessage,
    outgoing: Boolean,
    speakerColor: Color?,
    ink: ChatInk,
    attachments: Map<String, NoteAttachment>,
    onOpenAttachment: (NoteAttachment) -> Unit,
    resolveFile: suspend (NoteAttachment) -> File?
) {
    if (message.notice && message.files.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ink.chip)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.body,
                    color = ink.chipText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (outgoing) 12.dp else 4.dp,
                        topEnd = if (outgoing) 4.dp else 12.dp,
                        bottomEnd = 12.dp,
                        bottomStart = 12.dp
                    )
                )
                .background(if (outgoing) ink.outgoing else ink.incoming)
                .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
        ) {
            val sender = message.sender
            if (!outgoing && speakerColor != null && sender != null) {
                Text(
                    text = sender,
                    color = speakerColor,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            message.files.forEach { name ->
                MediaBlock(
                    fileName = name,
                    attachment = attachments[name.lowercase()],
                    ink = ink,
                    onOpen = onOpenAttachment,
                    resolveFile = resolveFile
                )
            }
            if (message.body.isNotBlank()) {
                Text(
                    text = message.body,
                    color = ink.text,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.End
            ) {
                Text(text = message.clock, color = ink.meta, fontSize = 11.sp)
                if (outgoing) {
                    Spacer(Modifier.width(3.dp))
                    ThemedIcon(
                        glyph = Glyph.doneAll,
                        contentDescription = null,
                        tint = if (ink.dark) WhatsAppTick else WhatsAppTeal,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaBlock(
    fileName: String,
    attachment: NoteAttachment?,
    ink: ChatInk,
    onOpen: (NoteAttachment) -> Unit,
    resolveFile: suspend (NoteAttachment) -> File?
) {
    if (attachment == null) {
        AttachmentCard(
            fileName = fileName,
            subtitle = "Media not included in this export",
            icon = Glyph.description,
            tint = ink.meta,
            ink = ink,
            onClick = null
        )
        return
    }
    when (WhatsAppTranscript.kindOf(fileName)) {
        WhatsAppTranscript.MediaKind.Image -> ImageMedia(
            attachment = attachment,
            ink = ink,
            onOpen = onOpen,
            resolveFile = resolveFile
        )

        WhatsAppTranscript.MediaKind.Video -> AttachmentCard(
            fileName = fileName,
            subtitle = "Video · ${formatBytes(attachment.size)}",
            icon = Glyph.videocam,
            tint = ink.meta,
            ink = ink,
            onClick = { onOpen(attachment) }
        )

        WhatsAppTranscript.MediaKind.Audio -> AttachmentCard(
            fileName = fileName,
            subtitle = "Voice note · ${formatBytes(attachment.size)}",
            icon = Glyph.playArrow,
            tint = WhatsAppTeal,
            ink = ink,
            circular = true,
            onClick = { onOpen(attachment) }
        )

        else -> AttachmentCard(
            fileName = fileName,
            subtitle = "Document · ${formatBytes(attachment.size)}",
            icon = Glyph.description,
            tint = WhatsAppTick,
            ink = ink,
            onClick = { onOpen(attachment) }
        )
    }
}

@Composable
private fun ImageMedia(
    attachment: NoteAttachment,
    ink: ChatInk,
    onOpen: (NoteAttachment) -> Unit,
    resolveFile: suspend (NoteAttachment) -> File?
) {
    val bitmap = rememberThumbnail(attachment, resolveFile)
    if (bitmap == null) {
        AttachmentCard(
            fileName = attachment.name,
            subtitle = "Photo · ${formatBytes(attachment.size)}",
            icon = Glyph.image,
            tint = ink.meta,
            ink = ink,
            onClick = { onOpen(attachment) }
        )
        return
    }
    Box(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable { onOpen(attachment) }
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(240.dp).height(180.dp)
        )
    }
}

@Composable
private fun AttachmentCard(
    fileName: String,
    subtitle: String,
    icon: Glyph,
    tint: Color,
    ink: ChatInk,
    circular: Boolean = false,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(ink.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(if (circular) CircleShape else RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            ThemedIcon(
                glyph = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = fileName.substringAfterLast('/'),
                color = ink.text,
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                maxLines = 2
            )
            Text(
                text = subtitle,
                color = ink.meta,
                fontSize = 11.5.sp,
                maxLines = 1
            )
        }
    }
}

/** Decodes one photo at bubble size, once, and keeps the last few warm for the scroll back. */
@Composable
private fun rememberThumbnail(
    attachment: NoteAttachment,
    resolveFile: suspend (NoteAttachment) -> File?
): ImageBitmap? {
    val cached = remember(attachment.id) { ThumbnailCache.get(attachment.id) }
    if (cached != null) return cached
    val loaded by produceState<ImageBitmap?>(initialValue = null, key1 = attachment.id) {
        value = resolveFile(attachment)?.let { decodeThumbnail(it) }
    }
    loaded?.let { ThumbnailCache.put(attachment.id, it) }
    return loaded
}

private val ThumbnailCache = LruCache<String, ImageBitmap>(24)

private suspend fun decodeThumbnail(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 520) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )?.asImageBitmap()
    }.getOrNull()
}
