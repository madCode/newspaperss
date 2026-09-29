package com.app.newspaperss.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.app.newspaperss.core.edition.Ordering
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.DayOfWeek
import java.time.LocalTime

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") }) }

    @Test
    fun everyFieldSurvivesARoundTrip() = runTest {
        store.update {
            it.copy(
                edition = it.edition.copy(minutes = 45, maxPerSource = 2, ordering = Ordering.SHUFFLE, wordsPerMinute = 300),
                scheduleEnabled = true,
                schedule = it.schedule.copy(time = LocalTime.of(7, 15), days = setOf(DayOfWeek.SUNDAY)),
                delivery = DeliveryMethod.FOLDER,
                folderUri = "content://tree/x",
                folderName = "Books",
            )
        }
        val s = store.current()
        assertEquals(45, s.edition.minutes)
        assertEquals(2, s.edition.maxPerSource)
        assertEquals(Ordering.SHUFFLE, s.edition.ordering)
        assertEquals(300, s.edition.wordsPerMinute)
        assertEquals(true, s.scheduleEnabled)
        assertEquals(LocalTime.of(7, 15), s.schedule.time)
        assertEquals(setOf(DayOfWeek.SUNDAY), s.schedule.days)
        assertEquals(DeliveryMethod.FOLDER, s.delivery)
        assertEquals("content://tree/x", s.folderUri)
        assertEquals("Books", s.folderName)
    }

    @Test
    fun clearingTheFolderRemovesIt() = runTest {
        store.update { it.copy(folderUri = "content://tree/x", folderName = "Books") }
        store.update { it.copy(folderUri = null, folderName = null) }
        assertNull(store.current().folderUri)
    }

    @Test
    fun noDaysChosenStaysNoDays() = runTest {
        store.update { it.copy(schedule = it.schedule.copy(days = emptySet())) }
        assertEquals(emptySet<DayOfWeek>(), store.current().schedule.days)
    }
}
