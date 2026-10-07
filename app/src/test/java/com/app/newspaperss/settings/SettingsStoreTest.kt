package com.app.newspaperss.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.app.newspaperss.core.edition.Ordering
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                previewTextSize = PreviewTextSize.LARGER,
                kindleEmail = "me_42@kindle.com",
                mailApp = "com.example.mail",
                feedsFrom = FeedsFrom.SERVER,
                listenSpeed = 1.5f,
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
        assertEquals(PreviewTextSize.LARGER, s.previewTextSize)
        assertEquals("me_42@kindle.com", s.kindleEmail)
        assertEquals("com.example.mail", s.mailApp)
        assertEquals(FeedsFrom.SERVER, s.feedsFrom)
        assertEquals(1.5f, s.listenSpeed)
    }

    @Test
    fun anUpgradeWithATtrssAccountLandsInTheServerSetupAndWithoutOneOnThePhone() = runTest {
        assertNull("unset before it's settled", store.current().feedsFrom)
        assertEquals(FeedsFrom.SERVER, store.settleFeedsFrom { true })
        assertEquals(FeedsFrom.SERVER, store.current().feedsFrom)

        val other = SettingsStore(PreferenceDataStoreFactory.create { tmp.newFile("other.preferences_pb") })
        assertEquals(FeedsFrom.PHONE, other.settleFeedsFrom { false })
        assertEquals(FeedsFrom.PHONE, other.current().feedsFrom)
    }

    @Test
    fun onceSettledTheSetupIsntGuessedAgain() = runTest {
        store.settleFeedsFrom { false }
        var asked = false
        assertEquals("a tt-rss account added later doesn't switch it", FeedsFrom.PHONE, store.settleFeedsFrom { asked = true; true })
        assertFalse(asked)
        store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
        assertEquals("nor does one removed", FeedsFrom.SERVER, store.settleFeedsFrom { false })
        store.update { it.copy(edition = it.edition.copy(minutes = 20)) }
        assertEquals("other changes keep it", FeedsFrom.SERVER, store.current().feedsFrom)
    }

    @Test
    fun beforeItsSettledTheSetupFollowsTheAccount() {
        assertEquals(FeedsFrom.SERVER, Settings().feedsFrom(hasServer = true))
        assertEquals(FeedsFrom.PHONE, Settings().feedsFrom(hasServer = false))
        assertEquals(FeedsFrom.PHONE, Settings(feedsFrom = FeedsFrom.PHONE).feedsFrom(hasServer = true))
    }

    @Test
    fun emailToKindleSurvivesARoundTripAndAskEachTimeStaysUnset() = runTest {
        store.update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com", mailApp = "com.example.mail") }
        store.update { it.copy(mailApp = null) }
        val s = store.current()
        assertEquals(DeliveryMethod.KINDLE_EMAIL, s.delivery)
        assertEquals(KindleEmail("me_42@kindle.com", null), s.kindleEmailTarget)
    }

    @Test
    fun aDeliveryThisVersionDoesntKnowReadsAsSharing() = runTest {
        dataStore.edit { it[stringPreferencesKey("delivery_method")] = "CARRIER_PIGEON" }
        assertEquals(DeliveryMethod.SHARE, store.current().delivery)
    }

    @Test
    fun emailDeliveryWithoutAUsableAddressSharesInstead() {
        val email = Settings(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = " me_42@kindle.com ")
        assertEquals("me_42@kindle.com", email.kindleEmailTarget?.address)
        assertNull(email.copy(kindleEmail = null).kindleEmailTarget)
        assertNull(email.copy(kindleEmail = "me_42@kindle").kindleEmailTarget)
        assertNull("an address left from before isn't used once another delivery is chosen", email.copy(delivery = DeliveryMethod.SHARE).kindleEmailTarget)
    }

    @Test
    fun kindleAddressesAreCheckedLightly() {
        assertTrue(KindleAddress.isValid("first.last_7@kindle.com"))
        assertTrue("any domain is an address; it's only warned about", KindleAddress.isValid("me@example.org"))
        listOf("", "me", "me@", "@kindle.com", "me@kindle", "me@@kindle.com", "me @kindle.com").forEach { assertFalse(it, KindleAddress.isValid(it)) }
        assertTrue(KindleAddress.looksLikeKindle("Me@Kindle.com"))
        assertTrue(KindleAddress.looksLikeKindle("me@kindle.cn"))
        assertFalse(KindleAddress.looksLikeKindle("me@gmail.com"))
    }

    @Test
    fun aTextSizeThisVersionDoesntKnowReadsAsDefault() = runTest {
        // Written by a newer version that has more sizes, then the app downgraded.
        dataStore.edit { it[stringPreferencesKey("preview_text_size")] = "HUGE" }
        assertEquals(PreviewTextSize.DEFAULT, store.current().previewTextSize)
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
