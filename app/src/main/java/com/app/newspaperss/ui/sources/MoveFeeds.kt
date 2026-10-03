package com.app.newspaperss.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.plural
import com.app.newspaperss.core.ttrss.TtrssCategory
import com.app.newspaperss.data.FeedMoves
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A phone feed in the move sheet; [already] is its tt-rss feed, when tt-rss has it at the same address. */
data class MoveChoice(val sourceId: Long, val title: String, val ticked: Boolean, val already: PublicationEntity? = null)

/**
 * The move sheet: the phone feeds, and the one category the new ones go into. [categories] is
 * null while loading; [error] says why only Uncategorized is offered. [paper] is the category
 * the paper takes articles from, if one is chosen.
 */
data class MoveSheet(
    val feeds: List<MoveChoice>,
    val categories: List<TtrssCategory>? = null,
    val chosen: Int = UNCATEGORIZED_ID,
    val error: String? = null,
    val paper: TtrssCategory? = null,
) {
    val ticked get() = feeds.count { it.ticked }
}

/** Phone feeds in the server setup, as Sources' banner and the offer after sign-in show them. */
sealed interface PhoneFeeds {
    /** [count] feeds are fetched by this phone, [already] of them at an address tt-rss has too. */
    data class Offer(val count: Int, val already: Int) : PhoneFeeds

    /** A move under way: [lines] say how each feed asked about went, then [current] is [step] of [total]. */
    data class Moving(val lines: List<String>, val current: String?, val step: Int, val total: Int) : PhoneFeeds

    /** The last move took [moved] of [total]; [failures] are still on the phone, each with why. */
    data class Partial(val moved: Int, val total: Int, val failures: List<Pair<SourceEntity, String>>) : PhoneFeeds
}

/**
 * Moving phone feeds into tt-rss, for the screens that offer it: Sources and the sign-in in
 * Settings. The move itself runs as background work ([FeedMoves]); this is the sheet that starts
 * it and what's shown of it.
 */
