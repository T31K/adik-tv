package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

class NightDownloadsTest {
    private fun at(day: Int, hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
    }

    private fun Long.toCal() = Calendar.getInstance().apply { timeInMillis = this@toCal }

    @Test
    fun windowIsThreeToSeven() {
        assertThat(NightDownloads.inWindow(at(20, 2, 59))).isFalse()
        assertThat(NightDownloads.inWindow(at(20, 3, 0))).isTrue()
        assertThat(NightDownloads.inWindow(at(20, 6, 59))).isTrue()
        assertThat(NightDownloads.inWindow(at(20, 7, 0))).isFalse()
    }

    @Test
    fun eveningSchedulesTomorrowThreeAm() {
        val next = NightDownloads.nextTrigger(at(19, 21, 15)).toCal()
        assertThat(next.get(Calendar.DAY_OF_MONTH)).isEqualTo(20)
        assertThat(next.get(Calendar.HOUR_OF_DAY)).isEqualTo(3)
        assertThat(next.get(Calendar.MINUTE)).isEqualTo(0)
    }

    @Test
    fun earlyMorningSchedulesSameDayThreeAm() {
        val next = NightDownloads.nextTrigger(at(20, 1, 30)).toCal()
        assertThat(next.get(Calendar.DAY_OF_MONTH)).isEqualTo(20)
        assertThat(next.get(Calendar.HOUR_OF_DAY)).isEqualTo(3)
    }

    @Test
    fun insideWindowRechecksInThirtyMinutes() {
        val next = NightDownloads.nextTrigger(at(20, 4, 10)).toCal()
        assertThat(next.get(Calendar.HOUR_OF_DAY)).isEqualTo(4)
        assertThat(next.get(Calendar.MINUTE)).isEqualTo(40)
    }

    @Test
    fun lastCheckRollsOverToTomorrow() {
        val next = NightDownloads.nextTrigger(at(20, 6, 45)).toCal()
        assertThat(next.get(Calendar.DAY_OF_MONTH)).isEqualTo(21)
        assertThat(next.get(Calendar.HOUR_OF_DAY)).isEqualTo(3)
    }
}
