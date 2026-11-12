package com.salmansaadiq.dheu.ui.friends

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.core.timeAgo
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.Friends
import com.salmansaadiq.dheu.data.ReportTarget
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.data.storageImage
import com.salmansaadiq.dheu.data.swipeMessage
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuSheet
import com.salmansaadiq.dheu.ui.components.DheuTextField
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.EmptyState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.IconAction
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.components.ReportSheet
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.SheetAction
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.MatchesRoute
import com.salmansaadiq.dheu.ui.nav.SwipeProfileRoute
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

// Find friends, the flame tab (0027): the deck, the swipe profile editor and
// the matches list. Same behaviour as the website's /friends pages.

private const val FLY_MS = 240

enum class FriendsState { Loading, Error, NoProfile, Paused, Ready }

/** A match to show "It's a match" for: [image] is an image model, or null for initials. */
data class ShownMatch(val userId: String, val name: String, val image: Any?, val conversationId: String?)

class FriendsViewModel(private val me: String) : ViewModel() {
    var state by mutableStateOf(FriendsState.Loading)
        private set
    var profile by mutableStateOf<Friends.MyProfile?>(null)
        private set
    var cards by mutableStateOf<List<Friends.Card>>(emptyList())
        private set
    /** Nobody left, even after starting over. */
    var exhausted by mutableStateOf(false)
        private set
    var fetching by mutableStateOf(false)
        private set
    /** The deck just started over with the people passed on. */
    var looped by mutableStateOf(false)
        private set
    var lastSwiped by mutableStateOf<Friends.Card?>(null)
        private set
    var matches by mutableStateOf<List<ShownMatch>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var resuming by mutableStateOf(false)
        private set

    // Swipes, undos and top-ups go to the server one at a time, in order: a
    // top-up sent before a swipe arrived would hand that card back.
    private val queue = Mutex()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state = FriendsState.Loading
            error = null
            try {
                val mine = Friends.myProfile(me)
                profile = mine
                when {
                    mine == null -> state = FriendsState.NoProfile
                    !mine.active -> state = FriendsState.Paused
                    else -> {
                        val deck = Friends.deck()
                        val unseen = runCatching { Friends.matches() }.getOrDefault(emptyList()).filter { !it.seen }
                        cards = deck
                        exhausted = deck.isEmpty()
                        looped = deck.firstOrNull()?.looped == true
                        lastSwiped = null
                        matches = unseen.map { ShownMatch(it.userId, it.fullName, storageImage(Buckets.AVATARS, it.avatarPath), it.conversationId) }
                        if (unseen.isNotEmpty()) runCatching { Friends.markMatchesSeen() }
                        state = FriendsState.Ready
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly("Couldn't load Find friends.")
                state = FriendsState.Error
            }
        }
    }