class PhoneFeedMover(
    private val moves: FeedMoves,
    private val ttrss: TtrssRepository,
    private val settings: SettingsStore,
    private val sources: SourceRepository,
    private val scope: CoroutineScope,
) {
    /** The phone's own feeds in Sources' order: not curated lists, and not moved ones kept for their stars. */
    val feeds: Flow<List<SourceEntity>> = combine(sources.observe(), moves.state) { all, s ->
        all.filter { it.kind == SourceKind.FEED && !s.hides(it) }
    }.distinctUntilChanged()

    /** What to say about the phone feeds; null when there's nothing to say. */
    val status: Flow<PhoneFeeds?> = combine(sources.observe(), moves.state) { all, s -> all to s }.map { (all, s) ->
        val byId = all.associateBy { it.id }
        fun title(id: Long) = byId[id]?.title ?: "A removed feed"
        val visible = all.filter { it.kind == SourceKind.FEED && !s.hides(it) }
        when {
            s.running -> PhoneFeeds.Moving(
                lines = s.subscribed.map { "✓ ${title(it.sourceId)} · ${if (it.already) "already in tt-rss, removed here" else "subscribed"}" } +
                    s.failed.map { "${title(it.sourceId)} · not moved: ${it.reason}" },
                current = all.firstOrNull { it.id in s.queued }?.title,
                step = (s.total - s.queued.size + 1).coerceIn(1, s.total.coerceAtLeast(1)),
                total = s.total,
            )
            s.finished && s.failed.any { f -> visible.any { it.id == f.sourceId } } -> PhoneFeeds.Partial(
                s.moved,
                s.total,
                s.failed.mapNotNull { f -> visible.firstOrNull { it.id == f.sourceId }?.let { it to f.reason } },
            )
            visible.isNotEmpty() -> PhoneFeeds.Offer(visible.size, visible.count { ttrss.feedAt(it.url) != null })
            else -> null
        }
    }.distinctUntilChanged()

    /** How many a move that took every feed moved, once, for a snackbar; [resultShown] after. */
    val movedAll: Flow<Int> = moves.state.map { s -> s.moved.takeIf { s.finished && s.failed.isEmpty() } }.distinctUntilChanged().filterNotNull()

    fun resultShown() {
        scope.launch { moves.resultShown() }
    }

    private val _sheet = MutableStateFlow<MoveSheet?>(null)
    val sheet: StateFlow<MoveSheet?> = _sheet.asStateFlow()
    private var loading: Job? = null

    /**
     * Opens the move sheet with every phone feed ticked, or with [only] those ticked: the ones a
     * move couldn't take. The category starts on the one the paper takes articles from, so the
     * feeds stay in the paper, else the one last used, else Uncategorized.
     */
    fun open(only: Set<Long>? = null) {
        loading?.cancel()
        loading = scope.launch {
            val feeds = feeds.first()
            if (feeds.isEmpty()) return@launch
            _sheet.value = MoveSheet(feeds.map { MoveChoice(it.id, it.title, only == null || it.id in only, ttrss.feedAt(it.url)) })
            val account = sources.observe().first().firstOrNull { it.kind == SourceKind.TTRSS }
            val loaded = ttrss.categories(includeEmpty = true)
            val categories = pickerCategories((loaded as? TtrssRepository.Categories.Loaded)?.categories.orEmpty())
            val ids = categories.map { it.id }.toSet()
            val paper = account?.ttrssCategoryId?.let { id -> categories.firstOrNull { it.id == id } ?: account.ttrssCategoryTitle?.let { TtrssCategory(id, it) } }
            val chosen = listOfNotNull(paper?.id, settings.current().lastCategoryId).firstOrNull { it in ids } ?: UNCATEGORIZED_ID
            _sheet.value = _sheet.value?.copy(categories = categories, chosen = chosen, error = (loaded as? TtrssRepository.Categories.Failed)?.message, paper = paper)
        }
    }

    fun toggle(sourceId: Long) {
        _sheet.value = _sheet.value?.let { s -> s.copy(feeds = s.feeds.map { if (it.sourceId == sourceId) it.copy(ticked = !it.ticked) else it }) }
    }

    fun choose(categoryId: Int) {
        val s = _sheet.value ?: return
        if (s.categories?.any { it.id == categoryId } == true) _sheet.value = s.copy(chosen = categoryId)
    }

    fun close() {
        loading?.cancel()
        _sheet.value = null
    }

    /** Starts the move. Does nothing while another one is running: [FeedMoves.start] refuses it. */
    fun move() {
        val s = _sheet.value ?: return
        val category = s.categories?.firstOrNull { it.id == s.chosen } ?: return
        val ids = s.feeds.filter { it.ticked }.map { it.sourceId }
        if (ids.isEmpty()) return
        _sheet.value = null
        // Not cut short by leaving the screen straight after: the reader asked for it.
        scope.launch {
            withContext(NonCancellable) {
                if (moves.start(ids, category)) settings.update { it.copy(lastCategoryId = category.id) }
            }
        }
    }
}

/** "8 feeds are fetched by this phone, not your tt-rss", with the first words in bold. */
private fun offerText(count: Int) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(if (count == 1) "1 feed is fetched by this phone" else "$count feeds are fetched by this phone") }
    append(", not your tt-rss. Move ${if (count == 1) "it" else "them"} so read status matches your other apps.")
}

/**
 * Sources' banner above the phone feeds in the server setup: the offer, a move under way, or
 * what a move couldn't take.
 */
@Composable
fun PhoneFeedsBanner(status: PhoneFeeds, onMove: () -> Unit, onMoveOthers: (Set<Long>) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                is PhoneFeeds.Offer -> {
                    Text(offerText(status.count), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onMove) { Text(if (status.count == 1) "Move it to tt-rss" else "Move them to tt-rss") }
                }
                is PhoneFeeds.Moving -> MoveProgress(status)
                is PhoneFeeds.Partial -> {
                    val left = status.failures.size
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Moved ${status.moved} of ${status.total}.") }
                            append(if (left == 1) " The other one is still fetched by this phone:" else " The other $left are still fetched by this phone:")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    status.failures.forEach { (source, reason) -> Text("${source.title}: $reason", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { onMoveOthers(status.failures.map { it.first.id }.toSet()) }) {
                        Text(if (left == 1) "Move the other one" else "Move the other $left")
                    }
                }
            }
        }
    }
}

