package com.salmansaadiq.dheu.ui.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.core.parseInstant
import com.salmansaadiq.dheu.core.timeAgo
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.StorageImage
import com.salmansaadiq.dheu.data.storageImage
import com.salmansaadiq.dheu.data.HIGHLIGHT_TITLE_MAX
import com.salmansaadiq.dheu.data.HighlightSummary
import com.salmansaadiq.dheu.data.StoryRow
import com.salmansaadiq.dheu.data.Stories
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.DheuTextField
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.HighlightRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.NewHighlightRoute
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/** The round highlight covers under a profile's bio, with "New" first on my own. */
@Composable
fun HighlightsRow(highlights: List<HighlightSummary>, isOwner: Boolean) {
    if (highlights.isEmpty() && !isOwner) return
    val nav = LocalNav.current
    val c = Dheu.colors
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 14.dp)) {
        if (isOwner) {
            item(key = "new") {
                Bubble("New", onClick = { nav.to(NewHighlightRoute) }) {
                    Box(Modifier.fillMaxSize().border(2.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Lucide.Plus, "New highlight", tint = c.inkMuted, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        items(highlights, key = { it.id }) { h ->
            Bubble(h.title, onClick = { nav.to(HighlightRoute(h.id)) }) {
                Box(Modifier.fillMaxSize().border(2.dp, c.line, CircleShape).padding(4.dp).clip(CircleShape).background(c.surface2)) {
                    AsyncImage(storageImage(Buckets.STORIES, h.coverPath), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Bubble(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.width(68.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp)) { content() }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private class NewHighlightData(val stories: List<StoryRow>, val existing: Int)

/** Name a highlight and pick the stories for it; the first picked is the cover. */
@Composable
fun NewHighlightScreen() {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val scope = rememberCoroutineScope()
    val c = Dheu.colors
    val loader = rememberLoader("new-highlight") {
        coroutineScope {
            val stories = async { Stories.storiesForHighlights(me) }
            val existing = Stories.highlights(me).size
            NewHighlightData(stories.await(), existing)
        }
    }
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val now = remember { Instant.now() }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        DheuTopBar("New highlight", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                val data = s.value
                DheuTextField(title, { title = it }, "Name", maxLength = HIGHLIGHT_TITLE_MAX, supporting = "e.g. Trip, Friends, 2026", modifier = Modifier.padding(16.dp))
                Text(
                    "Choose stories (${selected.size} selected)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (data.stories.isEmpty()) {
                    Text(
                        "You have no stories to highlight. Post a story first: you can add it to a highlight from the story itself before it expires.",
                        color = c.inkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(data.stories, key = { it.id }) { story ->
                            val on = story.id in selected
                            val active = parseInstant(story.expiresAt).isAfter(now)
                            Box(
                                Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)).background(c.surface2).clickable {
                                    selected = if (on) selected - story.id else selected + story.id
                                    error = null
                                },
                            ) {
                                AsyncImage(
                                    StorageImage(Buckets.STORIES, story.imagePath),
                                    null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().alpha(if (on) 0.7f else 1f),
                                )
                                Text(
                                    if (active) timeAgo(story.createdAt) else "highlighted",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                                        .then(if (on) Modifier.background(c.brand) else Modifier.background(Color.Black.copy(alpha = 0.3f)).border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)),
                                    contentAlignment = Alignment.Center,
                                ) { if (on) Icon(Lucide.Check, "Selected", tint = c.brandInk, modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    PrimaryButton(
                        "Create highlight",
                        onClick = {
                            when {
                                title.isBlank() -> error = "Give the highlight a name."
                                selected.isEmpty() -> error = "Pick at least one story."
                                else -> {
                                    busy = true
                                    scope.launch {
                                        try {
                                            Stories.createHighlight(me, title, selected, position = data.existing)
                                            nav.back()
                                        } catch (e: Exception) {
                                            error = e.friendly("Couldn't create the highlight.")
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = data.stories.isNotEmpty(),
                        loading = busy,
                    )
                }
            }
        }
    }
}
