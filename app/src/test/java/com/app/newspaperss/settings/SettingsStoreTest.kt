package com.app.newspaperss.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    private val dataStore by lazy { PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") } }
    private val store by lazy { SettingsStore(dataStore) }

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
                notesFolderUri = "content://tree/vault",
                notesFolderName = "Vault",
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
        assertEquals("content://tree/vault", s.notesFolderUri)
        assertEquals("Vault", s.notesFolderName)
    }

    @Test
    fun notesSavedBesideFolderEditionsCarryOverAsTheNotesFolder() = runTest {
        legacy(DeliveryMethod.FOLDER)
        assertEquals("content://tree/x", store.current().notesFolderUri)
        assertEquals("Books", store.current().notesFolderName)

        store.update { it.copy(notesFolderUri = null, notesFolderName = null) }
        assertNull("turning notes off sticks, rather than the old switch turning them back on", store.current().notesFolderUri)
    }

    @Test
    fun theOldNotesSwitchMeantNothingWithoutFolderDelivery() = runTest {
        legacy(DeliveryMethod.SHARE)
        assertNull(store.current().notesFolderUri)
    }

    private suspend fun legacy(delivery: DeliveryMethod) {
        dataStore.edit {
            it[stringPreferencesKey("delivery_method")] = delivery.name
            it[stringPreferencesKey("delivery_folder_uri")] = "content://tree/x"
            it[stringPreferencesKey("delivery_folder_name")] = "Books"
            it[booleanPreferencesKey("delivery_notes_with_edition")] = true
        }
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
