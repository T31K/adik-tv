package com.arflix.tv.data.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class MdbListApiModelTest {
    @Test
    fun `ratings tolerate numeric and textual values`() {
        val info = Gson().fromJson(
            """{
                "ratings": [
                    {"source":"tomatoes","value":"Fresh","score":87},
                    {"source":"letterboxd","value":"4.2","score":"84"}
                ]
            }""".trimIndent(),
            MdbMediaInfo::class.java
        )

        assertEquals("Fresh", info.ratings?.get(0)?.value?.asString)
        assertEquals(87.0, info.ratings?.get(0)?.score?.asDouble ?: 0.0, 0.0)
        assertEquals(4.2, info.ratings?.get(1)?.value?.asDouble ?: 0.0, 0.0)
    }
}
