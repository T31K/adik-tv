package com.arflix.tv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.arflix.tv.R
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.skin.ArvioFocusableSurface
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.skin.rememberArvioCardShape
import com.arflix.tv.util.LocalDeviceType
import com.arflix.tv.util.Constants
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex

/**
 * Media card component for rows/grids.
 * Arctic Fuse 2 style:
 * - Large landscape cards with solid pink/magenta focus border
 * - Transform-based focus (graphicsLayer) via `ArvioFocusableSurface`
 * - No layout size changes on focus (no width/height scaling)
 * - Uses `ArvioSkin` for consistent styling
 */

private val missingArtworkBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF1F2333),
        Color(0xFF0D0D14)
    )
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaCard(
    item: MediaItem,
    width: Dp = 280.dp,  // Arctic Fuse 2: larger cards by default
    isLandscape: Boolean = true,
    logoImageUrl: String? = null,
    // Optional focus-state image that swaps in when the card is focused.
    focusImageUrl: String? = null,
    enableFocusedImageSwap: Boolean = true,
    animateFocus: Boolean = true,
    showLogoImage: Boolean = true,
    raiseOnFocus: Boolean = true,
    showProgress: Boolean = false,
    showTitle: Boolean = true,
    showSubtitle: Boolean = true,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    isFocusedOverride: Boolean = false,
    focusedScale: Float = 1.045f,
    enableSystemFocus: Boolean = true,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // If this is a placeholder card, show skeleton only
    if (item.isPlaceholder) {
        PlaceholderCard(
            width = width,
            isLandscape = isLandscape,
            showTitle = showTitle,
            showSubtitle = showSubtitle,
            modifier = modifier
        )
        return
    }

    var isFocused by remember { mutableStateOf(false) }
    val visualFocused = isFocusedOverride || isFocused
    val isMobile = LocalDeviceType.current.isTouchDevice()

    val aspectRatio = if (isLandscape) 16f / 9f else 2f / 3f
    // Landscape cards should prefer wide artwork/backdrops.
    // Poster art is portrait and looks cropped in 16:9 cards, so only use it as fallback.
    // Guard empty/blank URLs — TMDB items with no posterPath AND no backdropPath
    // produce image="" in toMediaItem(). Passing "" to Coil throws
    // "Unable to create a fetcher that supports: " and the card stays dark forever.
    // With this guard, cards with no image simply show the surface background color.
    val continueWatchingArtwork = item.episodeStill
        ?.takeIf { showProgress && isLandscape && it.isNotBlank() }
    val baseImageUrl = if (isLandscape) {
        (continueWatchingArtwork ?: item.backdrop ?: item.image).takeIf { it.isNotBlank() }
    } else {
        item.image.takeIf { it.isNotBlank() }
    }
    val explicitFocusUrl = focusImageUrl?.takeIf { it.isNotBlank() }
    val rawImageUrl = if (visualFocused && enableFocusedImageSwap) {
        explicitFocusUrl ?: baseImageUrl
    } else {
        baseImageUrl
    }
    val shape = rememberArvioCardShape(ArvioSkin.radius.md)

    val jumpBorderWidth = 2.5.dp

    val context = LocalContext.current
    val density = LocalDensity.current
    val overlayBrush: Brush? = null  // Gradient removed per user feedback
    val imageRequest = remember(rawImageUrl, width, aspectRatio, isMobile) {
        if (rawImageUrl == null) return@remember null
        val widthPx = with(density) { width.roundToPx() }
        val heightPx = (widthPx / aspectRatio).toInt().coerceAtLeast(1)
        val cacheKey = "$rawImageUrl|${widthPx}x$heightPx"
        ImageRequest.Builder(context)
            .data(rawImageUrl)
            .size(widthPx, heightPx)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .memoryCacheKey(cacheKey)
            .placeholderMemoryCacheKey(cacheKey)
            .crossfade(if (isMobile) 250 else 0)
            .build()
    }
    var artworkIsOpaque by remember(imageRequest) { mutableStateOf(false) }
    // Performance: Removed context/density from keys
    val effectiveLogoImageUrl = logoImageUrl.takeIf { showLogoImage }
    val logoRequest = remember(effectiveLogoImageUrl, isMobile) {
        val logoWidthPx = with(density) { 220.dp.roundToPx() }.coerceAtLeast(1)
        val logoHeightPx = with(density) { 64.dp.roundToPx() }.coerceAtLeast(1)
        if (effectiveLogoImageUrl.isNullOrBlank()) {
            null
        } else {
            val cacheKey = "$effectiveLogoImageUrl|${logoWidthPx}x$logoHeightPx"
            ImageRequest.Builder(context)
                .data(effectiveLogoImageUrl)
                .size(logoWidthPx, logoHeightPx)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .memoryCacheKey(cacheKey)
                .placeholderMemoryCacheKey(cacheKey)
                .crossfade(if (isMobile) 200 else 0)
                .build()
        }
    }

    Column(
        modifier = modifier
            .width(width)
            .zIndex(if (visualFocused && raiseOnFocus) 1f else 0f)
    ) {
        ArvioFocusableSurface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            shape = shape,
            backgroundColor = ArvioSkin.colors.surface,
            outlineColor = ArvioSkin.colors.focusOutline,
            outlineWidth = jumpBorderWidth,
            showRestBorder = true,
            focusedScale = focusedScale,
            pressedScale = 0.97f,
            focusedTransformOriginX = 0.5f,
            animateFocus = animateFocus,
            enableSystemFocus = enableSystemFocus,
            isFocusedOverride = isFocusedOverride,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChanged = {
                isFocused = it
                if (it) onFocused()
            },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (!artworkIsOpaque) Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(missingArtworkBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.title,
                        style = ArvioSkin.typography.cardTitle,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
                if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        onError = { artworkIsOpaque = false },
                        onSuccess = {
                            // Keep the fallback behind transparent artwork and crossfades.
                            artworkIsOpaque = !isMobile &&
                                it.result.drawable.opacity == android.graphics.PixelFormat.OPAQUE
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (overlayBrush != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(overlayBrush)
                    )
                }

                // Megaflix: download-state badge (top-start of the artwork).
                // NB: use == comparisons, not `when` — MediaItems restored from the Gson
                // disk cache can have a null downloadStatus (Gson bypasses Kotlin defaults),
                // and `when(enum)` compiles to .ordinal() which NPEs on null.
                val dlStatus = item.downloadStatus
                if (dlStatus == com.arflix.tv.data.model.DownloadStatus.COMING_SOON) {
                    MegaflixStatusBadge("COMING SOON", Modifier.align(Alignment.TopStart))
                } else if (dlStatus == com.arflix.tv.data.model.DownloadStatus.DOWNLOADING) {
                    MegaflixStatusBadge("↓ ${(item.downloadProgress * 100).toInt()}%", Modifier.align(Alignment.TopStart))
                } else if (dlStatus == com.arflix.tv.data.model.DownloadStatus.FAILED) {
                    MegaflixStatusBadge("RETRY", Modifier.align(Alignment.TopStart))
                }

                // Official logo/art overlay is only used on landscape cards.
                // Poster layout should stay image-first on TV, otherwise the
                // clearlogo crowds the poster art and looks double-stamped.
                if (logoRequest != null && isLandscape) {
                    AsyncImage(
                        model = logoRequest,
                        contentDescription = stringResource(R.string.component_media_logo, item.title),
                        contentScale = ContentScale.Fit,
                        alignment = if (isLandscape) Alignment.BottomStart else Alignment.BottomCenter,
                        modifier = Modifier
                            .align(if (isLandscape) Alignment.BottomStart else Alignment.BottomCenter)
                            .fillMaxWidth(if (isLandscape) 0.52f else 0.74f)
                            .height(if (isLandscape) 48.dp else 42.dp)
                            .padding(
                                start = if (isLandscape) 10.dp else 0.dp,
                                end = if (isLandscape) 0.dp else 8.dp,
                                bottom = if (isLandscape) 18.dp else 12.dp
                            )
                    )
                }

                // Subtle green watched badge
                if (item.isWatched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                        .padding(bottom = 6.dp, end = 6.dp)
                        .size(14.dp)
                        .background(
                            color = ArvioSkin.colors.watchedGreen.copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                        .border(
                            width = 1.dp,
                            color = ArvioSkin.colors.watchedGreen,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = ArvioSkin.colors.watchedGreen,
                        modifier = Modifier.size(8.dp)
                    )
                }
                }

                // Subtle playback progress bar for Continue Watching.
                if (
                    showProgress && item.showPlaybackProgress && !item.isWatched &&
                    item.progress in 1 until Constants.WATCHED_THRESHOLD
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.Black.copy(alpha = 0.48f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.progress / 100f)
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.92f))
                        )
                    }
                }

                // ── Continue Watching badges ──
                if (showProgress) {
                    // Top-right: time remaining or "New Episode" badge
                    val topRightLabel = item.timeRemainingLabel
                        ?: if (item.mediaType == MediaType.TV && item.progress == 0 && !item.isWatched) stringResource(R.string.component_badge_new_episode) else null
                    if (topRightLabel != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(ArvioSkin.spacing.x2)
                                .background(
                                    color = ArvioSkin.colors.surfaceRaised.copy(alpha = 0.85f),
                                    shape = rememberArvioCardShape(ArvioSkin.radius.sm),
                                )
                                .padding(horizontal = ArvioSkin.spacing.x2, vertical = ArvioSkin.spacing.x1),
                        ) {
                            Text(
                                text = topRightLabel,
                                style = ArvioSkin.typography.badge,
                                color = ArvioSkin.colors.textPrimary,
                            )
                        }
                    }

                    // Top-left: episodes remaining for TV shows
                    if (item.mediaType == MediaType.TV && item.totalEpisodes != null && item.totalEpisodes > 0) {
                        val epsRemaining = item.totalEpisodes - (item.watchedEpisodes ?: 0)
                        if (epsRemaining > 0) {
                            val epsLabel = if (isLandscape) {
                                if (epsRemaining == 1) stringResource(R.string.component_ep_left_one)
                                else stringResource(R.string.component_eps_left, epsRemaining)
                            } else {
                                epsRemaining.toString()
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(ArvioSkin.spacing.x2)
                                    .background(
                                        color = ArvioSkin.colors.surfaceRaised.copy(alpha = 0.62f),
                                        shape = rememberArvioCardShape(ArvioSkin.radius.sm),
                                    )
                                    .padding(horizontal = ArvioSkin.spacing.x2, vertical = ArvioSkin.spacing.x1),
                            ) {
                                Text(
                                    text = epsLabel,
                                    style = ArvioSkin.typography.badge,
                                    color = ArvioSkin.colors.textPrimary,
                                )
                            }
                        }
                    }

                    // Season/episode marker, right-aligned with top-right badge.
                    val nextEpisode = item.nextEpisode
                    if (!item.isWatched && item.mediaType == MediaType.TV && nextEpisode != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(
                                    end = ArvioSkin.spacing.x2,
                                    bottom = 14.dp
                                )
                                .background(
                                    color = ArvioSkin.colors.surfaceRaised.copy(alpha = 0.70f),
                                    shape = rememberArvioCardShape(ArvioSkin.radius.sm),
                                )
                                .padding(horizontal = ArvioSkin.spacing.x2, vertical = ArvioSkin.spacing.x1),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.component_season_episode_marker,
                                    nextEpisode.seasonNumber,
                                    nextEpisode.episodeNumber
                                ),
                                style = ArvioSkin.typography.badge,
                                color = ArvioSkin.colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }

        if (showTitle) {
            Spacer(modifier = Modifier.height(ArvioSkin.spacing.x2))

            Text(
                text = item.title,
                style = if (isMobile) ArvioSkin.typography.cardTitle.copy(
                    fontSize = 14.sp,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = androidx.compose.ui.graphics.Color.Black,
                        offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                        blurRadius = 6f
                    )
                ) else ArvioSkin.typography.cardTitle.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = androidx.compose.ui.graphics.Color.Black,
                        offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                        blurRadius = 6f
                    )
                ),
                color = if (visualFocused) {
                    ArvioSkin.colors.textPrimary
                } else {
                    ArvioSkin.colors.textPrimary.copy(alpha = 0.85f)
                },
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )

            if (showSubtitle) {
                // Prefer release date (or year) under the title. Fall back to the
                // explicit subtitle or media-type label only when neither is set.
                val tvSeriesLabel = stringResource(R.string.component_label_tv_series)
                val movieLabel = stringResource(R.string.movie)
                val mediaLabel = stringResource(R.string.component_label_media)
                val subtitle = remember(item.subtitle, item.releaseDate, item.year, item.mediaType, tvSeriesLabel, movieLabel, mediaLabel) {
                    val release = item.releaseDate?.takeIf { it.isNotBlank() }
                        ?: item.year.takeIf { it.isNotBlank() }
                    release
                        ?: item.subtitle.ifBlank {
                            when (item.mediaType) {
                                MediaType.TV -> tvSeriesLabel
                                MediaType.MOVIE -> movieLabel
                                else -> mediaLabel
                            }
                        }
                }
                Text(
                    text = subtitle,
                    style = ArvioSkin.typography.caption.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = androidx.compose.ui.graphics.Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    ),
                    color = ArvioSkin.colors.textMuted.copy(alpha = 0.85f),
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Placeholder card shown while Continue Watching data loads.
 * Displays a skeleton animation to indicate loading state.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaceholderCard(
    width: Dp,
    isLandscape: Boolean,
    showTitle: Boolean = true,
    showSubtitle: Boolean = true,
    modifier: Modifier = Modifier
) {
    val aspectRatio = if (isLandscape) 16f / 9f else 2f / 3f
    val shape = rememberArvioCardShape(ArvioSkin.radius.md)

    Column(
        modifier = modifier.width(width)
    ) {
        // Card skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(shape)
        ) {
            SkeletonBox(
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showTitle) {
            Spacer(modifier = Modifier.height(ArvioSkin.spacing.x2))

            // Title skeleton
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(rememberArvioCardShape(ArvioSkin.radius.sm))
            )

            if (showSubtitle) {
                Spacer(modifier = Modifier.height(4.dp))

                // Subtitle skeleton
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .clip(rememberArvioCardShape(ArvioSkin.radius.sm))
                )
            }
        }
    }
}

