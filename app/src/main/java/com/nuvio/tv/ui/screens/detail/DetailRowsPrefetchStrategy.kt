package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A prefetch that gets cancelled never reports back. */
private const val WARM_UP_TIMEOUT_MS = 2_000L

/** The default only prefetches mid-scroll; [warmUp] builds rows at rest so the first jump doesn't. */
@OptIn(ExperimentalFoundationApi::class)
internal class DetailRowsPrefetchStrategy : LazyListPrefetchStrategy {

    private val scrollStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = 2)

    // LazyListState keeps one scope for its lifetime, so this stays valid after the callback.
    private var listScope: LazyListPrefetchScope? = null
    private var warmedUp = false

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        listScope = this
        scrollStrategy.forwardScroll(this, delta, layoutInfo)
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        listScope = this
        scrollStrategy.forwardVisibleItemsUpdated(this, layoutInfo)
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        scrollStrategy.forwardNestedPrefetch(this, firstVisibleItemIndex)
    }

    /** Builds the next [count] items once; call while nothing animates. */
    suspend fun warmUp(layoutInfo: LazyListLayoutInfo, count: Int) {
        val scope = listScope ?: return
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
        if (warmedUp) return
        warmedUp = true
        val first = lastVisible.index + 1
        val end = minOf(first + count, layoutInfo.totalItemsCount)
        if (first >= end) return
        var pending = end - first
        val built = CompletableDeferred<Unit>()
        for (index in first until end) {
            scope.schedulePrefetch(index) {
                pending -= 1
                if (pending == 0) built.complete(Unit)
            }
        }
        withTimeoutOrNull(WARM_UP_TIMEOUT_MS) { built.await() }
    }
}

// Top-level so the default strategy is the only receiver these calls can dispatch to.
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListPrefetchStrategy.forwardScroll(
    scope: LazyListPrefetchScope,
    delta: Float,
    layoutInfo: LazyListLayoutInfo,
) = scope.onScroll(delta, layoutInfo)

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListPrefetchStrategy.forwardVisibleItemsUpdated(
    scope: LazyListPrefetchScope,
    layoutInfo: LazyListLayoutInfo,
) = scope.onVisibleItemsUpdated(layoutInfo)

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListPrefetchStrategy.forwardNestedPrefetch(
    scope: NestedPrefetchScope,
    firstVisibleItemIndex: Int,
) = scope.onNestedPrefetch(firstVisibleItemIndex)
