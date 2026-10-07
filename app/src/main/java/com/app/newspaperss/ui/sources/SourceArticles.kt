package com.app.newspaperss.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.ui.components.ArticleRowFrame
import com.app.newspaperss.ui.components.StarToggle
import com.app.newspaperss.ui.components.openInBrowser
import com.app.newspaperss.ui.components.rowIconSize
import java.util.Locale

@Composable
internal fun ArticlesHeading(articles: List<ArticleEntity>, selecting: Boolean, onSelect: () -> Unit) {
    // The list stops at the newest few, so its count isn't everything the source has waiting.
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (articles.isEmpty()) "Newest articles" else "Newest articles · ${articles.size}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics {
                heading()
                contentDescription = if (articles.isEmpty()) "Newest articles" else "Newest articles, ${articles.size}"
            },
        )
        if (articles.isNotEmpty()) {
            // Hidden rather than removed while selecting: removing it would change the heading's
            // height and width, and move every row on the way in and out of selection mode.
            TextButton(
                onClick = onSelect,
                enabled = !selecting,
                modifier = Modifier.heightIn(min = 48.dp).then(if (selecting) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
            ) { Text("Select") }
        }
    }
    if (articles.isEmpty()) Text("No articles yet.", modifier = Modifier.padding(horizontal = 16.dp))
    if (articles.isNotEmpty()) {
        val canChange = articles.any { it.state != ArticleState.IN_EDITION }
        // Nothing while selecting: the selection bar says what to do then.
        val shown = TAP_HELP.takeIf { canChange && !selecting }
        // The longest wording is laid out invisibly under the one shown, so the line keeps one
        // height as what it says changes: a change in its line count would move every row below it.
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
            HelperText(TAP_HELP, shown = false)
            if (shown != null) HelperText(shown, shown = true)
        }
    }
}

private const val TAP_HELP = "Tap ● to mark one read or unread, and ☆ to put it in your next edition."

@Composable
private fun HelperText(text: String, shown: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // TalkBack would read ☆ as "white star".
        modifier = if (shown) Modifier.semantics { contentDescription = text.replace("☆", "the star") } else Modifier.alpha(0f).clearAndSetSemantics {},
    )
}

/**
 * The actions for the selected articles, labelled once here instead of on every row. Counts are
 * of what each action would change: articles in an unsent edition can be selected but nothing
 * applies to them. The buttons stack at large font sizes rather than truncating.
 */
