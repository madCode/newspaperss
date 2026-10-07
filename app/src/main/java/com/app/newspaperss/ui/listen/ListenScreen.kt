package com.app.newspaperss.ui.listen

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.border
import com.app.newspaperss.settings.PodcastVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.listen.LISTEN_SPEEDS
import com.app.newspaperss.listen.ListenPlayer
import com.app.newspaperss.listen.ListenPosition
import com.app.newspaperss.listen.ListenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt


/**
 * What's playing: the page being read, its sentence tinted and the page following the voice, with
 * the player under it. Take the phone out of a pocket and this is where it is: the text to read
 * along, the picture being described, the sentence that didn't land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(player: ListenPlayer, onBack: () -> Unit, podcastVoice: StateFlow<PodcastVoice?> = MutableStateFlow(null)) {
    val state by player.state.collectAsStateWithLifecycle()
    val voice by podcastVoice.collectAsStateWithLifecycle()
    var contents by remember { mutableStateOf(false) }
    val title = when {
        state.pages.isEmpty() -> "Listening"
        state.pages.getOrNull(state.at.page)?.end == true -> "Listening · the end"
        else -> "Listening · ${state.at.page + 1} of ${state.pages.size - 1}"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (state.pages.isNotEmpty()) {
                        IconButton(onClick = { contents = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents") }
                    }
                },
            )
        },
        bottomBar = { if (state.pages.isNotEmpty()) PlayerDock(state, player, voice) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val script = state.script
            when {
                state.error != null && script == null -> Message(state.error!!)
                state.editionId == null -> Message("Nothing is playing. Open an edition and tap Listen.")
                script == null -> Message("Opening…")
                else -> Page(state, script, player)
            }
        }
    }
    if (contents) Contents(state, onPick = { contents = false; player.seek(ListenPosition(it, 0)) }, onDismiss = { contents = false })
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Page(state: ListenState, script: ListenScript, player: ListenPlayer) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Following the voice until the reader scrolls away; the chip brings them back.
    var following by remember(state.editionId, state.at.page) { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    val layouts = remember(script) { mutableStateMapOf<Int, TextLayoutResult>() }
    // Notes above the article take the first rows.
    val notes = listOfNotNull(state.missingLanguage, state.error).size
    val line = script.lines.getOrNull(state.at.line)
    // Jumps, not animated scrolls: animation smears on e-ink.
    LaunchedEffect(line, following, script) {
        if (!following || line == null) return@LaunchedEffect
        list.scrollToItem(notes + line.block)
        // A long paragraph: down to the sentence, a little below the top.
        val layout = layouts[line.block] ?: return@LaunchedEffect
        val ranges = sentenceRanges(script.blocks[line.block])
        val top = layout.getLineTop(layout.getLineForOffset(ranges[line.index].first))
        val viewport = list.layoutInfo.viewportSize.height
        if (top > viewport / 3) list.scrollBy(top - viewport / 4f)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            state.missingLanguage?.let { tag -> item { MissingVoice(tag) } }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) } }
            itemsIndexed(script.blocks) { index, block ->
                val current = line?.block == index
                when (block) {
                    is Block.Text -> TextBlock(
                        block, if (current) line!!.index else null,
                        onLayout = { layouts[index] = it },
                        onTap = { sentence -> player.seek(ListenPosition(state.at.page, script.lines.indexOfFirst { it.block == index && it.index == sentence })) },
                    )
                    is Block.Image -> ImageBlock(block, current, player) { player.seek(ListenPosition(state.at.page, script.lines.indexOfFirst { it.block == index })) }
                }
            }
            if (state.finished) {
                item { Text("You've heard the whole edition.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)) }
            }
        }
        if (!following) {
            TextButton(
                onClick = { following = true; scope.launch { list.scrollToItem(notes + (line?.block ?: 0)) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
            ) { Text("Back to where it's reading", color = MaterialTheme.colorScheme.inverseOnSurface) }
        }
    }
}

/** Each sentence's characters in its block's text, which is the sentences joined by spaces. */
internal fun sentenceRanges(block: Block): List<IntRange> {
    if (block !is Block.Text) return emptyList()
    var at = 0
    return block.sentences.map { sentence -> (at until at + sentence.length).also { at += sentence.length + 1 } }
}

@Composable
private fun TextBlock(block: Block.Text, current: Int?, onLayout: (TextLayoutResult) -> Unit, onTap: (Int) -> Unit) {
    val typography = MaterialTheme.typography
    val style = when (block.kind) {
        Kind.KICKER -> typography.labelLarge
        Kind.TITLE -> typography.headlineSmall
        Kind.BYLINE -> typography.bodySmall
        Kind.HEADING -> typography.titleMedium
        Kind.QUOTE -> typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
        Kind.PARAGRAPH, Kind.ITEM -> typography.bodyLarge
    }
    val tint = MaterialTheme.colorScheme.secondaryContainer
    val ranges = remember(block) { sentenceRanges(block) }
    // Rebuilt only when this block's sentence changes, not for every sentence read elsewhere.
    val text: AnnotatedString = remember(block, current, tint) {
        buildAnnotatedString {
            block.sentences.forEachIndexed { i, sentence ->
                if (i > 0) append(' ')
                // Underlined as well as tinted: on e-ink the tint is too faint to follow.
                if (i == current) withStyle(SpanStyle(background = tint, textDecoration = TextDecoration.Underline)) { append(sentence) } else append(sentence)
            }
        }
    }
    // The gesture below lives as long as the block; the next article's equal block needs this one's tap.
    val tap by rememberUpdatedState(onTap)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val outer = Modifier.fillMaxWidth()
        .padding(
            start = if (block.kind == Kind.QUOTE || block.kind == Kind.ITEM) 16.dp else 0.dp,
            bottom = if (block.kind == Kind.KICKER || block.kind == Kind.TITLE) 4.dp else 12.dp,
        )
    // On the text itself, so a tap's offset is the text's own, not shifted by a list item's bullet.
    val modifier = Modifier
        .pointerInput(block) {
            detectTapGestures { offset ->
                val at = layout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                val sentence = ranges.indexOfFirst { at <= it.last + 1 }
                if (sentence >= 0) tap(sentence)
            }
        }
        // For TalkBack, the paragraph is one stop that reads from its start.
        .semantics {
            onClick(label = "Read from here") { tap(0); true }
            if (block.kind == Kind.TITLE || block.kind == Kind.HEADING) heading()
        }
    val prefix = if (block.kind == Kind.ITEM) "• " else ""
    Row(outer) {
        if (prefix.isNotEmpty()) Text(prefix, style = style, modifier = Modifier.clearAndSetSemantics {})
        Text(text, modifier.weight(1f), style = style, onTextLayout = { layout = it; onLayout(it) })
    }
}

