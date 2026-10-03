package com.app.newspaperss.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.core.edition.Schedule
import com.app.newspaperss.edition.EditionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime

enum class DeliveryMethod {
    /** The share sheet (Send to Kindle, email…); the reader confirms it was sent. */
    SHARE,
    /** Saved into a folder the reader picked, e.g. one their Kobo or KOReader syncs. */
    FOLDER,
    /** Emailed to the Kindle's own Send-to-Kindle address from the reader's mail app; see [Settings.kindleEmailTarget]. */
    KINDLE_EMAIL,
}

/** Where Send emails an edition: a Kindle's own address, from [mailApp] or, if null, whichever mail app the reader picks each time. */
data class KindleEmail(val address: String, val mailApp: String?)

object KindleAddress {
    // Light on purpose: only catching a half-typed or pasted-wrong address, not policing the format.
    private val shape = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$")

    fun isValid(address: String): Boolean = shape.matches(address.trim())

    /** Amazon's addresses end in kindle.com (kindle.cn in China); anything else is likely the wrong address, though not certainly. */
    fun looksLikeKindle(address: String): Boolean = address.trim().lowercase().let { it.endsWith("@kindle.com") || it.endsWith("@kindle.cn") }
}

/** The reader's e-reader, chosen during onboarding; it decides the default delivery and the tips shown. */
enum class Device(val label: String) {
    KINDLE("Kindle"),
    KOBO("Kobo"),
    BOOX("Boox or another Android e-reader"),
    POCKETBOOK("PocketBook"),
    KOREADER("KOReader"),
    OTHER("Something else / just the file"),
}

/**
 * Whether Open is any use: it opens the book on this phone, and a Kindle or Kobo reader gets it
 * onto their e-reader by sending it. Unknown (settings not loaded yet) keeps Open.
 */
val Device?.offersOpen: Boolean get() = this != Device.KINDLE && this != Device.KOBO

/** How large the in-app article preview sets its text, as a percentage of the WebView's default. */
enum class PreviewTextSize(val label: String, val percent: Int) {
    SMALL("Small", 85),
    DEFAULT("Default", 100),
    LARGE("Large", 120),
    LARGER("Larger", 145),
}

data class Settings(
    val onboarded: Boolean = false,
    val device: Device? = null,
    val edition: EditionSettings = EditionSettings(),
    val scheduleEnabled: Boolean = false,
    val schedule: Schedule = Schedule(),
    val delivery: DeliveryMethod = DeliveryMethod.SHARE,
    /** A persisted SAF tree URI, for [DeliveryMethod.FOLDER]. */
    val folderUri: String? = null,
    val folderName: String? = null,
    /** A persisted SAF tree URI where each delivered edition's Markdown reading notes are saved, whatever [delivery] is. */
    val notesFolderUri: String? = null,
    val notesFolderName: String? = null,
    val previewTextSize: PreviewTextSize = PreviewTextSize.DEFAULT,
    /** The Kindle's Send-to-Kindle address, for [DeliveryMethod.KINDLE_EMAIL]. */
    val kindleEmail: String? = null,
    /** The package of the mail app Send opens for [DeliveryMethod.KINDLE_EMAIL]; null asks each time. */
    val mailApp: String? = null,
    /** A tt-rss account's feeds are shown under it on Sources; folded until the reader opens them. */
    val feedsShown: Boolean = false,
) {
    /**
     * Where Send emails editions, or null to share them as usual: email delivery is chosen and has
     * a usable address. Without one (cleared in Settings), Send falls back to the share sheet.
     */
    val kindleEmailTarget: KindleEmail?
        get() = kindleEmail?.takeIf { delivery == DeliveryMethod.KINDLE_EMAIL && KindleAddress.isValid(it) }?.let { KindleEmail(it.trim(), mailApp) }
}

