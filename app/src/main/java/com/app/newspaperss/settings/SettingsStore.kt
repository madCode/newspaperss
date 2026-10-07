package com.app.newspaperss.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

/**
 * Where the reader's sites come from: fetched by this phone, or from their own RSS server (tt-rss).
 * Never both: the reading list and curated lists stay on the phone either way.
 */
enum class FeedsFrom { PHONE, SERVER }

/**
 * How large the in-app article preview sets its text, as a percentage on top of Android's font
 * size. Stored by name, so a new size goes anywhere in the list without changing anyone's choice.
 */
enum class PreviewTextSize(val label: String, val percent: Int) {
    SMALL("Small", 85),
    DEFAULT("Default", 100),
    LARGE("Large", 120),
    LARGER("Larger", 145),
    LARGEST("Largest", 175),
}

/** How Listen reads: live in the phone's voice, or a podcast made ahead in Kokoro's. */
enum class ListenVoice { PHONE, PODCAST }

/** Kokoro's voices offered for the podcast; [com.app.newspaperss.listen.KokoroEngine] maps them to the model's. */
enum class PodcastVoice(val label: String, val accent: String) {
    HEART("Heart", "American"),
    MICHAEL("Michael", "American"),
    EMMA("Emma", "British"),
    GEORGE("George", "British"),
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
    /** Null only until [SettingsStore.settleFeedsFrom] has run once; see [feedsFrom]. */
    val feedsFrom: FeedsFrom? = null,
    /** The tt-rss category a site was last added to, offered first next time; 0 is Uncategorized. */
    val lastCategoryId: Int? = null,
    /** The tt-rss categories folded on Sources, by name; "" is Uncategorized. */
    val foldedCategories: Set<String> = emptySet(),
    /** How fast Listen reads, 1.0 being the voice's normal pace. */
    val listenSpeed: Float = 1f,
    val listenVoice: ListenVoice = ListenVoice.PHONE,
    val podcastVoice: PodcastVoice = PodcastVoice.HEART,
    /**
     * Minutes this phone takes to make a minute of the podcast, once warm; null until Kokoro has
     * been checked on it. See [com.app.newspaperss.core.listen.PodcastPace].
     */
    val podcastPace: Float? = null,
    /** Whether [podcastPace] comes from real podcasts made on this phone, not only the check. */
    val podcastPaceMeasured: Boolean = false,
) {
    /**
     * The setup chosen, or before it's settled, the one [SettingsStore.settleFeedsFrom] will
     * choose: the server if a tt-rss account is there.
     */
    fun feedsFrom(hasServer: Boolean): FeedsFrom = feedsFrom ?: if (hasServer) FeedsFrom.SERVER else FeedsFrom.PHONE

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
        val feedsFrom = stringPreferencesKey("feeds_from")
        val lastCategoryId = intPreferencesKey("ttrss_last_category_id")
        val foldedCategories = stringSetPreferencesKey("sources_folded_categories")
        val listenSpeed = floatPreferencesKey("listen_speed")
        val listenVoice = stringPreferencesKey("listen_voice")
        val podcastVoice = stringPreferencesKey("podcast_voice")
        val podcastPace = floatPreferencesKey("podcast_pace")
        val podcastPaceMeasured = booleanPreferencesKey("podcast_pace_measured")
        /** Notes saved beside editions in the delivery folder; read as that folder being the notes folder. */
        val legacyNotesWithEdition = booleanPreferencesKey("delivery_notes_with_edition")
    }

    val settings: Flow<Settings> = store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val s = transform(read(prefs))
            prefs[Keys.onboarded] = s.onboarded
            prefs.setOrRemove(Keys.device, s.device?.name)
            prefs[Keys.minutes] = s.edition.minutes
            prefs[Keys.maxPerSource] = s.edition.maxPerSource
            prefs[Keys.ordering] = s.edition.ordering.name
            prefs[Keys.wpm] = s.edition.wordsPerMinute
            prefs[Keys.scheduleEnabled] = s.scheduleEnabled
            prefs[Keys.scheduleTime] = s.schedule.time.toString()
            prefs[Keys.scheduleDays] = s.schedule.days.map { it.name }.toSet()
            prefs[Keys.delivery] = s.delivery.name
            prefs.setOrRemove(Keys.folderUri, s.folderUri)
            prefs.setOrRemove(Keys.folderName, s.folderName)
            prefs.setOrRemove(Keys.notesFolderUri, s.notesFolderUri)
            prefs.setOrRemove(Keys.notesFolderName, s.notesFolderName)
            prefs[Keys.previewTextSize] = s.previewTextSize.name
            prefs.setOrRemove(Keys.kindleEmail, s.kindleEmail)
            prefs.setOrRemove(Keys.mailApp, s.mailApp)
            prefs.setOrRemove(Keys.feedsFrom, s.feedsFrom?.name)
            prefs.setOrRemove(Keys.lastCategoryId, s.lastCategoryId)
            prefs[Keys.foldedCategories] = s.foldedCategories
            prefs[Keys.listenSpeed] = s.listenSpeed
            prefs[Keys.listenVoice] = s.listenVoice.name
            prefs[Keys.podcastVoice] = s.podcastVoice.name
            if (s.podcastPace != null) prefs[Keys.podcastPace] = s.podcastPace else prefs.remove(Keys.podcastPace)
            prefs[Keys.podcastPaceMeasured] = s.podcastPaceMeasured
            prefs.remove(Keys.legacyNotesWithEdition)
        }
    }

    /**
     * Makes the setup explicit the first time it's read after an upgrade: the server if
     * [hasServer] (a tt-rss account was added before there was a choice), else this phone.
     * Once set, it only changes when the reader changes it.
     */
    suspend fun settleFeedsFrom(hasServer: suspend () -> Boolean): FeedsFrom {
        var settled: FeedsFrom? = null
        store.edit { prefs ->
            val current = prefs[Keys.feedsFrom]?.enumOrNull<FeedsFrom>()
            settled = current ?: (if (hasServer()) FeedsFrom.SERVER else FeedsFrom.PHONE).also { prefs[Keys.feedsFrom] = it.name }
        }
        return settled!!
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        val delivery = p[Keys.delivery]?.enumOrNull<DeliveryMethod>() ?: d.delivery
        // Only a folder that was being delivered to had notes saved in it.
        val legacyNotes = p[Keys.legacyNotesWithEdition] == true && delivery == DeliveryMethod.FOLDER
        return Settings(
            onboarded = p[Keys.onboarded] ?: false,
            device = p[Keys.device]?.enumOrNull<Device>(),
            edition = EditionSettings(
                minutes = p[Keys.minutes] ?: d.edition.minutes,
                maxPerSource = p[Keys.maxPerSource] ?: d.edition.maxPerSource,
                ordering = p[Keys.ordering]?.enumOrNull<Ordering>() ?: d.edition.ordering,
                wordsPerMinute = p[Keys.wpm] ?: d.edition.wordsPerMinute,
            ),
            scheduleEnabled = p[Keys.scheduleEnabled] ?: d.scheduleEnabled,
            schedule = Schedule(
                time = p[Keys.scheduleTime]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.schedule.time,
                days = p[Keys.scheduleDays]?.mapNotNull { it.enumOrNull<DayOfWeek>() }?.toSet() ?: d.schedule.days,
            ),
            delivery = delivery,
            folderUri = p[Keys.folderUri],
            folderName = p[Keys.folderName],
            notesFolderUri = p[Keys.notesFolderUri] ?: p[Keys.folderUri]?.takeIf { legacyNotes },
            notesFolderName = p[Keys.notesFolderName] ?: p[Keys.folderName]?.takeIf { legacyNotes },
            previewTextSize = p[Keys.previewTextSize]?.enumOrNull<PreviewTextSize>() ?: d.previewTextSize,
            kindleEmail = p[Keys.kindleEmail],
            mailApp = p[Keys.mailApp],
            feedsFrom = p[Keys.feedsFrom]?.enumOrNull<FeedsFrom>(),
            lastCategoryId = p[Keys.lastCategoryId],
            foldedCategories = p[Keys.foldedCategories] ?: emptySet(),
            listenSpeed = p[Keys.listenSpeed] ?: d.listenSpeed,
            listenVoice = p[Keys.listenVoice]?.let { runCatching { ListenVoice.valueOf(it) }.getOrNull() } ?: d.listenVoice,
            podcastVoice = p[Keys.podcastVoice]?.let { runCatching { PodcastVoice.valueOf(it) }.getOrNull() } ?: d.podcastVoice,
            podcastPace = p[Keys.podcastPace],
            podcastPaceMeasured = p[Keys.podcastPaceMeasured] ?: d.podcastPaceMeasured,
        )
    }
}

private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value != null) this[key] = value else remove(key)
}

/** A stored enum's value, or null for one this version doesn't have (from a newer one, say). */
private inline fun <reified E : Enum<E>> String.enumOrNull(): E? = enumValues<E>().firstOrNull { it.name == this }
