package com.app.newspaperss.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
}

data class Settings(
    val edition: EditionSettings = EditionSettings(),
    val scheduleEnabled: Boolean = false,
    val schedule: Schedule = Schedule(),
    val delivery: DeliveryMethod = DeliveryMethod.SHARE,
    /** A persisted SAF tree URI, for [DeliveryMethod.FOLDER]. */
    val folderUri: String? = null,
    val folderName: String? = null,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
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
    }

    val settings: Flow<Settings> = store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val s = transform(read(prefs))
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
        }
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
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
            delivery = p[Keys.delivery]?.let { runCatching { DeliveryMethod.valueOf(it) }.getOrNull() } ?: d.delivery,
            folderUri = p[Keys.folderUri],
            folderName = p[Keys.folderName],
        )
    }
}
