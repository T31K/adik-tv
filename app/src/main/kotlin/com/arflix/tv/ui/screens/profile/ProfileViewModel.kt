package com.arflix.tv.ui.screens.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.R
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.model.ProfileColors
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileAvatarImageManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.ui.components.ToastType
import com.arflix.tv.util.PinUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ProfileUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfile: Profile? = null,
    val isLoading: Boolean = true,
    val isSwitchingProfile: Boolean = false,
    val isManageMode: Boolean = false,
    // Add profile dialog state
    val showAddDialog: Boolean = false,
    val newProfileName: String = "",
    val selectedColorIndex: Int = 0,
    val selectedAvatarId: Int = 0, // 0 = legacy letter, 1-24 = cartoon avatar
    val selectedAvatarImageUri: String? = null,
    val useCustomAvatarImage: Boolean = false,
    val isKidsProfile: Boolean = false,
    // Edit profile dialog state
    val editingProfile: Profile? = null,
    // Toast state
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.SUCCESS,
    val showToast: Boolean = false,
    // PIN dialog state
    val showPinDialog: Boolean = false,
    val pinDialogMode: String = "", // "verify" or "setup"
    val pendingProfileForPin: Profile? = null,
    val pinContext: String = "", // "select", "edit", or "delete"
    val pinError: String = "" // Error message for wrong PIN
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val profileManager: ProfileManager,
    private val watchlistRepository: WatchlistRepository,
    private val profileAvatarImageManager: ProfileAvatarImageManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfiles()
        observeProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val profiles = profileRepository.getProfiles()
            val activeProfile = profileRepository.getActiveProfile()
            _uiState.value = _uiState.value.copy(
                profiles = profiles,
                activeProfile = activeProfile,
                isLoading = false
            )
        }
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            profileRepository.profiles.collect { profiles ->
                _uiState.value = _uiState.value.copy(
                    profiles = profiles,
                    isLoading = _uiState.value.isLoading && profiles.isEmpty()
                )
            }
        }
        viewModelScope.launch {
            profileRepository.activeProfile.collect { profile ->
                _uiState.value = _uiState.value.copy(activeProfile = profile)
            }
        }
    }

    private fun showToast(message: String, type: ToastType = ToastType.SUCCESS) {
        _uiState.value = _uiState.value.copy(
            toastMessage = message,
            toastType = type,
            showToast = true
        )
        viewModelScope.launch {
            delay(3500)
            _uiState.value = _uiState.value.copy(showToast = false)
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(showToast = false)
    }

    fun selectProfile(profile: Profile) {
        if (_uiState.value.isSwitchingProfile) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSwitchingProfile = true)
            try {
                val previousProfileId = withContext(Dispatchers.IO) {
                    profileRepository.getActiveProfileId()
                }
                val isSameProfile = previousProfileId == profile.id
                // Clear profile caches BEFORE switching to keep profiles isolated.
                if (!isSameProfile) watchlistRepository.clearWatchlistCache()

                // Update ProfileManager cache immediately so profile-scoped keys are correct
                // for any work started after selection.
                profileManager.setCurrentProfileId(profile.id)
                profileManager.setCurrentProfileName(profile.name)

                if (!isSameProfile) {
                    withContext(Dispatchers.IO) {
                        profileRepository.setActiveProfile(profile.id)
                    }
                }
            } finally {
                _uiState.value = _uiState.value.copy(isSwitchingProfile = false)
            }
        }
    }

    fun switchProfile() {
        // Clear caches when leaving a profile to prevent data leakage
        watchlistRepository.clearWatchlistCache()

        viewModelScope.launch {
            profileRepository.clearActiveProfile()
        }
    }

    // ========== Add Profile ==========

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            newProfileName = "",
            selectedColorIndex = (_uiState.value.profiles.size) % ProfileColors.colors.size,
            selectedAvatarId = 0,
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false,
            isKidsProfile = false
        )
    }

    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun setNewProfileName(name: String) {
        _uiState.value = _uiState.value.copy(newProfileName = name)
    }

    fun setSelectedColorIndex(index: Int) {
        _uiState.value = _uiState.value.copy(
            selectedColorIndex = index,
            selectedAvatarId = 0,
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false
        )
    }

    fun setSelectedAvatarId(id: Int) {
        _uiState.value = _uiState.value.copy(
            selectedAvatarId = id,
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false
        )
    }

    fun setSelectedAvatarImage(uri: String) {
        _uiState.value = _uiState.value.copy(
            selectedAvatarImageUri = uri,
            useCustomAvatarImage = true,
            selectedAvatarId = 0
        )
    }

    fun removeSelectedAvatarImage() {
        _uiState.value = _uiState.value.copy(
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false
        )
    }

    fun createProfile() {
        val state = _uiState.value
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            val profile = profileRepository.createProfile(
                name = state.newProfileName.trim(),
                avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                avatarId = state.selectedAvatarId,
                isKidsProfile = false
            )
            val selectedImage = state.selectedAvatarImageUri
            if (state.useCustomAvatarImage && !selectedImage.isNullOrBlank()) {
                runCatching {
                    profileAvatarImageManager.importAvatar(profile.id, selectedImage)
                }.onSuccess { imported ->
                    profileRepository.updateProfile(
                        profile.copy(
                            avatarId = 0,
                            avatarImageVersion = imported.version,
                            avatarImageStoragePath = imported.storagePath
                        )
                    )
                }.onFailure {
                    showToast(context.getString(R.string.profile_avatar_import_failed), ToastType.ERROR)
                }
            }
            _uiState.value = _uiState.value.copy(showAddDialog = false)
            showToast(context.getString(R.string.profile_created_success), ToastType.SUCCESS)
        }
    }

    // ========== Edit Profile ==========

    fun showEditDialog(profile: Profile) {
        // If profile is locked, require PIN verification before allowing edit
        if (profile.isLocked && !profile.pin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = "edit"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                editingProfile = profile,
                newProfileName = profile.name,
                selectedColorIndex = ProfileColors.colors.indexOf(profile.avatarColor).takeIf { it >= 0 } ?: 0,
                selectedAvatarId = profile.avatarId,
                selectedAvatarImageUri = null,
                useCustomAvatarImage = profile.avatarImageVersion > 0L,
                isKidsProfile = false
            )
        }
    }

    fun hideEditDialog() {
        _uiState.value = _uiState.value.copy(editingProfile = null)
    }

    fun updateProfile() {
        val state = _uiState.value
        val editing = state.editingProfile ?: return
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            var updatedProfile = editing.copy(
                name = state.newProfileName.trim(),
                avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                avatarId = state.selectedAvatarId,
                isKidsProfile = false
            )
            val selectedImage = state.selectedAvatarImageUri
            if (state.useCustomAvatarImage && !selectedImage.isNullOrBlank()) {
                runCatching {
                    profileAvatarImageManager.importAvatar(editing.id, selectedImage)
                }.onSuccess { imported ->
                    updatedProfile = updatedProfile.copy(
                        avatarId = 0,
                        avatarImageVersion = imported.version,
                        avatarImageStoragePath = imported.storagePath
                    )
                }.onFailure {
                    showToast(context.getString(R.string.profile_avatar_import_failed), ToastType.ERROR)
                    return@launch
                }
            } else if (!state.useCustomAvatarImage) {
                profileAvatarImageManager.clearLocalAvatar(editing.id)
                updatedProfile = updatedProfile.copy(
                    avatarImageVersion = 0L,
                    avatarImageStoragePath = null
                )
            }

            profileRepository.updateProfile(updatedProfile)
            _uiState.value = _uiState.value.copy(editingProfile = null)
            showToast(context.getString(R.string.profile_updated), ToastType.SUCCESS)
        }
    }

    // ========== Manage Mode ==========

    fun toggleManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = !_uiState.value.isManageMode)
    }

    fun exitManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = false)
    }

    fun deleteProfile(profile: Profile) {
        // If profile is locked, require PIN verification before allowing delete
        if (profile.isLocked && !profile.pin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = "delete"
            )
        } else {
            performDeleteProfile(profile)
        }
    }

    private fun performDeleteProfile(profile: Profile) {
        viewModelScope.launch {
            val activeId = _uiState.value.activeProfile?.id
            profileRepository.deleteProfile(profile.id)
            showToast(context.getString(R.string.profile_deleted), ToastType.SUCCESS)
            if (activeId == profile.id) {
                watchlistRepository.clearWatchlistCache()
                profileManager.setCurrentProfileId("default")
                profileManager.setCurrentProfileName("default")
            }
        }
    }

    // ========== PIN Management ==========

    fun selectProfileWithLockCheck(profile: Profile) {
        if (profile.isLocked && !profile.pin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = "select"
            )
        } else {
            selectProfile(profile)
        }
    }

    fun showPinSetupDialog() {
        _uiState.value = _uiState.value.copy(
            showPinDialog = true,
            pinDialogMode = "setup"
        )
    }

    fun hidePinDialog() {
        _uiState.value = _uiState.value.copy(
            showPinDialog = false,
            pinDialogMode = "",
            pendingProfileForPin = null,
            pinContext = "",
            pinError = ""
        )
    }

    fun verifyPinAndSelectProfile(enteredPin: String) {
        val profile = _uiState.value.pendingProfileForPin ?: return
        val context = _uiState.value.pinContext
        if (PinUtil.verifyPin(enteredPin, profile.pin)) {
            hidePinDialog()
            // Handle PIN verification context
            when (context) {
                "edit" -> {
                    // Open edit dialog for locked profile
                    _uiState.value = _uiState.value.copy(
                        editingProfile = profile,
                        newProfileName = profile.name,
                        selectedColorIndex = ProfileColors.colors.indexOf(profile.avatarColor).takeIf { it >= 0 } ?: 0,
                        selectedAvatarId = profile.avatarId,
                        selectedAvatarImageUri = null,
                        useCustomAvatarImage = profile.avatarImageVersion > 0L,
                        isKidsProfile = false
                    )
                }
                "delete" -> {
                    // Delete locked profile after PIN verification
                    performDeleteProfile(profile)
                }
                else -> {
                    // "select": select the locked profile
                    selectProfile(profile)
                }
            }
        } else {
            // PIN incorrect - show error message in dialog
            _uiState.value = _uiState.value.copy(pinError = "")
            _uiState.value = _uiState.value.copy(
                pinError = "Incorrect PIN. Please try again."
            )
        }
    }

    fun setupProfilePin(pin: String) {
        val profile = _uiState.value.editingProfile ?: return
        // Hash the PIN before storing
        val hashedPin = PinUtil.hashPin(pin)
        val updatedProfile = profile.copy(pin = hashedPin, isLocked = true)
        viewModelScope.launch {
            profileRepository.updateProfile(updatedProfile)
            _uiState.value = _uiState.value.copy(editingProfile = updatedProfile)
            hidePinDialog()
            showToast(context.getString(R.string.profile_pin_set_success), ToastType.SUCCESS)
        }
    }

    fun removeProfilePin() {
        val profile = _uiState.value.editingProfile ?: return
        val updatedProfile = profile.copy(pin = null, isLocked = false)
        viewModelScope.launch {
            profileRepository.updateProfile(updatedProfile)
            _uiState.value = _uiState.value.copy(editingProfile = updatedProfile)
        }
    }
}
