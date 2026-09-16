package com.nuvio.tv.ui.util

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** A plurals resource formatted with its own count. */
@Composable
internal fun quantityStringResource(@PluralsRes id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)
