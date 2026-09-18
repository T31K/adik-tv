package com.arflix.tv.data.repository

import java.net.URLEncoder

internal fun buildCatalogRequestUrls(
    baseUrl: String,
    catalogType: String,
    catalogId: String,
    skip: Int,
    queryBase: String?
): List<String> {
    val type = URLEncoder.encode(catalogType, "UTF-8")
    val id = URLEncoder.encode(catalogId, "UTF-8")
    val base = "$baseUrl/catalog/$type/$id"
    val query = queryBase?.takeIf { it.isNotBlank() }
    val configSuffix = query?.let { "?$it" }.orEmpty()
    if (skip <= 0) return listOf("$base.json$configSuffix")
    val skipQuery = listOfNotNull(query, "skip=$skip").joinToString("&")
    // Standard add-ons read skip from the path. Query-first can silently repeat page one.
    return listOf("$base/skip=$skip.json$configSuffix", "$base.json?$skipQuery")
}
