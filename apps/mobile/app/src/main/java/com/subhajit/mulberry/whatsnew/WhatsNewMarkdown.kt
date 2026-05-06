package com.subhajit.mulberry.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.parser.Parser

@Composable
fun WhatsNewMarkdown(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val parser = remember { Parser.builder().build() }
    val document = remember(markdown) { parser.parse(markdown) as Document }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        renderBlockChildren(document)
    }
}

@Composable
private fun renderBlockChildren(node: Node) {
    var child = node.firstChild
    while (child != null) {
        renderBlock(child)
        child = child.next
    }
}

@Composable
private fun renderBlock(node: Node) {
    when (node) {
        is Heading -> {
            val isDark = isSystemInDarkTheme()
            val headlineColor = if (isDark) MaterialTheme.colorScheme.onSurface else Color.Black
            val text = inlineToAnnotatedString(node)
            val style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = text, style = style, color = headlineColor)
        }

        is Paragraph -> {
            val isDark = isSystemInDarkTheme()
            val bodyColor = if (isDark) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            } else {
                Color.Black.copy(alpha = 0.6f)
            }
            val text = inlineToAnnotatedString(node)
            MarkdownText(
                text = text,
                textColor = bodyColor
            )
        }

        is BulletList -> {
            val isDark = isSystemInDarkTheme()
            val bulletColor = if (isDark) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            } else {
                Color.Black.copy(alpha = 0.6f)
            }
            var item = node.firstChild
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                while (item != null) {
                    if (item is ListItem) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = bulletColor
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            val itemText = inlineToAnnotatedString(item)
                            MarkdownText(
                                text = itemText,
                                textColor = bulletColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item = item.next
                }
            }
        }

        is BlockQuote -> {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                renderBlockChildren(node)
            }
        }

        else -> {
            renderBlockChildren(node)
        }
    }
}

@Composable
private fun MarkdownText(
    text: AnnotatedString,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    ClickableText(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        ),
        onClick = { offset ->
            val annotations = text.getStringAnnotations(tag = "URL", start = offset, end = offset)
            val url = annotations.firstOrNull()?.item ?: return@ClickableText
            runCatching { uriHandler.openUri(url) }
        }
    )
}

@Composable
private fun inlineToAnnotatedString(node: Node): AnnotatedString {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    return remember(node, linkStyle) {
        buildAnnotatedString { appendInlineChildren(node, linkStyle) }
    }
}

private fun AnnotatedString.Builder.appendInlineChildren(parent: Node, linkStyle: SpanStyle) {
    var child = parent.firstChild
    while (child != null) {
        appendInline(child, linkStyle)
        child = child.next
    }
}

private fun AnnotatedString.Builder.appendInline(node: Node, linkStyle: SpanStyle) {
    when (node) {
        is MdText -> append(node.literal)
        is SoftLineBreak -> append(" ")
        is HardLineBreak -> append("\n")
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlineChildren(node, linkStyle) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInlineChildren(node, linkStyle) }
        is Code -> append(node.literal)
        is Link -> {
            val start = length
            withStyle(linkStyle) { appendInlineChildren(node, linkStyle) }
            val end = length
            addStringAnnotation(tag = "URL", annotation = node.destination, start = start, end = end)
        }
        else -> appendInlineChildren(node, linkStyle)
    }
}
