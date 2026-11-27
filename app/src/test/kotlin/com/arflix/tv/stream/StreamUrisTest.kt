package com.arflix.tv.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamUrisTest {
    @Test
    fun extractsTheMovieIdFromAStreamUri() {
        assertThat(StreamUris.idOf("adikstream://demon_slayer_infinity_castle_2025"))
            .isEqualTo("demon_slayer_infinity_castle_2025")
    }

    @Test
    fun fileAndHttpUrisAreNotStreams() {
        assertThat(StreamUris.idOf("file:///storage/8764-1816/Megaflix/a.mkv")).isNull()
        assertThat(StreamUris.idOf("http://127.0.0.1:5000/abc/movie")).isNull()
        assertThat(StreamUris.idOf("adikstream://")).isNull()
    }
}