/**
 * A move under way as stepped lines, changing once per feed: no spinner or bar, which e-ink
 * redraws whole, and TalkBack reads the current line as it changes.
 */
@Composable
fun MoveProgress(status: PhoneFeeds.Moving) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        status.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            status.current?.let { "Moving ${status.step} of ${status.total} · $it" } ?: "Carrying their settings over…",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text("You can leave this screen; it carries on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings, straight after signing in from the phone setup: the same move, offered once. */
@Composable
fun MoveOffer(offer: PhoneFeeds.Offer, found: String?, onMove: () -> Unit, onNotNow: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            found?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val feeds = plural(offer.count, "phone feed")
            Text("Move your $feeds to tt-rss?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(
                listOfNotNull(
                    "tt-rss will fetch them, and read status will match your other apps. Their settings here come along.",
                    when (offer.already) {
                        0 -> null
                        1 -> "1 is already in your tt-rss."
                        else -> "${offer.already} are already in your tt-rss."
                    },
                ).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMove) { Text("Move ${offer.count}") }
                OutlinedButton(onClick = onNotNow) { Text("Not now") }
            }
        }
    }
}

/** The move sheet: every phone feed with a tick, one category for the new ones, Move and Cancel. */
@Composable
fun MoveSheetDialog(sheet: MoveSheet, mover: PhoneFeedMover) {
    // The category list replaces the feeds, then Done brings them back.
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        // Back from the category list returns to the feeds rather than closing the sheet.
        onDismissRequest = { if (picking) picking = false else mover.close() },
        title = { Text(if (picking) "Category" else "Move ${plural(sheet.ticked, "feed")} to tt-rss") },
        text = {
            if (picking) {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    sheet.categories.orEmpty().forEach { category ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = category.id == sheet.chosen, role = Role.RadioButton, onClick = { mover.choose(category.id) })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = category.id == sheet.chosen, onClick = null)
                            Text(category.title, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    MutedLine("To add a category, make it in tt-rss first.")
                }
            } else {
                MoveSheetFeeds(sheet, mover::toggle, onPick = { picking = true })
            }
        },
        confirmButton = {
            if (picking) {
                TextButton(onClick = { picking = false }) { Text("Done") }
            } else {
                TextButton(onClick = mover::move, enabled = sheet.ticked > 0 && sheet.categories != null) { Text("Move ${sheet.ticked}") }
            }
        },
        dismissButton = { if (!picking) TextButton(onClick = mover::close) { Text("Cancel") } },
    )
}

@Composable
private fun MoveSheetFeeds(sheet: MoveSheet, onToggle: (Long) -> Unit, onPick: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "tt-rss will fetch them, and read status will match your other apps. Their settings here come along.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        sheet.feeds.forEach { feed ->
            Row(
                Modifier.fillMaxWidth().toggleable(value = feed.ticked, role = Role.Checkbox, onValueChange = { onToggle(feed.sourceId) }).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = feed.ticked, onCheckedChange = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(feed.title)
                    feed.already?.let { p ->
                        MutedLine(if (p.outsideCategory) "Already in your tt-rss, outside your paper's category: just removed here" else "Already in your tt-rss: just removed here")
                    }
                }
            }
        }
        Text("Category for the new ones", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        val categories = sheet.categories
        if (categories == null) {
            Text("Loading your categories…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            val name = categories.firstOrNull { it.id == sheet.chosen }?.title ?: UNCATEGORIZED
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Category, $name" }) {
                Text(name, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        sheet.error?.let { Text("Couldn't load your categories. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        sheet.paper?.takeIf { categories != null && it.id != sheet.chosen }?.let {
            Text(
                "Your paper takes articles from ${it.title} only: feeds in another category won't reach it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MutedLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}
