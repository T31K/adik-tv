package com.arflix.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.R
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision

private val BannerShape = RoundedCornerShape(24.dp)
private val CardBorder = Color(0xFF2B2B2B)
private val ImdbYellow = Color(0xFFF5C518)
private val BottomScrim = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color.Transparent,
        0.25f to Color.Transparent,
        0.48f to Color.Black.copy(alpha = 0.40f),
        0.66f to Color.Black.copy(alpha = 0.80f),
        0.82f to Color.Black.copy(alpha = 0.94f),
        1.00f to Color(0xCC000000)
    )
)

/**
 * Netflix-style immersive mobile hero banner.
 *
 * Displays a full-bleed poster image with a layered dark scrim, a large title,
 * genre tags, and a metadata row showing the release year and IMDb rating.
 * No action buttons.
 *
 * @param imageUrl  URL of the backdrop / poster image.
 * @param title     Primary title shown as the visual anchor.
 * @param genres    List of genre strings displayed as a bullet-separated row.
 * @param year      Release year string (e.g. "2024"). Pass empty to hide.
 * @param rating    IMDb rating string (e.g. "8.7"). Pass empty to hide.
 * @param modifier  Modifier applied to the card's outermost Box.
 */
@Composable
fun MobileHeroBanner(
    imageUrl: String,
    title: String,
    genres: List<String>,
    year: String = "",
    rating: String = "",
    logoUrl: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .shadow(elevation = 8.dp, shape = BannerShape, clip = false)
            .clip(BannerShape)
            .background(Color(0xFF141419))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .border(width = 1.dp, color = CardBorder, shape = BannerShape)
    ) {
        // ── Layer 1: Full-bleed background image ────────────────────────────
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .precision(Precision.INEXACT)
                    .allowHardware(true)
                    .crossfade(300)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Layer 2: Bottom scrim spanning the lower 65% of the card ────────
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.BottomCenter)
                .background(BottomScrim)
        )

        // ── Layer 3: Content overlay — pinned to bottom-center ───────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Element A — Logo image when available, otherwise large title text
            if (logoUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logoUrl)
                        .precision(Precision.INEXACT)
                        .allowHardware(true)
                        .crossfade(300)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth()
                )
            } else {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 42.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Element B — Genre tags with bullet separators
            BannerGenres(genres = genres)

            // Element C — Year and IMDb rating
            BannerMeta(year = year, rating = rating)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private sub-composables
// ─────────────────────────────────────────────────────────────────────────────

/** Year + IMDb rating row, matching the hero carousel metadata style. */
@Composable
private fun BannerMeta(year: String, rating: String) {
    val hasYear = year.isNotEmpty()
    val hasRating = rating.isNotEmpty()
    if (!hasYear && !hasRating) return

    val context = LocalContext.current
    val imdbSvgRequest = remember(context) {
        ImageRequest.Builder(context)
            .data(R.raw.logo_imdb_rectangle)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowRgb565(false)
            .build()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hasYear) {
            Text(
                text = year,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        if (hasRating) {
            AsyncImage(
                model = imdbSvgRequest,
                contentDescription = "IMDb",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(28.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Text(
                text = rating,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Bullet-separated genre string rendered in muted gray. */
@Composable
private fun BannerGenres(genres: List<String>) {
    if (genres.isEmpty()) return
    Text(
        text = genres.joinToString("  •  "),
        color = Color.LightGray,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        lineHeight = 17.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
private fun MobileHeroBannerSeriesPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        MobileHeroBanner(
            imageUrl = "",
            title = "Stranger Things",
            genres = listOf("Slick", "Psychological", "Thriller"),
            year = "2022",
            rating = "8.7"
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
private fun MobileHeroBannerFilmPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        MobileHeroBanner(
            imageUrl = "",
            title = "Oppenheimer",
            genres = listOf("History", "Drama", "Biography"),
            year = "2023",
            rating = "8.3"
        )
    }
}
