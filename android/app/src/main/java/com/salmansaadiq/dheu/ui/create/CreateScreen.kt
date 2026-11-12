package com.salmansaadiq.dheu.ui.create

import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import com.salmansaadiq.dheu.ui.components.MentionSuggestions
import com.salmansaadiq.dheu.ui.components.fieldColors
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.data.AUDIENCE_CLOSE_FRIENDS
import com.salmansaadiq.dheu.data.AUDIENCE_EVERYONE
import com.salmansaadiq.dheu.data.Posts
import com.salmansaadiq.dheu.data.Stories
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.DheuTextField
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.PostRoute
import com.salmansaadiq.dheu.ui.nav.StoryRoute
import com.salmansaadiq.dheu.ui.theme.CloseFriendsGreen
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.launch

private const val MAX_PHOTOS = 10

@Composable
fun CreateScreen(startWithStory: Boolean) {
    var story by rememberSaveable { mutableStateOf(startWithStory) }
    val nav = LocalNav.current
    val c = Dheu.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        // Opened from the + on Home (or Your story), so it has a way back.
        DheuTopBar("Create", onBack = nav::back)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
            listOf("Post" to false, "Story" to true).forEachIndexed { i, (label, value) ->
                SegmentedButton(
                    selected = story == value,
                    onClick = { story = value },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = c.brandSoft, activeContentColor = c.ink, inactiveContainerColor = c.surface),
                ) { Text(label) }
            }
        }
        if (story) NewStory() else NewPost()
    }
}

@Composable
private fun NewPost() {
    val context = LocalContext.current
    val me = LocalAppState.current.userId
    val nav = LocalNav.current
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var caption by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) photos = (photos + uris).distinct().take(MAX_PHOTOS)
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (photos.isEmpty()) {
            PickBox("Choose up to 10 photos", ratio = 1f, onClick = ::pick)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(photos, key = { _, uri -> uri.toString() }) { i, uri ->
                    Box(Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                        AsyncImage(uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        SmallRound(Lucide.X, "Remove", Modifier.align(Alignment.TopEnd)) { photos = photos - uri }
                        if (i > 0) SmallRound(Lucide.ChevronLeft, "Move left", Modifier.align(Alignment.BottomStart)) {
                            photos = photos.toMutableList().also { list -> list.add(i - 1, list.removeAt(i)) }
                        }
                        if (i < photos.lastIndex) SmallRound(Lucide.ChevronRight, "Move right", Modifier.align(Alignment.BottomEnd)) {
                            photos = photos.toMutableList().also { list -> list.add(i + 1, list.removeAt(i)) }
                        }
                    }
                }
                if (photos.size < MAX_PHOTOS) {
                    item {
                        Box(
                            Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)).clickable(onClick = ::pick),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.ImagePlus, "Add photos", tint = c.inkMuted) }
                    }
                }
            }
            Text("${photos.size} of $MAX_PHOTOS · the first photo sets the shape", color = c.inkFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = caption,
            onValueChange = { if (it.text.length <= 2200) caption = it },
            label = { Text("Write a caption… @mention people") },
            minLines = 3,
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        MentionSuggestions(caption, onPick = { caption = it })
        error?.let { Text(it, color = c.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            progress ?: "Share",
            onClick = {
                error = null
                progress = "Preparing…"
                scope.launch {
                    try {
                        val id = Posts.create(context, me, photos, caption.text) { progress = it }
                        photos = emptyList()
                        caption = TextFieldValue("")
                        // The new post takes Create's place, so Back goes to where Create was opened from.
                        nav.replace(PostRoute(id))
                    } catch (e: Exception) {
                        error = e.friendly("Couldn't share the post.")
                    } finally {
                        progress = null
                    }
                }
            },
            enabled = photos.isNotEmpty(),
            loading = progress != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Posts disappear after 3 months.",
            color = c.inkFaint,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun NewStory() {
    val context = LocalContext.current
    val me = LocalAppState.current.userId
    val nav = LocalNav.current
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Uri?>(null) }
    var posting by remember { mutableStateOf<String?>(null) } // the audience it's going to
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // Everyone who can see my stories, or only my close friends (0024).
    fun share(audience: String) {
        val uri = photo ?: return
        posting = audience
        error = null
        scope.launch {
            try {
                Stories.create(context, me, uri, audience)
                photo = null
                nav.replace(StoryRoute(me))
            } catch (e: Exception) {
                error = e.friendly("Couldn't share your story.")
            } finally {
                posting = null
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val chosen = photo
        if (chosen == null) {
            PickBox("Choose a photo for your story", ratio = 9f / 16f, onClick = ::pick)
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).heightIn(max = 560.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                AsyncImage(chosen, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                SmallRound(Lucide.X, "Remove", Modifier.align(Alignment.TopEnd)) { photo = null }
            }
        }
        error?.let { Text(it, color = c.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                if (posting == AUDIENCE_EVERYONE) "Sharing…" else "Your story",
                onClick = { share(AUDIENCE_EVERYONE) },
                enabled = photo != null && posting == null,
                loading = posting == AUDIENCE_EVERYONE,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                if (posting == AUDIENCE_CLOSE_FRIENDS) "Sharing…" else "Close friends",
                onClick = { share(AUDIENCE_CLOSE_FRIENDS) },
                enabled = photo != null && posting == null,
                loading = posting == AUDIENCE_CLOSE_FRIENDS,
                icon = Lucide.StarFilled,
                color = CloseFriendsGreen,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Close friends stories only reach the people on your list (Settings → Close friends), with a green ring. " +
                "Stories disappear after 24 hours, unless you add them to a highlight.",
            color = c.inkFaint,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun PickBox(label: String, ratio: Float, onClick: () -> Unit) {
    val c = Dheu.colors
    Column(
        Modifier.fillMaxWidth().aspectRatio(ratio).heightIn(max = 520.dp).clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, c.line, RoundedCornerShape(14.dp)).background(c.surface2).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.ImagePlus, null, tint = c.inkMuted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, color = c.inkMuted)
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(6.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(16.dp)) }
}
