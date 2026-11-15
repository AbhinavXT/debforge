package com.abhinavxt.debforge.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.abhinavxt.debforge.domain.ReleaseNameParser

/** What a release filename is shown as: a title line and a quality-tags line. */
data class ReleaseLabels(val title: String, val tags: String)

/** Parsed title ("Sintel (2010)") and tags ("2160p · BluRay · HDR"); falls back to the filename. */
fun releaseLabels(filename: String): ReleaseLabels {
    val info = ReleaseNameParser.parse(filename)
    return ReleaseLabels(title = info.displayTitle.ifBlank { filename }, tags = info.qualityLabel)
}

/**
 * Splits a filename into a head and a tail that always holds the extension
 * (plus up to [tailChars] characters before it), for a middle ellipsis.
 */
fun splitForMiddleEllipsis(filename: String, tailChars: Int = 6): Pair<String, String> {
    val dot = filename.lastIndexOf('.')
    val extStart = if (dot > 0 && filename.length - dot <= 6) dot else filename.length
    val tailStart = (extStart - tailChars).coerceAtLeast(0)
    return filename.substring(0, tailStart) to filename.substring(tailStart)
}

/** Single-line filename; the head is ellipsized so the end and extension stay visible. */
@Composable
fun MiddleEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
    /** True where a parsed title already names the file: TalkBack then skips the raw filename. */
    hideFromAccessibility: Boolean = false
) {
    val (head, tail) = splitForMiddleEllipsis(text)
    val semanticsModifier = if (hideFromAccessibility) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.clearAndSetSemantics { contentDescription = text }
    }
    Row(modifier.then(semanticsModifier)) {
        if (head.isNotEmpty()) {
            Text(head, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Text(tail, style = style, color = color, maxLines = 1, softWrap = false)
    }
}
