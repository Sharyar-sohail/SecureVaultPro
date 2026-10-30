package com.securevault.pro.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes

private sealed interface MarkdownBlock {
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data object Gap : MarkdownBlock
}

private fun parseMarkdown(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val code = StringBuilder()
    var inCode = false
    content.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
                code.clear()
            }
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code.appendLine(line)
            return@forEach
        }
        when {
            line.isBlank() -> blocks += MarkdownBlock.Gap
            line.startsWith("### ") -> blocks += MarkdownBlock.Heading(line.removePrefix("### "), 3)
            line.startsWith("## ") -> blocks += MarkdownBlock.Heading(line.removePrefix("## "), 2)
            line.startsWith("# ") -> blocks += MarkdownBlock.Heading(line.removePrefix("# "), 1)
            line.startsWith("- ") || line.startsWith("* ") ->
                blocks += MarkdownBlock.Bullet(line.drop(2))
            line.matches(Regex("^\\d+\\.\\s+.*")) ->
                blocks += MarkdownBlock.Bullet(line)
            else -> blocks += MarkdownBlock.Paragraph(line)
        }
    }
    if (inCode && code.isNotEmpty()) blocks += MarkdownBlock.Code(code.toString().trimEnd())
    return blocks
}

private fun markdownInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end == -1) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = VaultColor.textStrong))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end == -1) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = VaultColor.accent.copy(alpha = 0.10f),
                            color = VaultColor.accent
                        )
                    )
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                }
            }
            text.startsWith("*", i) || text.startsWith("_", i) -> {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                if (end == -1) {
                    append(text[i])
                    i++
                } else {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = VaultColor.textStrong))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                }
            }
            text.startsWith("[", i) -> {
                val labelEnd = text.indexOf("](", i + 1)
                val urlEnd = if (labelEnd >= 0) text.indexOf(')', labelEnd + 2) else -1
                if (labelEnd == -1 || urlEnd == -1) {
                    append(text[i])
                    i++
                } else {
                    pushStyle(
                        SpanStyle(
                            color = VaultColor.accent,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                    append(text.substring(i + 1, labelEnd))
                    pop()
                    i = urlEnd + 1
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end == -1) {
                    append(text[i])
                    i++
                } else {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = VaultColor.textFaint))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val palette = VaultPaletteController.current
    val blocks = remember(content) { parseMarkdown(content) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = if (compact) Arrangement.spacedBy(2.dp) else Arrangement.spacedBy(5.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    Spacer(Modifier.height(if (block.level == 1) 6.dp else 2.dp))
                    Text(
                        text = markdownInline(block.text),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        color = VaultColor.textStrong
                    )
                }
                is MarkdownBlock.Paragraph -> Text(
                    text = markdownInline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColor.textBody,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                )
                is MarkdownBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.accent,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = markdownInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.textBody,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MarkdownBlock.Code -> Text(
                    text = block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceOverlay.copy(alpha = 0.04f), VaultShapes.input)
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    color = VaultColor.accent.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                )
                MarkdownBlock.Gap -> Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            }
        }
    }
}
