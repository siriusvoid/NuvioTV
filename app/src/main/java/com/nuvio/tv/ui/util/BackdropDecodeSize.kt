package com.nuvio.tv.ui.util

/**
 * Backdrops are served at w1280, so requesting a full-screen decode makes ImageDecoder
 * resample on the CPU for pixels the source never had. Home and the details page ask for
 * this same size so the second screen reuses the first one's bitmap instead of decoding
 * the same artwork again under a different cache key.
 */
const val BACKDROP_DECODE_WIDTH_PX = 1280
const val BACKDROP_DECODE_HEIGHT_PX = 720

fun backdropDecodeWidth(screenWidthPx: Int): Int =
    screenWidthPx.coerceAtMost(BACKDROP_DECODE_WIDTH_PX).coerceAtLeast(1)

fun backdropDecodeHeight(screenHeightPx: Int): Int =
    screenHeightPx.coerceAtMost(BACKDROP_DECODE_HEIGHT_PX).coerceAtLeast(1)
