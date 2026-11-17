package com.arflix.tv.data.model

import java.io.Serializable

/**
 * A home row from the feed. The id selects a library filter (see MediaRepository.loadLocalCatalog).
 *
 * Saved per-profile arrangements written by older builds carry many more fields
 * (sourceType, collection*, addon*, pack*); Gson ignores the unknown ones, and only
 * [id] and [title] are ever read back from saved rows.
 */
data class CatalogConfig(
    val id: String,
    val title: String
) : Serializable
