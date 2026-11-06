package com.securevault.pro.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    data class Bullet(val text: String, val depth: Int) : MarkdownBlock
    data class Ordered(val text: String, val number: Int, val depth: Int) : MarkdownBlock
    data class Task(val text: String, val done: Boolean, val depth: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
    data object Gap : MarkdownBlock
}

private val headingPattern = Regex("^(#{1,6})\\s*(.*)$")
private val rulePattern = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
private val taskPattern = Regex("^\\s*([-*+])\\s+\\[( |x|X)]\\s*(.*)$")
private val bulletPattern = Regex("^\\s*([-*+])\\s+(.*)$")
private val orderedPattern = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
private val quotePattern = Regex("^\\s*>\\s?(.*)$")

/** Two spaces of leading whitespace is one list level; deeper indents clamp so the text never runs off. */
private fun indentDepth(line: String): Int =
    (line.takeWhile { it == ' ' }.length / 2).coerceIn(0, 3)

private fun parseMarkdown(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()

    fun add(block: MarkdownBlock) {
        // Runs of blank lines are one break, not three: a note typed with breathing room
        // used to render as a column of floating gaps.
        if (block == MarkdownBlock.Gap && blocks.lastOrNull() == MarkdownBlock.Gap) return
        blocks += block
    }

    val code = StringBuilder()
    var inCode = false
    content.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val fence = line.trimStart()
        if (fence.startsWith("```") || fence.startsWith("~~~")) {
            if (inCode) {
                add(MarkdownBlock.Code(code.toString().trimEnd()))
                code.clear()
            }
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            code.appendLine(line)
            return@forEach
        }
        val heading = headingPattern.matchEntire(line)
        val task = taskPattern.matchEntire(line)
        val ordered = orderedPattern.matchEntire(line)
        val bullet = bulletPattern.matchEntire(line)
        val quote = quotePattern.matchEntire(line)
        when {
            line.isBlank() -> add(MarkdownBlock.Gap)
            rulePattern.matches(line) -> add(MarkdownBlock.Rule)
            heading != null -> {
                val body = heading.groups[2]?.value.orEmpty()
                if (body.isBlank()) add(MarkdownBlock.Rule) else {
                    add(MarkdownBlock.Heading(body, heading.groups[1]!!.value.length))
                }
            }
            task != null -> add(
                MarkdownBlock.Task(
                    text = task.groups[3]?.value.orEmpty(),
                    done = task.groups[2]?.value?.equals("x", ignoreCase = true) == true,
                    depth = indentDepth(line)
                )
            )
            ordered != null -> add(
                MarkdownBlock.Ordered(
                    text = ordered.groups[2]?.value.orEmpty(),
                    number = ordered.groups[1]?.value?.toIntOrNull() ?: 1,
                    depth = indentDepth(line)
                )
            )
            quote != null -> add(MarkdownBlock.Quote(quote.groups[1]?.value.orEmpty()))
            bullet != null -> add(
                MarkdownBlock.Bullet(bullet.groups[2]?.value.orEmpty(), indentDepth(line))
            )
            else -> add(MarkdownBlock.Paragraph(line.trimStart()))
        }
    }
    if (inCode && code.isNotEmpty()) blocks += MarkdownBlock.Code(code.toString().trimEnd())
    return blocks
}

/**
 * True when a marker opens an emphasis run rather than sitting inside a word.
 *
 * `my_secret_code` is an identifier a note keeper types constantly, and the naive scanner
 * italicised `_secret_` inside it. A `_` only starts emphasis after whitespace or punctuation.
 */
private fun opensEmphasis(text: String, index: Int, marker: Char): Boolean {
    if (index > 0) {
        val previous = text[index - 1]
        val boundary = previous == ' ' || previous == '\t' ||
            previous in listOf('(', '[', '{', '*', '_', '~', '`', '\n', ':', ';', '>')
        if (!boundary) return false
    }
    return text.getOrNull(index + 1) != ' ' && text.length > index + 1
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
            // An image is not a link: the exclamation used to survive as stray punctuation and
            // the alt text was drawn underlined. It now reads as a labelled placeholder.
            text.startsWith("![", i) -> {
                val labelEnd = text.indexOf("](", i + 2)
                val urlEnd = if (labelEnd >= 0) text.indexOf(')', labelEnd + 2) else -1
                if (labelEnd == -1 || urlEnd == -1) {
                    append(text[i])
                    i++
                } else {
                    pushStyle(
                        SpanStyle(
                            color = VaultColor.accentInk,
                            fontWeight = FontWeight.Medium,
                            fontStyle = FontStyle.Italic
                        )
                    )
                    append(text.substring(i + 2, labelEnd))
                    pop()
                    i = urlEnd + 1
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
            text.startsWith("__", i) -> {
                val end = text.indexOf("__", i + 2)
                if (end == -1) {
                    append(text[i])
                    i++
                } else {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = VaultColor.textStrong))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                }
            }
            (text[i] == '*' || text[i] == '_') &&
                opensEmphasis(text, i, text[i]) &&
                text.indexOf(text[i], i + 1) != -1 -> {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = VaultColor.textStrong))
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

/** The hanging indent a nested list item needs, so depth reads as depth. */
private fun indentModifier(depth: Int): Modifier =
    if (depth <= 0) Modifier else Modifier.padding(start = 14.dp * depth)

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
                            3 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
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
                is MarkdownBlock.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp)
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .clip(CircleShape)
                            .background(VaultColor.accent.copy(alpha = 0.55f))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = markdownInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.textBody,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MarkdownBlock.Bullet -> Row(
                    Modifier
                        .fillMaxWidth()
                        .then(indentModifier(block.depth))
                ) {
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
                // A numbered run keeps its own number instead of collapsing into a bullet with
                // the digit still glued to the text.
                is MarkdownBlock.Ordered -> Row(
                    Modifier
                        .fillMaxWidth()
                        .then(indentModifier(block.depth))
                ) {
                    Text(
                        text = "${block.number}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(
                        text = markdownInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.textBody,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MarkdownBlock.Task -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(indentModifier(block.depth))
                ) {
                    if (block.done) {
                        ThemedIcon(
                            glyph = Glyph.check,
                            contentDescription = null,
                            tint = VaultColor.credit,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.5.dp, VaultColor.hairline, RoundedCornerShape(4.dp))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = markdownInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (block.done) VaultColor.textFaint else VaultColor.textBody,
                        modifier = Modifier.weight(1f)
                    )
                }
                MarkdownBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(VaultColor.hairline)
                )
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
