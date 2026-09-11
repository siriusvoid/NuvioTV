package com.nuvio.tv.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.LocalContentColor

/** Text without TV Material's offscreen layer; only where the container doesn't scale on focus. */
@Composable
fun LayerFreeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = resolvedColor, textAlign = textAlign ?: TextAlign.Unspecified)),
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
    )
}