/**
 * Poster-style media card (portrait orientation).
 * Phase 5: Added proper image sizing and shimmer placeholder.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    item: MediaItem,
    width: Dp = 140.dp,
    isFocusedOverride: Boolean = false,
    enableSystemFocus: Boolean = true,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    useWhiteBorder: Boolean = true,
) {
    if (item.isPlaceholder) {
        PlaceholderCard(
            width = width,
            isLandscape = false,
            showTitle = false,
            modifier = modifier
        )
        return
    }

    var isFocused by remember { mutableStateOf(false) }
    val visualFocused = isFocusedOverride || isFocused

    val shape = rememberArvioCardShape(ArvioSkin.radius.md)
    val outlineColor = if (useWhiteBorder) ArvioSkin.colors.focusOutline else ArvioSkin.colors.accent

    val context = LocalContext.current
    val density = LocalDensity.current
    val aspectRatio = 2f / 3f
    val isMobile = LocalDeviceType.current.isTouchDevice()
    val posterUrl = item.image.takeIf { it.isNotBlank() }
    val imageRequest = remember(posterUrl, width, isMobile) {
        if (posterUrl == null) return@remember null
        val widthPx = with(density) { width.roundToPx() }
        val heightPx = (widthPx / aspectRatio).toInt().coerceAtLeast(1)
        val cacheKey = "$posterUrl|${widthPx}x$heightPx"
        ImageRequest.Builder(context)
            .data(posterUrl)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .memoryCacheKey(cacheKey)
            .placeholderMemoryCacheKey(cacheKey)
            .crossfade(if (isMobile) 250 else 0)
            .build()
    }

    Column(modifier = modifier.width(width)) {
        ArvioFocusableSurface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            shape = shape,
            backgroundColor = ArvioSkin.colors.surface,
            outlineColor = outlineColor,
            enableSystemFocus = enableSystemFocus,
            isFocusedOverride = isFocusedOverride,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChanged = {
                isFocused = it
                if (it) onFocused()
            },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(missingArtworkBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.title,
                        style = ArvioSkin.typography.cardTitle,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Only show title and year when focused
        if (visualFocused) {
            Spacer(modifier = Modifier.height(ArvioSkin.spacing.x1))

            Text(
                text = item.title,
                style = ArvioSkin.typography.caption.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = androidx.compose.ui.graphics.Color.Black,
                        offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                        blurRadius = 4f
                    )
                ),
                color = ArvioSkin.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.year.isNotBlank()) {
                Text(
                    text = item.year,
                    style = ArvioSkin.typography.caption.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = androidx.compose.ui.graphics.Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    ),
                    color = ArvioSkin.colors.textMuted.copy(alpha = 0.65f),
                )
            }
        }
    }
}

/** Megaflix: small pill badge for a card's download state (Coming Soon / progress / retry). */
@androidx.compose.runtime.Composable
private fun MegaflixStatusBadge(text: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}
