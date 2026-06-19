package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaCastMember

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun CastSection(
    cast: List<MetaCastMember>,
    modifier: Modifier = Modifier,
    title: String = "Cast",
    leadingCast: List<MetaCastMember> = emptyList(),
    hideActorNames: Boolean = false,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    restorePersonId: Int? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onCastMemberFocused: (MetaCastMember) -> Unit = {},
    onCastMemberClick: (MetaCastMember) -> Unit = {}
) {
    if (cast.isEmpty() && leadingCast.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val castPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val lazyListState = rememberLazyListState(prefetchStrategy = castPrefetchStrategy)

    LaunchedEffect(cast, leadingCast) {
        val validKeys = buildSet {
            leadingCast.forEach { member ->
                add("leading:${member.tmdbId ?: member.name}:${member.character.orEmpty()}")
            }
            cast.forEach { member ->
                add("cast:${member.tmdbId ?: member.name}:${member.character.orEmpty()}")
            }
        }
        itemFocusRequesters.keys.retainAll(validKeys)
    }

    // Track whether a restore is pending so focusRestorer can use the correct fallback
    var restorePending by remember { mutableStateOf(false) }

    // Only react to restoreFocusToken changes (triggered on ON_RESUME).
    // restorePersonId/cast lists are read inside but not used as keys to avoid
    // triggering scroll at the moment of click (before navigation happens).
    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken <= 0 || restorePersonId == null) {
            restorePending = false
            return@LaunchedEffect
        }
        val leadingIndex = leadingCast.indexOfFirst { it.tmdbId == restorePersonId }
        val castIndex = cast.indexOfFirst { it.tmdbId == restorePersonId }
        if (leadingIndex < 0 && castIndex < 0) {
            restorePending = false
            return@LaunchedEffect
        }
        restorePending = true
        restoreFocusRequester.requestFocusAfterFrames()
    }

    val itemWidth = 150.dp
    val cardSize = 100.dp
    val hasTitle = title.isNotBlank()
    val currentUpFocusRequester by rememberUpdatedState(upFocusRequester)
    val currentDownFocusRequester by rememberUpdatedState(downFocusRequester)

    val itemFocusPropertiesModifier = if (currentUpFocusRequester != null || currentDownFocusRequester != null) {
        Modifier.focusProperties {
            if (currentUpFocusRequester != null) up = currentUpFocusRequester!!
            if (currentDownFocusRequester != null) down = currentDownFocusRequester!!
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (hasTitle) 20.dp else NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm)
    ) {
        if (hasTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .focusRestorer { if (restorePending) restoreFocusRequester else firstItemFocusRequester },
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            val standardGap = NuvioTheme.spacing.sm
            val deadSpace = itemWidth - cardSize

            if (leadingCast.isNotEmpty()) {
                itemsIndexed(
                    items = leadingCast,
                    key = { index, member ->
                        "leading|" + index + "|" + (member.tmdbId?.toString() ?: member.name) + "|" + (member.character ?: "") + "|" + (member.photo ?: "")
                    }
                ) { index, member ->
                    val isLastLeading = member == leadingCast.last()
                    val endPadding = if (isLastLeading && cast.isNotEmpty()) NuvioTheme.spacing.none else standardGap
                    val isRestoreTarget = member.tmdbId == restorePersonId
                    val isFirstItem = index == 0
                    val focusKey = "leading:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
                    val focusRequester = when {
                        isRestoreTarget -> restoreFocusRequester
                        isFirstItem -> firstItemFocusRequester
                        else -> remember(focusKey) { itemFocusRequesters.getOrPut(focusKey) { FocusRequester() } }
                    }

                    Box(modifier = Modifier.padding(end = endPadding)) {
                        CastMemberItem(
                            member = member,
                            hideActorName = hideActorNames,
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .then(itemFocusPropertiesModifier),
                            itemWidth = itemWidth,
                            cardSize = cardSize,
                            onFocused = {
                                onCastMemberFocused(member)
                                if (isRestoreTarget && restoreFocusToken > 0) {
                                    restorePending = false
                                    onRestoreFocusHandled()
                                }
                            },
                            onClick = { onCastMemberClick(member) }
                        )
                    }
                }
            }

            if (leadingCast.isNotEmpty() && cast.isNotEmpty()) {
                item(key = "role_divider") {
                    Box(
                        modifier = Modifier.height(cardSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(NuvioTheme.spacing.hairline)
                                .height(72.dp)
                                .offset(x = -deadSpace / 2)
                                .background(NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.9f))
                        )
                    }
                }
            }

            itemsIndexed(
                items = cast,
                key = { index, member ->
                    index.toString() + "|" + (member.tmdbId?.toString() ?: member.name) + "|" + (member.character ?: "") + "|" + (member.photo ?: "")
                }
            ) { index, member ->
                val isRestoreTarget = member.tmdbId == restorePersonId
                val isFirstCastItem = index == 0 && leadingCast.isEmpty()
                val focusKey = "cast:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
                val focusRequester = when {
                    isRestoreTarget -> restoreFocusRequester
                    isFirstCastItem -> firstItemFocusRequester
                    else -> remember(focusKey) { itemFocusRequesters.getOrPut(focusKey) { FocusRequester() } }
                }

                Box(modifier = Modifier.padding(end = standardGap)) {
                    CastMemberItem(
                        member = member,
                        hideActorName = hideActorNames,
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .then(itemFocusPropertiesModifier),
                        itemWidth = itemWidth,
                        cardSize = cardSize,
                        onFocused = {
                            onCastMemberFocused(member)
                            if (isRestoreTarget && restoreFocusToken > 0) {
                                restorePending = false
                                onRestoreFocusHandled()
                            }
                        },
                        onClick = { onCastMemberClick(member) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastMemberItem(
    member: MetaCastMember,
    hideActorName: Boolean = false,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 150.dp,
    cardSize: Dp = 100.dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardSizePx = remember(cardSize, density) {
        with(density) { cardSize.roundToPx() }
    }
    val typography = MaterialTheme.typography
    val nameStyle = remember(typography) { typography.labelMedium }
    val characterStyle = remember(typography) { typography.labelSmall }
    val initialsStyle = remember(typography) { typography.titleLarge }
    // Actor/voice-actor headshots are real-person photos: TMDB profile images
    // (image.tmdb.org) or TVDB person artwork (.../banners/person/...). Character
    // images come from elsewhere — TVDB role art (.../v4/actor/.../photo/) or anime
    // CDNs. When hiding actor names, blank only the real-person headshots.
    val isRealPersonPhoto = member.photo?.let { url ->
        url.contains("image.tmdb.org", ignoreCase = true) ||
            url.contains("/person/", ignoreCase = true)
    } == true
    val photo = if (hideActorName && isRealPersonPhoto) null else member.photo
    val photoModel = remember(context, photo, cardSizePx) {
        photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = cardSizePx, height = cardSizePx)
                .build()
        }
    }

    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            onClick = onClick,
            modifier = modifier
                .size(cardSize)
                .align(Alignment.Start)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                },
            shape = CardDefaults.shape(
                shape = CircleShape
            ),
            colors = CardDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = CircleShape
                )
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val currentBgColor = if (isFocused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.SurfaceVariant
                val bgPainter = remember(currentBgColor) { androidx.compose.ui.graphics.painter.ColorPainter(currentBgColor) }

                if (photoModel != null) {
                    AsyncImage(
                        model = photoModel,
                        contentDescription = if (hideActorName) member.character else member.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = bgPainter,
                        error = bgPainter,
                        fallback = bgPainter
                    )
                } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize().background(currentBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (if (hideActorName) member.character else member.name)?.firstOrNull()?.uppercase() ?: "?",
                            style = initialsStyle,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val character = member.character?.takeIf { it.isNotBlank() }
        val displayCharacter = character?.let {
            when {
                it.equals("Creator", ignoreCase = true) -> stringResource(R.string.cast_role_creator)
                it.equals("Director", ignoreCase = true) -> stringResource(R.string.cast_role_director)
                it.equals("Writer", ignoreCase = true) -> stringResource(R.string.cast_role_writer)
                else -> it
            }
        }

        if (!hideActorName) {
            Text(
                text = member.name,
                style = nameStyle,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (displayCharacter != null) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = displayCharacter,
                    style = characterStyle,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (displayCharacter != null) {
            // Actor name hidden — show the character in the primary slot.
            Text(
                text = displayCharacter,
                style = nameStyle,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
