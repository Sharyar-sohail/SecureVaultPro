package com.securevault.pro.presentation.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.SecureNote
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultSearchField
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.noteAccent
import com.securevault.pro.presentation.components.noteTint
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.components.vaultShadow
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun NotesScreen(
    onOpenNote: (String) -> Unit,
    onAddNote: () -> Unit,
    onLock: () -> Unit,
    viewModel: NotesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GlassScaffold(
        title = "Notes",
        subtitle = "${state.total} encrypted notes · ${state.pinned} pinned",
        actions = {
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = VaultColor.accent
            )
        },
        fab = {
            val heroFill = VaultColor.heroFill(VaultColor.warning, VaultColor.debit)
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(heroFill)),
                contentAlignment = Alignment.Center
            ) {
                GlassIconButton(
                    icon = Glyph.add,
                    contentDescription = "Add note",
                    onClick = onAddNote,
                    tint = VaultColor.onGradient(heroFill),
                    background = Color.Transparent,
                    size = 58.dp
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            VaultSearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 6.dp),
                placeholder = "Search notes, tags, Markdown"
            )
            Spacer(Modifier.height(6.dp))
            if (state.notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = if (state.query.isBlank()) Glyph.notes else Glyph.search,
                        title = if (state.query.isBlank()) "No secure notes" else "No matching notes",
                        message = if (state.query.isBlank()) {
                            "Capture recovery codes, plans or private thoughts. Notes support Markdown, colours, pinning and encrypted attachments."
                        } else {
                            "Try another word, tag or phrase."
                        },
                        actionLabel = if (state.query.isBlank()) "Write a note" else null,
                        onAction = if (state.query.isBlank()) onAddNote else null
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = vaultGutter(),
                        end = vaultGutter(),
                        top = 4.dp,
                        bottom = 120.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp
                ) {
                    items(state.notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onOpen = { onOpenNote(note.id) },
                            onTogglePin = { viewModel.togglePin(note.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: SecureNote,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit
) {
    val palette = VaultPaletteController.current
    val accent = noteAccent(note.color)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .vaultShadow(palette = palette, shape = VaultShapes.card, accent = accent)
            .clip(VaultShapes.card)
            .background(noteTint(note.color))
            .border(1.dp, accent.copy(alpha = 0.28f), VaultShapes.card)
            .clickable(onClick = onOpen)
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = note.title.ifBlank { "Untitled note" },
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            GlassIconButton(
                icon = Glyph.pushPin,
                contentDescription = if (note.isPinned) "Unpin note" else "Pin note",
                onClick = onTogglePin,
                tint = if (note.isPinned) accent else VaultColor.textFaint,
                background = Color.Transparent,
                size = 26.dp
            )
        }
        if (note.content.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = plainPreview(note.content),
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody,
                lineHeight = 18.sp,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        } else if (note.sealed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sealed content · cannot be displayed on this device",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
        if (note.tags.isNotEmpty() || note.attachments.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                note.tags.take(2).forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent.copy(alpha = 0.8f),
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (note.attachments.isNotEmpty()) {
                    ThemedIcon(
                        glyph = Glyph.attachFile,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = note.attachments.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatShortDate(note.updatedAt),
                style = VaultType.micro(),
                color = VaultColor.textFaint
            )
            Spacer(Modifier.weight(1f))
            if (note.isPinned) {
                ThemedIcon(
                    glyph = Glyph.pushPin,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

private fun plainPreview(content: String): String {
    return content
        .replace(Regex("```[\\s\\S]*?```"), "[code]")
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "[image]")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[#*_>`~]"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .take(320)
}