// A corrupt settings file resets to defaults rather than crashing every launch.
private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val device = stringPreferencesKey("device")
        val minutes = intPreferencesKey("edition_minutes")
        val maxPerSource = intPreferencesKey("edition_max_per_source")
        val ordering = stringPreferencesKey("edition_ordering")
        val wpm = intPreferencesKey("reading_wpm")
        val scheduleEnabled = booleanPreferencesKey("schedule_enabled")
        val scheduleTime = stringPreferencesKey("schedule_time")
        val scheduleDays = stringSetPreferencesKey("schedule_days")
        val delivery = stringPreferencesKey("delivery_method")
        val folderUri = stringPreferencesKey("delivery_folder_uri")
        val folderName = stringPreferencesKey("delivery_folder_name")
        val notesFolderUri = stringPreferencesKey("notes_folder_uri")
        val notesFolderName = stringPreferencesKey("notes_folder_name")
        val previewTextSize = stringPreferencesKey("preview_text_size")
        val kindleEmail = stringPreferencesKey("kindle_email")
        val mailApp = stringPreferencesKey("kindle_email_mail_app")
        val feedsShown = booleanPreferencesKey("sources_feeds_shown")
        /** Notes saved beside editions in the delivery folder; read as that folder being the notes folder. */
        val legacyNotesWithEdition = booleanPreferencesKey("delivery_notes_with_edition")
    }

    val settings: Flow<Settings> = store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val s = transform(read(prefs))
            prefs[Keys.onboarded] = s.onboarded
            if (s.device != null) prefs[Keys.device] = s.device.name else prefs.remove(Keys.device)
            prefs[Keys.minutes] = s.edition.minutes
            prefs[Keys.maxPerSource] = s.edition.maxPerSource
            prefs[Keys.ordering] = s.edition.ordering.name
            prefs[Keys.wpm] = s.edition.wordsPerMinute
            prefs[Keys.scheduleEnabled] = s.scheduleEnabled
            prefs[Keys.scheduleTime] = s.schedule.time.toString()
            prefs[Keys.scheduleDays] = s.schedule.days.map { it.name }.toSet()
            prefs[Keys.delivery] = s.delivery.name
            if (s.folderUri != null) prefs[Keys.folderUri] = s.folderUri else prefs.remove(Keys.folderUri)
            if (s.folderName != null) prefs[Keys.folderName] = s.folderName else prefs.remove(Keys.folderName)
            if (s.notesFolderUri != null) prefs[Keys.notesFolderUri] = s.notesFolderUri else prefs.remove(Keys.notesFolderUri)
            if (s.notesFolderName != null) prefs[Keys.notesFolderName] = s.notesFolderName else prefs.remove(Keys.notesFolderName)
            prefs[Keys.previewTextSize] = s.previewTextSize.name
            if (s.kindleEmail != null) prefs[Keys.kindleEmail] = s.kindleEmail else prefs.remove(Keys.kindleEmail)
            if (s.mailApp != null) prefs[Keys.mailApp] = s.mailApp else prefs.remove(Keys.mailApp)
            prefs[Keys.feedsShown] = s.feedsShown
            prefs.remove(Keys.legacyNotesWithEdition)
        }
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        val delivery = p[Keys.delivery]?.let { runCatching { DeliveryMethod.valueOf(it) }.getOrNull() } ?: d.delivery
        // Only a folder that was being delivered to had notes saved in it.
        val legacyNotes = p[Keys.legacyNotesWithEdition] == true && delivery == DeliveryMethod.FOLDER
        return Settings(
            onboarded = p[Keys.onboarded] ?: false,
            device = p[Keys.device]?.let { runCatching { Device.valueOf(it) }.getOrNull() },
            edition = EditionSettings(
                minutes = p[Keys.minutes] ?: d.edition.minutes,
                maxPerSource = p[Keys.maxPerSource] ?: d.edition.maxPerSource,
                ordering = p[Keys.ordering]?.let { runCatching { Ordering.valueOf(it) }.getOrNull() } ?: d.edition.ordering,
                wordsPerMinute = p[Keys.wpm] ?: ReadingTime.DEFAULT_WPM,
            ),
            scheduleEnabled = p[Keys.scheduleEnabled] ?: d.scheduleEnabled,
            schedule = Schedule(
                time = p[Keys.scheduleTime]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.schedule.time,
                days = p[Keys.scheduleDays]?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet() ?: d.schedule.days,
            ),
            delivery = delivery,
            folderUri = p[Keys.folderUri],
            folderName = p[Keys.folderName],
            notesFolderUri = p[Keys.notesFolderUri] ?: p[Keys.folderUri]?.takeIf { legacyNotes },
            notesFolderName = p[Keys.notesFolderName] ?: p[Keys.folderName]?.takeIf { legacyNotes },
            previewTextSize = p[Keys.previewTextSize]?.let { runCatching { PreviewTextSize.valueOf(it) }.getOrNull() } ?: d.previewTextSize,
            kindleEmail = p[Keys.kindleEmail],
            mailApp = p[Keys.mailApp],
            feedsShown = p[Keys.feedsShown] ?: d.feedsShown,
        )
    }
}
