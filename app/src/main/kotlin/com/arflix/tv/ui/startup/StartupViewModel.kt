package com.arflix.tv.ui.startup

import androidx.lifecycle.ViewModel
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Splash-screen state. The app always opens on profile selection first, so home data is
 * loaded by HomeViewModel after a profile is chosen; nothing is preloaded here.
 */
data class StartupState(
    val isLoading: Boolean = true,
    val isReady: Boolean = false,
    val loadingProgress: Float = 0f,
    val loadingMessage: String = "Starting...",
    val categories: List<Category> = emptyList(),
    val heroItem: MediaItem? = null,
    val heroLogoUrl: String? = null,
    val logoCache: Map<String, String> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class StartupViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(
        StartupState(
            isLoading = false,
            isReady = true,
            loadingProgress = 1f,
            loadingMessage = "Ready!"
        )
    )
    val state: StateFlow<StartupState> = _state.asStateFlow()
}