    /** Back on the tab (after editing the profile, say): catch up if it was made, paused or deleted. */
    fun refresh() {
        if (state == FriendsState.Loading) return
        viewModelScope.launch {
            val mine = try {
                Friends.myProfile(me)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            val wasReady = state == FriendsState.Ready
            profile = mine
            when {
                mine == null -> state = FriendsState.NoProfile
                !mine.active -> state = FriendsState.Paused
                !wasReady -> load()
            }
        }
    }

    fun decide(like: Boolean) {
        val card = cards.firstOrNull() ?: return
        val rest = cards.drop(1)
        cards = rest
        lastSwiped = card
        looped = false
        error = null
        viewModelScope.launch {
            queue.withLock {
                try {
                    val result = Friends.swipe(card.userId, like)
                    if (result.matched) {
                        if (lastSwiped?.userId == card.userId) lastSwiped = null // a match can't be undone
                        matches = matches + ShownMatch(card.userId, card.fullName, storageImage(Buckets.SWIPE, card.photos.firstOrNull()), result.conversationId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val text = e.message.orEmpty()
                    when {
                        "NOT_AVAILABLE" in text -> Unit // paused or gone since; the card is gone anyway
                        "NO_PROFILE" in text -> load()
                        else -> {
                            error = e.swipeMessage("Couldn't save that swipe.")
                            if (lastSwiped?.userId == card.userId) lastSwiped = null
                            cards = listOf(card) + cards.filter { it.userId != card.userId }
                        }
                    }
                }
                if (rest.size <= 2) topUp(rest)
            }
        }
    }

    // Called with the queue held.
    private suspend fun topUp(holding: List<Friends.Card>) {
        fetching = true
        try {
            val held = holding.map { it.userId }.toSet()
            val fresh = Friends.deck(held.toList()).filter { it.userId !in held }
            if (fresh.isEmpty()) {
                if (holding.isEmpty()) exhausted = true
                return
            }
            exhausted = false
            if (holding.isEmpty() && fresh.first().looped) looped = true
            val have = cards.map { it.userId }.toSet()
            cards = cards + fresh.filter { it.userId !in have }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (holding.isEmpty()) exhausted = true
            error = e.friendly("Couldn't load more people.")
        } finally {
            fetching = false
        }
    }

    fun undo() {
        val card = lastSwiped ?: return
        lastSwiped = null
        error = null
        viewModelScope.launch {
            queue.withLock {
                try {
                    if (Friends.undo(card.userId)) {
                        exhausted = false
                        cards = listOf(card) + cards.filter { it.userId != card.userId }
                    } else {
                        error = "That swipe can't be undone any more."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.friendly("Couldn't undo that swipe.")
                }
            }
        }
    }

    fun retry() {
        error = null
        exhausted = false
        viewModelScope.launch { queue.withLock { topUp(emptyList()) } }
    }

    fun resume() {
        resuming = true
        error = null
        viewModelScope.launch {
            try {
                Friends.setActive(me, true)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.swipeMessage("Couldn't resume your profile.")
            } finally {
                resuming = false
            }
        }
    }

    fun dismissMatch() {
        matches = matches.drop(1)
    }
}

// ---- the tab -------------------------------------------------------------------

@Composable
fun FriendsScreen() {
    val app = LocalAppState.current
    val nav = LocalNav.current
    val me by app.me.collectAsStateWithLifecycle()
    val vm: FriendsViewModel = viewModel { FriendsViewModel(app.userId) }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        DheuTopBar("Find friends", actions = {
            if (vm.state == FriendsState.Ready || vm.state == FriendsState.Paused) {
                IconAction(Lucide.Users, "Your matches", { nav.to(MatchesRoute) })
                IconAction(Lucide.Pencil, "Edit your swipe profile", { nav.to(SwipeProfileRoute) })
            }
        })
        when (vm.state) {
            FriendsState.Loading -> Loading()
            FriendsState.Error -> ErrorState(vm.error ?: "Couldn't load Find friends.", onRetry = vm::load)
            FriendsState.NoProfile -> Intro(onStart = { nav.to(SwipeProfileRoute) })
            FriendsState.Paused -> Paused(vm.error, vm.resuming, onResume = vm::resume)
            FriendsState.Ready -> Deck(vm, onMatches = { nav.to(MatchesRoute) })
        }
    }

    vm.matches.firstOrNull()?.let { match ->
        MatchDialog(
            meImage = storageImage(Buckets.SWIPE, vm.profile?.photos?.firstOrNull()),
            meName = me?.fullName.orEmpty(),
            match = match,
            onChat = {
                vm.dismissMatch()
                if (match.conversationId != null) nav.chat(match.conversationId) else nav.dm(match.userId)
            },
            onDismiss = vm::dismissMatch,
        )
    }
}

@Composable
private fun Intro(onStart: () -> Unit) {
    val c = Dheu.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Lucide.Flame, null, tint = c.ink, modifier = Modifier.size(52.dp))
        Text(
            "Meet new people on Dheu",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Make a swipe profile, then go through other people's. Swipe right if you'd like to be friends, left to pass. " +
                "If you both swipe right, it's a match and a chat opens in Messages.",
            color = c.inkMuted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                "Only people with a swipe profile can see yours.",
                "Nobody knows you liked them unless they like you back.",
                "Pause or delete your profile any time.",
            ).forEach { line ->
                Row {
                    Icon(Lucide.Check, null, tint = c.brand, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        PrimaryButton("Make my swipe profile", onStart, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Paused(error: String?, resuming: Boolean, onResume: () -> Unit) {
    EmptyState(
        "Your profile is paused",
        "Nobody sees it and you can't swipe until you resume. Your matches and chats are still there.",
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PrimaryButton("Resume my profile", onResume, loading = resuming, icon = Lucide.Play)
            error?.let { Text(it, color = Dheu.colors.danger, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun Deck(vm: FriendsViewModel, onMatches: () -> Unit) {
    val c = Dheu.colors
    // A tap on Like or Pass: the top card flies off before it's decided.
    var request by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (vm.looped) Notice("You've seen everyone. Here are the people you passed on, in a new order.", c.brandSoft, c.brandStrong)
        vm.error?.let { Notice(it, c.dangerSoft, c.danger) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val card = vm.cards.firstOrNull()
            val next = vm.cards.getOrNull(1)
            if (card == null) {
                EmptyDeck(loading = vm.fetching || !vm.exhausted, onRetry = vm::retry, onMatches = onMatches)
            } else {
                if (next != null) {
                    CardFace(next, 0, Modifier.fillMaxSize().graphicsLayer { scaleX = 0.96f; scaleY = 0.96f; alpha = 0.7f })
                }
                key(card.userId) {
                    SwipeableCard(card, request, onDecide = { like ->
                        request = null
                        vm.decide(like)
                    })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Lucide.Undo, "Undo the last swipe", c.inkMuted, 46.dp, enabled = vm.lastSwiped != null && request == null, onClick = vm::undo)
            RoundButton(Lucide.X, "Pass", c.danger, 64.dp, enabled = vm.cards.isNotEmpty() && request == null) { request = false }
            RoundButton(Lucide.Heart, "Like", c.brand, 64.dp, enabled = vm.cards.isNotEmpty() && request == null) { request = true }
        }
    }
}

@Composable
private fun Notice(text: String, background: Color, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(background).padding(12.dp),
    )
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, tint: Color, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val c = Dheu.colors
    Box(
        Modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

/** The top card: drag it past the line to decide, tap its left or right for the other photos. */
@Composable
private fun SwipeableCard(card: Friends.Card, request: Boolean?, onDecide: (Boolean) -> Unit) {
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    val decide by rememberUpdatedState(onDecide)
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    var photo by remember { mutableIntStateOf(0) }
    var flying by remember { mutableStateOf(false) }
    val line = with(LocalDensity.current) { 110.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        fun fly(like: Boolean) {
            if (flying) return
            flying = true
            scope.launch {
                launch { dy.animateTo(dy.value + 60f, tween(FLY_MS)) }
                dx.animateTo(if (like) width * 1.5f else -width * 1.5f, tween(FLY_MS))
                decide(like)
            }
        }
        fun settle() {
            scope.launch {
                launch { dx.animateTo(0f, spring()) }
                dy.animateTo(0f, spring())
            }
        }
        LaunchedEffect(request) { if (request != null) fly(request) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = dx.value
                    translationY = dy.value * 0.25f
                    rotationZ = dx.value / 22f
                }
                .pointerInput(card.userId) {
                    detectDragGestures(
                        onDragEnd = { if (abs(dx.value) > line) fly(dx.value > 0) else settle() },
                        onDragCancel = { settle() },
                    ) { change, amount ->
                        if (flying) return@detectDragGestures
                        change.consume()
                        scope.launch {
                            dx.snapTo(dx.value + amount.x)
                            dy.snapTo(dy.value + amount.y)
                        }
                    }
                }
                .pointerInput(card.userId) {
                    detectTapGestures { position ->
                        val last = (card.photos.size - 1).coerceAtLeast(0)
                        photo = if (position.x < size.width / 3f) (photo - 1).coerceAtLeast(0) else (photo + 1).coerceAtMost(last)
                    }
                },
        ) {
            CardFace(card, photo, Modifier.fillMaxSize())
            val x = dx.value
            if (abs(x) > 24f) {
                val strength = ((abs(x) - 24f) / (line - 24f)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(if (x > 0) Alignment.TopStart else Alignment.TopEnd)
                        .padding(24.dp)
                        .size(56.dp)
                        .graphicsLayer { alpha = strength }
                        .clip(CircleShape)
                        .background(c.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (x > 0) Lucide.Heart else Lucide.X, null, tint = if (x > 0) c.brand else c.danger, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardFace(card: Friends.Card, photoIndex: Int, modifier: Modifier = Modifier) {
    val c = Dheu.colors
    val shape = RoundedCornerShape(20.dp)
    val index = photoIndex.coerceIn(0, (card.photos.size - 1).coerceAtLeast(0))
    Column(modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(c.surface2)) {
            AsyncImage(
                model = storageImage(Buckets.SWIPE, card.photos.getOrNull(index)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (card.photos.size > 1) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.photos.indices.forEach { i ->
                        Box(
                            Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (i == index) Color.White else Color.White.copy(alpha = 0.45f)),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp),
            ) {
                Text(
                    if (card.age != null) "${card.fullName}, ${card.age}" else card.fullName,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card.bio.isNotBlank() || card.interests.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.bio.isNotBlank()) {
                    Text(card.bio, color = c.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (card.interests.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        card.interests.forEach { InterestChip(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InterestChip(text: String, onRemove: (() -> Unit)? = null) {
    val c = Dheu.colors
    Row(
        Modifier.clip(CircleShape).background(c.brandSoft).padding(start = 10.dp, end = if (onRemove != null) 6.dp else 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = c.brandStrong, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.X, "Remove $text", tint = c.brandStrong, modifier = Modifier.size(16.dp).clickable(onClick = onRemove))
        }
    }
}

@Composable
private fun EmptyDeck(loading: Boolean, onRetry: () -> Unit, onMatches: () -> Unit) {
    val c = Dheu.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = c.inkFaint, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        } else {
            Icon(Lucide.Flame, null, tint = c.inkMuted, modifier = Modifier.size(40.dp))
            Text("No one new right now", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            Text(
                "You've swiped through everyone. When more people make a swipe profile, they'll show up here.",
                color = c.inkMuted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Check again", onRetry)
                PrimaryButton("Your matches", onMatches)
            }
        }
    }
}

@Composable
private fun MatchDialog(meImage: Any?, meName: String, match: ShownMatch, onChat: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("It's a match", color = Color.White, style = MaterialTheme.typography.displaySmall)
            Text(
                "You and ${match.name} both swiped right.",
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(vertical = 36.dp)) {
                Face(meImage, meName, Modifier.rotate(-6f))
                Face(match.image, match.name, Modifier.offset(x = (-18).dp).rotate(6f))
            }
            PrimaryButton("Say hi", onChat, Modifier.fillMaxWidth(), icon = Lucide.MessageCircle)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Keep swiping", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Face(image: Any?, name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(112.dp).clip(CircleShape).background(Dheu.colors.surface2).border(4.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(model = image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val initials = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
            Text(initials.ifEmpty { "?" }, color = Dheu.colors.ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- the swipe profile -------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwipeProfileScreen() {
    val app = LocalAppState.current
    val nav = LocalNav.current
    val me by app.me.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Dheu.colors

    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var existing by remember { mutableStateOf<Friends.MyProfile?>(null) }
    var photos by remember { mutableStateOf<List<Friends.Photo>>(emptyList()) }
    var bio by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf<List<String>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) } // "pause" or "delete"
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            val mine = Friends.myProfile(app.userId)
            existing = mine
            if (mine != null) {
                photos = mine.photos.map { Friends.Photo.Saved(it) }
                bio = mine.bio
                age = mine.age?.toString().orEmpty()
                interests = mine.interests
            }
            loadError = null
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.friendly("Couldn't load your swipe profile.")
        }
    }
    LaunchedEffect(Unit) { if (!loaded) load() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Friends.PHOTOS_MAX)) { uris ->
        val room = Friends.PHOTOS_MAX - photos.size
        if (uris.size > room) error = "You can have up to ${Friends.PHOTOS_MAX} photos."
        photos = photos + uris.take(room).map { Friends.Photo.Picked(it) }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // What's typed in the interest box, added: unchanged if empty or a repeat, null if one too many.
    fun withDraft(list: List<String>): List<String>? {
        val text = Friends.cleanInterest(draft)
        if (text.isEmpty() || list.any { it.equals(text, ignoreCase = true) }) return list
        if (list.size >= Friends.INTERESTS_MAX) return null
        return list + text
    }

    fun addInterest() {
        val next = withDraft(interests)
        if (next == null) {
            error = "You can have up to ${Friends.INTERESTS_MAX} interests."
            return
        }
        error = null
        interests = next
        draft = ""
    }

    fun save() {
        error = null
        if (photos.isEmpty()) {
            error = "Add at least one photo."
            return
        }
        val ageNumber = age.trim().toIntOrNull()
        if (age.isNotBlank() && (ageNumber == null || ageNumber !in Friends.AGE_MIN..Friends.AGE_MAX)) {
            error = "Your age has to be from ${Friends.AGE_MIN} to ${Friends.AGE_MAX}, or left empty."
            return
        }
        val list = withDraft(interests) ?: interests
        progress = "Saving…"
        scope.launch {
            try {
                Friends.save(context, app.userId, existing, photos, bio, ageNumber, list) { progress = it }
                nav.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.swipeMessage("Couldn't save your swipe profile.")
            } finally {
                progress = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        DheuTopBar(if (existing != null) "Your swipe profile" else "Make your swipe profile", onBack = nav::back)
        when {
            !loaded && loadError != null -> ErrorState(loadError!!, onRetry = { scope.launch { load() } })
            !loaded -> Loading()
            else -> Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Text("Photos", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Up to ${Friends.PHOTOS_MAX}. The first one is what people see first.",
                        color = c.inkMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    val tiles: List<Friends.Photo?> = photos + if (photos.size < Friends.PHOTOS_MAX) listOf(null) else emptyList()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { tile ->
                                    Box(Modifier.weight(1f).aspectRatio(3f / 4f)) {
                                        if (tile == null) {
                                            AddPhotoTile(onClick = ::pick)
                                        } else {
                                            PhotoTile(
                                                tile,
                                                main = photos.firstOrNull() == tile,
                                                onRemove = { photos = photos - tile },
                                                onMakeMain = { photos = listOf(tile) + (photos - tile) },
                                            )
                                        }
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                DheuTextField(
                    bio,
                    { bio = it },
                    "About you",
                    singleLine = false,
                    minLines = 3,
                    maxLength = Friends.BIO_MAX,
                    supporting = "${bio.length}/${Friends.BIO_MAX}",
                )
                DheuTextField(
                    age,
                    { if (it.all(Char::isDigit)) age = it },
                    "Age (optional)",
                    keyboardType = KeyboardType.Number,
                    maxLength = 2,
                    modifier = Modifier.width(160.dp),
                )

                Column {
                    Text("Interests (up to ${Friends.INTERESTS_MAX})", style = MaterialTheme.typography.titleSmall)
                    if (interests.isNotEmpty()) {
                        FlowRow(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            interests.forEach { t -> InterestChip(t, onRemove = { interests = interests - t }) }
                        }
                    }
                    if (interests.size < Friends.INTERESTS_MAX) {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DheuTextField(draft, { draft = it }, "Football, coffee…", modifier = Modifier.weight(1f), maxLength = 40)
                            Spacer(Modifier.width(8.dp))
                            SecondaryButton("Add", ::addInterest)
                        }
                    }
                }

                Text(
                    "Your card shows your name, ${me?.fullName.orEmpty()}. Your username and Dheu profile are only shown to people you match with.",
                    color = c.inkMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = c.danger) }
                PrimaryButton(
                    progress ?: if (existing != null) "Save" else "Make my swipe profile",
                    onClick = ::save,
                    enabled = busy == null,
                    loading = progress != null,
                    modifier = Modifier.fillMaxWidth(),
                )

                existing?.let { mine ->
                    HorizontalDivider(color = c.line)
                    SecondaryButton(
                        if (mine.active) "Pause my profile" else "Resume my profile",
                        onClick = {
                            busy = "pause"
                            error = null
                            scope.launch {
                                try {
                                    Friends.setActive(app.userId, !mine.active)
                                    existing = mine.copy(active = !mine.active)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.swipeMessage("Couldn't change that.")
                                } finally {
                                    busy = null
                                }
                            }
                        },
                        enabled = progress == null && busy == null,
                        loading = busy == "pause",
                        icon = if (mine.active) Lucide.Pause else Lucide.Play,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (mine.active) "While it's paused, nobody sees your profile and you can't swipe. Your matches stay."
                        else "Your profile is paused: nobody sees it and you can't swipe.",
                        color = c.inkMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SecondaryButton(
                        "Delete my swipe profile",
                        onClick = { confirmDelete = true },
                        enabled = progress == null && busy == null,
                        loading = busy == "delete",
                        icon = Lucide.Trash,
                        danger = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete your swipe profile?",
            "Its photos are deleted and nobody sees you in Find friends. Your matches and chats stay.",
            "Delete",
            onConfirm = {
                busy = "delete"
                error = null
                scope.launch {
                    try {
                        Friends.delete(app.userId)
                        nav.back()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.swipeMessage("Couldn't delete your swipe profile.")
                    } finally {
                        busy = null
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun AddPhotoTile(onClick: () -> Unit) {
    val c = Dheu.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).border(1.dp, c.line, shape).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.ImagePlus, null, tint = c.inkMuted)
        Text("Add", color = c.inkMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PhotoTile(photo: Friends.Photo, main: Boolean, onRemove: () -> Unit, onMakeMain: () -> Unit) {
    val model: Any? = when (photo) {
        is Friends.Photo.Saved -> storageImage(Buckets.SWIPE, photo.path)
        is Friends.Photo.Picked -> photo.uri
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Dheu.colors.surface2)) {
        AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.X, "Remove photo", tint = Color.White, modifier = Modifier.size(16.dp)) }
        Text(
            if (main) "Main" else "Make main",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .then(if (main) Modifier else Modifier.clickable(onClick = onMakeMain))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---- matches -------------------------------------------------------------------

@Composable
fun MatchesScreen() {
    val nav = LocalNav.current
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var matches by remember { mutableStateOf<List<Friends.Match>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Friends.Match?>(null) }
    var confirmUnmatch by remember { mutableStateOf<Friends.Match?>(null) }
    var reporting by remember { mutableStateOf<Friends.Match?>(null) }

    suspend fun load() {
        try {
            val list = Friends.matches()
            matches = list
            error = null
            // Seen here, so the deck won't show their "It's a match" again.
            if (list.any { !it.seen }) runCatching { Friends.markMatchesSeen() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.friendly("Couldn't load your matches.")
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        DheuTopBar("Your matches", onBack = nav::back)
        val list = matches
        when {
            list == null && error != null -> ErrorState(error!!, onRetry = { scope.launch { load() } })
            list == null -> Loading()
            list.isEmpty() -> EmptyState(
                "No matches yet",
                "When you and someone both swipe right, they show up here and a chat opens in Messages.",
            ) { PrimaryButton("Keep swiping", nav::back) }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.userId }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.profile(m.username) }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(m.avatarPath, m.fullName, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    m.fullName,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (!m.seen) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "New",
                                        color = c.brandStrong,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clip(CircleShape).background(c.brandSoft).padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                "@${m.username} · matched ${timeAgo(m.matchedAt)}",
                                color = c.inkMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton(
                            "Message",
                            onClick = { if (m.conversationId != null) nav.chat(m.conversationId) else nav.dm(m.userId) },
                            compact = true,
                        )
                        IconAction(Lucide.Ellipsis, "More for ${m.fullName}", { menuFor = m }, tint = c.inkMuted)
                    }
                    HorizontalDivider(color = c.line)
                }
            }
        }
    }

    menuFor?.let { m ->
        DheuSheet(m.fullName, onDismiss = { menuFor = null }) {
            SheetAction(Lucide.User, "View profile", {
                menuFor = null
                nav.profile(m.username)
            })
            SheetAction(Lucide.UserMinus, "Unmatch", {
                menuFor = null
                confirmUnmatch = m
            })
            SheetAction(Lucide.Flag, "Report", {
                menuFor = null
                reporting = m
            }, danger = true)
        }
    }
    confirmUnmatch?.let { m ->
        ConfirmDialog(
            "Unmatch ${m.fullName}?",
            "You won't see each other in Find friends again. Your chat stays in Messages.",
            "Unmatch",
            onConfirm = {
                scope.launch {
                    try {
                        Friends.unmatch(m.userId)
                        matches = matches?.filter { it.userId != m.userId }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't unmatch."))
                    }
                }
            },
            onDismiss = { confirmUnmatch = null },
        )
    }
    reporting?.let { m -> ReportSheet(ReportTarget.Profile, m.userId, onDismiss = { reporting = null }) }
}
