package com.arflix.tv.ui.screens.player

/** Where the remote's focus is in the Netflix-layout controls. */
enum class ChromeZone { CENTER, BOTTOM }

enum class ChromeButton { EPISODES, AUDIO_SUBTITLES }

enum class ChromeKey { OK, LEFT, RIGHT, UP, DOWN }

enum class ChromeAction { NONE, TOGGLE_PLAY, SEEK_BACK, SEEK_FORWARD, OPEN_TRACKS, OPEN_EPISODES }

data class ChromeFocus(val zone: ChromeZone = ChromeZone.CENTER, val button: Int = 0)

/**
 * D-pad navigation for the player controls. Centre zone: OK = play/pause, Left/Right = ±10 s,
 * Down = bottom row. Bottom row: Left/Right move between the labeled buttons, OK opens one,
 * Up = back to the centre.
 */
object PlayerChrome {
    fun buttons(isSeries: Boolean): List<ChromeButton> =
        if (isSeries) listOf(ChromeButton.EPISODES, ChromeButton.AUDIO_SUBTITLES) else listOf(ChromeButton.AUDIO_SUBTITLES)

    fun onKey(focus: ChromeFocus, key: ChromeKey, buttons: List<ChromeButton>): Pair<ChromeFocus, ChromeAction> =
        when (focus.zone) {
            ChromeZone.CENTER -> when (key) {
                ChromeKey.OK -> focus to ChromeAction.TOGGLE_PLAY
                ChromeKey.LEFT -> focus to ChromeAction.SEEK_BACK
                ChromeKey.RIGHT -> focus to ChromeAction.SEEK_FORWARD
                ChromeKey.UP -> focus to ChromeAction.NONE
                ChromeKey.DOWN -> ChromeFocus(
                    ChromeZone.BOTTOM,
                    buttons.indexOf(ChromeButton.AUDIO_SUBTITLES).coerceAtLeast(0)
                ) to ChromeAction.NONE
            }
            ChromeZone.BOTTOM -> when (key) {
                ChromeKey.LEFT -> focus.copy(button = (focus.button - 1).coerceAtLeast(0)) to ChromeAction.NONE
                ChromeKey.RIGHT -> focus.copy(button = (focus.button + 1).coerceAtMost(buttons.lastIndex)) to ChromeAction.NONE
                ChromeKey.UP -> ChromeFocus() to ChromeAction.NONE
                ChromeKey.DOWN -> focus to ChromeAction.NONE
                ChromeKey.OK -> focus to when (buttons.getOrNull(focus.button)) {
                    ChromeButton.EPISODES -> ChromeAction.OPEN_EPISODES
                    ChromeButton.AUDIO_SUBTITLES -> ChromeAction.OPEN_TRACKS
                    null -> ChromeAction.NONE
                }
            }
        }
}
