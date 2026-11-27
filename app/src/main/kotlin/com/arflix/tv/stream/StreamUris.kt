package com.arflix.tv.stream

import com.arflix.tv.megaflix.MegaflixLibraryBuilder

/** `adikstream://<movie id>` is how a library entry says "stream me from R2". */
object StreamUris {
    fun idOf(uri: String): String? =
        uri.takeIf { it.startsWith(MegaflixLibraryBuilder.STREAM_SCHEME) }
            ?.removePrefix(MegaflixLibraryBuilder.STREAM_SCHEME)
            ?.takeIf { it.isNotBlank() }
}
