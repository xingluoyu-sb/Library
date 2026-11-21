@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.library.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染：标题、无序/有序列表、引用、代码块、分隔线，
 * 以及行内的 **粗体**、*斜体*、`代码`、[链接](url)。
 * 不引第三方库，也不支持表格/图片这类重排版的东西。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, primary, codeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )

                is MdBlock.Bullet -> Row {
                    Text(
                        text = block.marker,
                        style = baseStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(18.dp),
                    )
                    Text(text = inline(block.text, primary, codeBg), style = baseStyle)
                }

                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(primary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = inline(block.text, primary, codeBg),
                        style = baseStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = block.text,
                        style = baseStyle.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        ),
                    )
                }

                MdBlock.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, primary, codeBg),
                    style = baseStyle,
                )
            }
        }
    }
}

/** 列表里的摘要：去掉标记符号，只留文字。 */
fun markdownPlain(text: String, maxLines: Int): String =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            when {
                line.startsWith("#") -> line.trimStart('#').trim()
                line.startsWith(">") -> line.trimStart('>').trim()
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ->
                    line.substring(2).trim()

                else -> line
            }
        }
        .map { it.replace("**", "").replace("`", "").replace("~~", "") }
        .take(maxLines)
        .joinToString("\n")

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Rule : MdBlock
}

private fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val codeBuffer = StringBuilder()
    var inCode = false

    source.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCode) {
                blocks.add(MdBlock.Code(codeBuffer.toString().trimEnd()))
                codeBuffer.clear()
                inCode = false
            } else {
                inCode = true
            }
            return@forEach
        }
        if (inCode) {
            codeBuffer.appendLine(line)
            return@forEach
        }

        when {
            trimmed.isEmpty() -> Unit

            trimmed.all { it == '-' || it == '*' || it == '_' } && trimmed.length >= 3 ->
                blocks.add(MdBlock.Rule)

            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                blocks.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
            }

            trimmed.startsWith("> ") || trimmed == ">" ->
                blocks.add(MdBlock.Quote(trimmed.removePrefix(">").trim()))

            trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") ->
                blocks.add(MdBlock.Bullet("•", trimmed.substring(6)))

            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
                blocks.add(MdBlock.Bullet("•", trimmed.substring(2)))

            NUMBERED.matches(trimmed) ->
                blocks.add(MdBlock.Bullet(trimmed.takeWhile { it.isDigit() } + ".", trimmed.dropWhile { it.isDigit() || it == '.' }.trim()))

            else -> blocks.add(MdBlock.Paragraph(trimmed))
        }
    }

    if (inCode && codeBuffer.isNotEmpty()) blocks.add(MdBlock.Code(codeBuffer.toString().trimEnd()))
    if (blocks.isEmpty() && source.isNotBlank()) blocks.add(MdBlock.Paragraph(source.trim()))
    return blocks
}

private val NUMBERED = Regex("""^\d+\.\s+.*""")

/** 行内样式解析：粗体、斜体、行内代码、链接。 */
private fun inline(text: String, primary: Color, codeBg: Color): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", index + 2)
                    if (end > index + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                    } else {
                        append("**")
                        index += 2
                    }
                }

                text.startsWith("~~", index) -> {
                    val end = text.indexOf("~~", index + 2)
                    if (end > index + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                    } else {
                        append("~~")
                        index += 2
                    }
                }

                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                fontSize = 13.sp,
                            ),
                        ) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    } else {
                        append('`')
                        index++
                    }
                }

                text.startsWith("[", index) -> {
                    val labelEnd = text.indexOf("](", index)
                    val urlEnd = if (labelEnd > 0) text.indexOf(')', labelEnd + 2) else -1
                    if (labelEnd > 0 && urlEnd > labelEnd) {
                        val label = text.substring(index + 1, labelEnd)
                        val url = text.substring(labelEnd + 2, urlEnd)
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = primary,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            append(label)
                        }
                        index = urlEnd + 1
                    } else {
                        append('[')
                        index++
                    }
                }

                text[index] == '*' && index + 1 < text.length && text[index + 1] != ' ' -> {
                    val end = text.indexOf('*', index + 1)
                    if (end > index + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    } else {
                        append('*')
                        index++
                    }
                }

                else -> {
                    append(text[index])
                    index++
                }
            }
        }
    }
