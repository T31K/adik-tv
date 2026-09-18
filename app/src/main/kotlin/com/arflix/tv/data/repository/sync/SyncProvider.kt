package com.arflix.tv.data.repository.sync

/**
 * Legacy preferred remote for a profile. Credentials may coexist; read and
 * write routing is controlled separately by [SyncProviderStore].
 */
enum class SyncProvider {
    NONE,
    TRAKT,
    MDBLIST,
    SIMKL;

    companion object {
        fun fromStorage(value: String?): SyncProvider = when (value?.lowercase()) {
            "trakt" -> TRAKT
            "mdblist" -> MDBLIST
            "simkl" -> SIMKL
            else -> NONE
        }
    }

    fun toStorage(): String = name.lowercase()
}
