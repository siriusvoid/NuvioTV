package com.nuvio.tv.ui.util

/** Backdrops are served at w1280; home and details decode at this size so they share the bitmap. */
const val BACKDROP_DECODE_WIDTH_PX = 1280
const val BACKDROP_DECODE_HEIGHT_PX = 720

fun backdropDecodeWidth(screenWidthPx: Int): Int =
    screenWidthPx.coerceAtMost(BACKDROP_DECODE_WIDTH_PX).coerceAtLeast(1)

fun backdropDecodeHeight(screenHeightPx: Int): Int =
    screenHeightPx.coerceAtMost(BACKDROP_DECODE_HEIGHT_PX).coerceAtLeast(1)