@Composable
internal fun SelectionBar(
    chosen: List<ArticleEntity>,
    building: Boolean,
    onStar: (List<Long>, Boolean) -> Unit,
    onMarkRead: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waiting = chosen.filter { it.state == ArticleState.NEW }.map { it.id }
    val starrable = chosen.filter { it.state != ArticleState.IN_EDITION }
    val takeOut = starrable.isNotEmpty() && starrable.all { it.starredAt != null }
    val toChange = starrable.filter { (it.starredAt != null) == takeOut }.map { it.id }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            // A rule, not a shadow: it still reads on e-ink.
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (toChange.isEmpty() && waiting.isEmpty()) {
                Text(
                    if (chosen.isEmpty()) "Tap articles to choose them." else "These are in an edition you haven't sent yet, so there's nothing to change.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                return@Column
            }
            val starLabel = if (takeOut) "Take out of next edition" else "Next edition"
            val markLabel = "Mark ${waiting.size} as read"
            val measurer = rememberTextMeasurer()
            val labelStyle = MaterialTheme.typography.labelLarge
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Side by side only if each label fits on one line in half the width; otherwise
                // stacked at full width. Squeezed side by side, large text would wrap inside the
                // buttons word by word.
                val half = (maxWidth - 8.dp) / 2
                val density = LocalDensity.current
                fun fits(label: String, chrome: Dp) = with(density) { measurer.measure(label, labelStyle).size.width.toDp() } + chrome <= half
                val sideBySide = toChange.isEmpty() || waiting.isEmpty() || (fits(starLabel, BUTTON_CHROME + 26.dp) && fits(markLabel, BUTTON_CHROME))
                val starButton: @Composable (Modifier) -> Unit = { modifier ->
                    val articles = plural(toChange.size, "article")
                    OutlinedButton(
                        onClick = { onStar(toChange, !takeOut) },
                        enabled = !(takeOut && building),
                        modifier = modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = if (takeOut) "Take $articles out of your next edition" else "Put $articles in your next edition"
                        },
                    ) {
                        Icon(if (takeOut) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(starLabel, textAlign = TextAlign.Center)
                    }
                }
                val markButton: @Composable (Modifier) -> Unit = { modifier ->
                    Button(
                        onClick = { onMarkRead(waiting) },
                        enabled = !building,
                        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mark ${plural(waiting.size, "article")} as read" },
                    ) { Text(markLabel, textAlign = TextAlign.Center) }
                }
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (toChange.isNotEmpty()) starButton(Modifier.weight(1f))
                        if (waiting.isNotEmpty()) markButton(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        starButton(Modifier.fillMaxWidth())
                        markButton(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** A button's own horizontal padding around its label (Material's 24dp a side). */
private val BUTTON_CHROME = 48.dp

internal fun undoMessage(change: SourceDetailViewModel.Change): String = when (change) {
    is SourceDetailViewModel.Change.Read -> if (change.marked.size == 1) "Marked as read" else "Marked ${change.marked.size} as read"
    is SourceDetailViewModel.Change.Stars -> {
        val count = change.batch.changed.size
        if (change.batch.starred) "$count in your next edition" else "$count taken out of your next edition"
    }
}

internal val IdSetSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })

/**
 * Tapping the row opens the original and changes nothing; the star beside it puts the article in
 * the next edition. Marking read is in selection mode, or the row's accessibility action. A
 * marked-read row stays where it is, so the list doesn't reflow (a full refresh on e-ink) and the
 * reader keeps her place.
 *
 * @param selection whether the row is selected, or null outside selection mode. In it, a tap
 *   selects instead of opening, and a checkbox takes the status mark's place.
 */
@Composable
internal fun RecentArticle(
    article: ArticleEntity,
    status: String,
    locale: Locale,
    building: Boolean,
    selection: Boolean?,
    onStar: (Boolean) -> Unit,
    onToggleRead: () -> Unit,
    onSelect: () -> Unit,
    onStartSelecting: () -> Unit,
) {
    val context = LocalContext.current
    val details = buildAnnotatedString {
        // When it was published: a tt-rss backlog arrives all at once, and every row would show
        // the day it was fetched.
        append(listOfNotNull(article.originTitle, shortDate(article.shownDate, locale)).joinToString(" · "))
        append(" · ")
        if (isStarred(article)) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(status) } else append(status)
    }
    val title = article.title.ifBlank { SourceRepository.hostOf(article.url) }
    val action = if (selection != null) {
        Modifier
            // Extra to the checkbox's shape, which carries the state on e-ink.
            .background(if (selection) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClickLabel = if (selection) "deselect" else "select", role = Role.Checkbox, onClick = onSelect)
            .semantics {
                toggleableState = ToggleableState(selection)
                stateDescription = if (selection) "Selected" else "Not selected"
            }
    } else {
        Modifier.combinedClickable(
            onClickLabel = "open in browser",
            onLongClickLabel = "select",
            onLongClick = onStartSelecting,
            onClick = {
                openInBrowser(context, article.url)
            },
        )
    }
    ArticleRowFrame(
        modifier = action,
        leading = {
            if (selection != null) {
                Icon(
                    if (selection) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(rowIconSize()),
                    tint = if (selection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ReadToggle(title, article, onToggleRead)
            }
        },
        title = {
            // Lighter weight as well as colour: on e-ink a muted colour alone can be hard to tell apart.
            val done = looksRead(article)
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (done) FontWeight.Normal else FontWeight.Medium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
        },
        details = { Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        // Nothing to change on an article already in an unsent edition: it's going out. Its slot
        // stays empty so the titles line up.
        trailing = if (article.state == ArticleState.IN_EDITION) null else {
            { StarToggle(title, article.starredAt != null, onStar, enabled = !(building && article.starredAt != null)) }
        },
        reserveTrailing = true,
    )
}

/** A star counts unless the article is already in an unsent edition, where it can't change. */
private fun isStarred(article: ArticleEntity) = article.starredAt != null && article.state != ArticleState.IN_EDITION

/** Dimmed in the list: read, delivered or too old, so what's still to come stands out. Not if starred: it's going out again. */
internal fun looksRead(article: ArticleEntity): Boolean =
    article.state in setOf(ArticleState.SKIPPED, ArticleState.DELIVERED, ArticleState.EXPIRED) && article.starredAt == null

/**
 * The read toggle: the article's status mark, which marks a waiting article read and brings back
 * a read or delivered one. No outline: it would box in every row. Narrower than a touch target,
 * so as not to push the titles right; Compose widens its touch area to 48dp. Not tappable on an
 * article in an unsent edition. While a build may be writing a waiting one into its book it still
 * takes the tap, which then says to wait, rather than letting it open the article underneath.
 */
@Composable
private fun ReadToggle(title: String, article: ArticleEntity, onToggle: () -> Unit) {
    val waiting = article.state == ArticleState.NEW
    val tappable = article.state != ArticleState.IN_EDITION
    // Its own node, not merged into the row's, so TalkBack can reach it apart from opening the article.
    val toggle = if (tappable) {
        Modifier
            .clickable(interactionSource = null, indication = ripple(bounded = false, radius = 24.dp), role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (waiting) "Mark $title as read" else "Mark $title as unread" }
    } else {
        Modifier
    }
    Box(toggle, contentAlignment = Alignment.Center) {
        // A shape per state, not a colour, so it reads on e-ink; the words are in the line below.
        Text(statusMark(article), style = MaterialTheme.typography.bodyLarge, softWrap = false, modifier = Modifier.clearAndSetSemantics {})
    }
}

private fun statusMark(article: ArticleEntity) = when (article.state) {
    ArticleState.NEW, ArticleState.IN_EDITION -> "●"
    ArticleState.DELIVERED -> "✓"
    ArticleState.SKIPPED, ArticleState.EXPIRED -> "○"
}
