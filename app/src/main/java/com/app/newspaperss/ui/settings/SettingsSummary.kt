package com.app.newspaperss.ui.settings

import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.core.ttrss.TtrssException
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssStatus
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.ui.listen.speedLabel
import com.app.newspaperss.work.EditionScheduler
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One line on a Settings summary row, and what needs fixing there, if anything. Problems show on
 * the summary so nothing that needs attention is hidden a tap away.
 */
data class Summary(val text: String, val problem: String? = null)

/** The pages Settings opens from its summary, in the order the summary lists them. */
enum class SettingsPage(val slug: String, val title: String) {
    EDITION("edition", "Your edition"),
    SCHEDULE("schedule", "Schedule"),
    TEXT_SIZE("text-size", "Article text size"),
    LISTENING("listening", "Listening"),
    // One page: the e-reader decides which delivery choices are offered.
    DELIVERY("delivery", "E-reader & delivery"),
    FEEDS("feeds", "Where your feeds live"),
    NOTES("notes", "Reading notes"),
    ;

    companion object {
        fun of(slug: String?): SettingsPage? = entries.firstOrNull { it.slug == slug }
    }
}

object SettingsSummary {
    fun edition(s: Settings): Summary {
        val order = when (s.edition.ordering) {
            Ordering.TAKE_TURNS -> "take turns"
            Ordering.IN_ORDER -> "source by source"
            Ordering.SHUFFLE -> "shuffled"
        }
        return Summary("About ${s.edition.minutes} minutes · ${s.edition.maxPerSource} per source · $order")
    }

    fun schedule(s: Settings, notificationsOn: Boolean, locale: Locale): Summary {
        if (!s.scheduleEnabled) return Summary("Off: make editions from Today")
        val time = "Ready by " + s.schedule.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        val days = s.schedule.days
        val text = when {
            days.isEmpty() -> time
            days.size == DayOfWeek.entries.size -> "$time, every day"
            days == WEEKDAYS -> "$time, weekdays"
            days == WEEKEND -> "$time, weekends"
            else -> "$time, " + days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        }
        val start = EditionScheduler.podcastStart(s)
            ?.let { " · starts " + it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) + " for the podcast" }.orEmpty()
        val problem = when {
            days.isEmpty() -> "Pick at least one day."
            !notificationsOn -> "Notifications are off."
            else -> null
        }
        return Summary(text + start, problem)
    }

    fun delivery(s: Settings, folderReachable: Boolean): Summary {
        val folder = s.folderName ?: "your folder"
        val target = s.kindleEmailTarget
        val (how, problem) = when (s.delivery) {
            DeliveryMethod.KINDLE_EMAIL ->
                if (target != null) "emailed to ${target.address}" to null else "emailed to your Kindle" to "Add your Kindle's email address."
            DeliveryMethod.SHARE -> "you send it" to null
            DeliveryMethod.FOLDER -> "saved to $folder" to (if (folderReachable) null else "Can't reach $folder.")
        }
        val text = listOfNotNull(s.device?.let(::shortName), how).joinToString(" · ")
        return Summary(text.replaceFirstChar { it.uppercase() }, problem)
    }

    fun textSize(s: Settings): Summary = Summary(s.previewTextSize.label)

    /**
     * Not a problem without a voice: many e-readers have none, and their readers may never want
     * Listen. The page says how to get one.
     */
    fun listening(s: Settings, hasVoice: Boolean): Summary = Summary(
        when {
            s.listenVoice == ListenVoice.PODCAST -> "Podcast in Kokoro (${s.podcastVoice.label}) · ${speedLabel(s.listenSpeed)}"
            hasVoice -> "This phone's voice · ${speedLabel(s.listenSpeed)}"
            else -> "No voice on this phone"
        },
    )

    /** "about 55 minutes", "about 2 hours", "about 2½ hours": as close as an estimate deserves. */
    fun aboutTime(minutes: Double): String {
        if (minutes < 90) return "about ${(minutes / 5).roundToInt().coerceAtLeast(1) * 5} minutes"
        val halves = (minutes / 30).roundToInt()
        val whole = halves / 2
        return "about " + (if (halves % 2 == 0) "$whole hours" else "$whole½ hours")
    }

    fun notes(s: Settings, reachable: Boolean): Summary {
        val name = s.notesFolderName ?: "your folder"
        return when {
            s.notesFolderUri == null -> Summary("Off")
            reachable -> Summary("Saved to $name")
            else -> Summary("Saved to $name", "Can't reach $name.")
        }
    }

    fun feedsFrom(choice: FeedsFrom, status: TtrssStatus): Summary {
        if (choice == FeedsFrom.PHONE) return Summary("Sites you pick")
        val source = status.source ?: return Summary("Your tt-rss", "Not signed in. Tap to sign in.")
        val text = "Your tt-rss · ${SourceRepository.hostOf(source.url)}"
        return Summary(text, if (loginProblem(status) != null) "Can't sign in to tt-rss. Tap to sign in again." else null)
    }

    /**
     * Why the account's login doesn't work, or null: no login this phone can use for it (its
     * password's key was lost, or the sign-in never finished), or tt-rss refusing it at the last sync.
     */
    fun loginProblem(status: TtrssStatus): String? {
        val source = status.source ?: return null
        if (!status.usable) return "Can't sign in: this phone has no working login for it. Sign in again."
        return source.lastError?.takeIf { it in LOGIN_REFUSED }?.let { "Can't sign in: $it" }
    }

    private val LOGIN_REFUSED = setOf(TtrssException.LoginFailed().message, TtrssException.ApiDisabled().message)

    // The labels in the picker explain themselves; a summary line has room for a name.
    private fun shortName(device: Device) = when (device) {
        Device.KINDLE -> "Kindle"
        Device.KOBO -> "Kobo"
        Device.BOOX -> "Boox"
        Device.POCKETBOOK -> "PocketBook"
        Device.KOREADER -> "KOReader"
        Device.OTHER -> "Other e-reader"
    }

    private val WEEKDAYS = DayOfWeek.entries.toSet() - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