@Composable
private fun ImageBlock(block: Block.Image, current: Boolean, player: ListenPlayer, onTap: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, block.src) {
        value = withContext(Dispatchers.IO) {
            player.image(block.src)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    val outline = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClickLabel = "Read from here", onClick = onTap),
    ) {
        bitmap?.let {
            val frame = if (current) Modifier.background(outline).padding(3.dp) else Modifier
            // The caption under it says what it is.
            Image(it, contentDescription = null, modifier = frame.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
        }
        val caption = block.description ?: "An image without a description"
        val captionStyle = MaterialTheme.typography.bodySmall
        Text(
            caption,
            style = captionStyle,
            modifier = Modifier.padding(top = 4.dp).then(if (current) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
            textDecoration = if (current) TextDecoration.Underline else null,
        )
    }
}

/** The page's language has no voice on this phone: Android can download one. */
@Composable
private fun MissingVoice(tag: String) {
    val context = LocalContext.current
    val language = Locale.forLanguageTag(tag).getDisplayLanguage(LocalConfiguration.current.locales[0]).ifEmpty { tag }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("This article is in $language, and your phone has no $language voice, so it's read in your phone's own.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Get a $language voice") }
        }
    }
}

@Composable
private fun PlayerDock(state: ListenState, player: ListenPlayer, voice: PodcastVoice?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            val page = state.pages.getOrNull(state.at.page)
            val inPage = state.secondsInPage
            val pageLeft = ((page?.minutes ?: 0.0) * 60 - inPage).coerceAtLeast(0.0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(page?.source ?: state.editionTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // The only sign this article plays from the podcast: its voice's name.
                // Only while it plays: paused, the next article's voice isn't chosen until it starts.
                if (voice != null && state.playing) {
                    Text(
                        voice.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .semantics { contentDescription = "Podcast, in ${voice.label}'s voice" },
                    )
                }
                if (page?.end != true) Text("${minutes(pageLeft)} left", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            }
            Chapters(state, Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = player::previous) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous article") }
                IconButton(onClick = player::back) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Back a sentence") }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(56.dp)) {
                    when {
                        state.playing -> Icon(Icons.Default.Pause, contentDescription = "Pause")
                        state.finished -> Icon(Icons.Default.PlayArrow, contentDescription = "Play from the start")
                        else -> Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }
                IconButton(onClick = player::forward) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Next sentence") }
                IconButton(onClick = player::next) { Icon(Icons.Default.SkipNext, contentDescription = "Next article") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val speed = speedLabel(state.speed)
                TextButton(
                    onClick = { player.setSpeed(LISTEN_SPEEDS[(LISTEN_SPEEDS.indexOf(state.speed) + 1).mod(LISTEN_SPEEDS.size)]) },
                    modifier = Modifier.semantics { contentDescription = "Speed $speed. Change speed" },
                ) { Text(speed) }
                val total = state.secondsTotal
                Text(
                    "${minutes(state.secondsIn)} in · ${minutes((total - state.secondsIn).coerceAtLeast(0.0))} left",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** The edition as one bar, a piece per article sized by its length: its shape, and where you are in it. */
@Composable
private fun Chapters(state: ListenState, modifier: Modifier) {
    val done = MaterialTheme.colorScheme.primary
    val todo = MaterialTheme.colorScheme.outlineVariant
    val pages = state.pages.filter { !it.end }
    Row(modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = "Article ${state.at.page + 1} of ${pages.size}" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        pages.forEachIndexed { i, page ->
            val weight = page.minutes.toFloat().coerceAtLeast(0.2f)
            Box(Modifier.weight(weight).fillMaxSize().background(if (i < state.at.page || state.finished) done else todo)) {
                if (i == state.at.page && !state.finished) {
                    val inPage = state.secondsInPage
                    val fraction = (inPage / (page.minutes * 60)).toFloat().coerceIn(0f, 1f)
                    if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxSize().background(done))
                }
            }
        }
    }
}

@Composable
private fun Contents(state: ListenState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.editionTitle) },
        text = {
            LazyColumn {
                itemsIndexed(state.pages) { i, page ->
                    val here = i == state.at.page
                    Column(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Listen", onClick = { onPick(i) }).padding(vertical = 10.dp)
                            .semantics(mergeDescendants = true) {},
                    ) {
                        Text(
                            (if (here) "▶ " else "") + page.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        val details = listOfNotNull(page.source, if (page.end) null else minutes(page.minutes * 60), "Playing".takeIf { here }).joinToString(" · ")
                        if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal fun speedLabel(speed: Float): String = (if (speed == speed.roundToInt().toFloat()) speed.roundToInt().toString() else speed.toString()) + "×"

private fun minutes(seconds: Double): String = ReadingTime.format(seconds / 60)
