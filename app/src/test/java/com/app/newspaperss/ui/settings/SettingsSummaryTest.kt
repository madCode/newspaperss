package com.app.newspaperss.ui.settings

import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.core.edition.Schedule
import com.app.newspaperss.edition.EditionSettings
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalTime
import java.util.Locale

class SettingsSummaryTest {
    private fun scheduled(vararg days: java.time.DayOfWeek) =
        Settings(scheduleEnabled = true, schedule = Schedule(LocalTime.of(6, 30), days.toSet()))

    // Newer JDKs put a narrow no-break space before AM.
    private fun schedule(s: Settings, notificationsOn: Boolean = true) =
        SettingsSummary.schedule(s, notificationsOn, Locale.US).let { it.copy(text = it.text.replace('\u202F', ' ')) }

    @Test
    fun theEditionLineNamesSizeCapAndOrder() {
        val s = Settings(edition = EditionSettings(minutes = 45, maxPerSource = 3, ordering = Ordering.SHUFFLE))
        assertEquals(Summary("About 45 minutes · 3 per source · shuffled"), SettingsSummary.edition(s))
    }

    @Test
    fun theScheduleNamesItsDaysTheShortestWay() {
        assertEquals("Off: make editions from Today", schedule(Settings(scheduleEnabled = false)).text)
        assertEquals("Ready by 6:30 AM, every day", schedule(Settings(scheduleEnabled = true)).text)
        assertEquals("Ready by 6:30 AM, weekdays", schedule(scheduled(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)).text)
        assertEquals("Ready by 6:30 AM, weekends", schedule(scheduled(SUNDAY, SATURDAY)).text)
        // In week order, whatever order they were picked in.
        assertEquals("Ready by 6:30 AM, Mon, Wed, Sun", schedule(scheduled(SUNDAY, WEDNESDAY, MONDAY)).text)
    }

    @Test
    fun aScheduleThatCantReachAnyoneSaysWhy() {
        assertEquals(Summary("Ready by 6:30 AM", "Pick at least one day."), schedule(scheduled()))
        assertEquals("Notifications are off.", schedule(Settings(scheduleEnabled = true), notificationsOn = false).problem)
        // Off, nothing is made, so there's nothing to hear about.
        assertEquals(null, schedule(Settings(scheduleEnabled = false), notificationsOn = false).problem)
    }

    @Test
    fun deliveryJoinsTheEReaderAndHowEditionsReachIt() {
        val kindle = Settings(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = " name_abc123@kindle.com ")
        assertEquals(Summary("Kindle · emailed to name_abc123@kindle.com"), SettingsSummary.delivery(kindle, folderReachable = true))
        assertEquals(Summary("Kobo · you send it"), SettingsSummary.delivery(Settings(device = Device.KOBO), folderReachable = true))
        val folder = Settings(device = Device.KOREADER, delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books")
        assertEquals(Summary("KOReader · saved to Books"), SettingsSummary.delivery(folder, folderReachable = true))
        assertEquals(Summary("You send it"), SettingsSummary.delivery(Settings(device = null), folderReachable = true))
    }

    @Test
    fun deliveryThatWontArriveSaysWhatToFix() {
        // Send falls back to the share sheet without a working address.
        val noAddress = Settings(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "name_abc123@kindle")
        assertEquals("Add your Kindle's email address.", SettingsSummary.delivery(noAddress, folderReachable = true).problem)
        val folder = Settings(device = Device.KOREADER, delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books")
        assertEquals("Can't reach Books.", SettingsSummary.delivery(folder, folderReachable = false).problem)
    }

    @Test
    fun notesAreOffOrSavedToTheirFolder() {
        assertEquals(Summary("Off"), SettingsSummary.notes(Settings(), reachable = true))
        val on = Settings(notesFolderUri = "content://vault", notesFolderName = "Vault")
        assertEquals(Summary("Saved to Vault"), SettingsSummary.notes(on, reachable = true))
        assertEquals("Can't reach Vault.", SettingsSummary.notes(on, reachable = false).problem)
    }
}
