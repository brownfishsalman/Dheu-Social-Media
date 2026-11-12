package com.salmansaadiq.dheu.ui.feed

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.salmansaadiq.dheu.data.FollowState
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Post
import com.salmansaadiq.dheu.data.Posts
import com.salmansaadiq.dheu.data.Profile
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.Stories
import com.salmansaadiq.dheu.data.StoryGroup
import com.salmansaadiq.dheu.data.SuggestionPrefs
import com.salmansaadiq.dheu.data.Updates
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.CountBadge
import com.salmansaadiq.dheu.ui.components.FollowButton
import com.salmansaadiq.dheu.ui.components.EmptyState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.StoryRing
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.ActivityRoute
import com.salmansaadiq.dheu.ui.nav.CreateRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.SearchRoute
import com.salmansaadiq.dheu.ui.post.PostCard
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.random.Random

class HomeViewModel(private val me: String) : ViewModel() {
    var posts by mutableStateOf<List<Post>>(emptyList())
        private set
    var stories by mutableStateOf<List<StoryGroup>>(emptyList())
        private set
    var followsAnyone by mutableStateOf(false)
        private set
    /** "Suggested for you": on some feed loads only (about 2 in 5, like the website), and only with 2+ people. */
    var suggestions by mutableStateOf<List<Person>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var cursor: String? = null

    init {
        load()
    }

    /** The feed doesn't wait for the following list (home_feed reads it from the session). */
    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (silent) refreshing = true else loading = true
            try {
                val feed = async { Posts.feedPage() }
                val suggested = async { if (Random.nextFloat() < 0.4f) runCatching { Profiles.suggestions(me) }.getOrDefault(emptyList()) else emptyList() }
                val following = Profiles.followingIds(me)
                val storyFeed = Stories.feed(me, following)
                val page = feed.await()
                posts = page.items
                cursor = page.nextCursor
                stories = storyFeed
                followsAnyone = following.isNotEmpty()
                suggestions = suggested.await().takeIf { it.size >= 2 }.orEmpty()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (posts.isEmpty()) error = e.friendly("Couldn't load your feed.")
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    val hasMore get() = cursor != null

    fun loadMore() {
        val before = cursor ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = Posts.feedPage(before)
                val seen = posts.map { it.id }.toSet()
                posts = posts + page.items.filter { it.id !in seen }
                cursor = page.nextCursor
            } catch (_: Exception) {
                // The sentinel stays; scrolling retries.
            } finally {
                loadingMore = false
            }
        }
    }

    fun removed(id: String) {
        posts = posts.filter { it.id != id }
    }
}

@Composable
fun HomeScreen() {
    val app = LocalAppState.current
    val nav = LocalNav.current
    val me by app.me.collectAsStateWithLifecycle()
    val badges by app.badges.collectAsStateWithLifecycle()
    val vm: HomeViewModel = viewModel { HomeViewModel(app.userId) }
    val c = Dheu.colors
    val list = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val suggestionsHidden by remember { SuggestionPrefs.hidden(context.applicationContext) }.collectAsStateWithLifecycle(initialValue = true)
    val suggestions = if (suggestionsHidden) emptyList() else vm.suggestions

    // Load the next page when the last few posts come into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, vm.posts.size) { if (nearEnd && vm.hasMore) vm.loadMore() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            // New posts start here, where the wordmark used to be; the middle tab is Find friends.
            title = {},
            navigationIcon = {
                IconButton(onClick = { nav.to(CreateRoute()) }) {
                    Icon(Lucide.SquarePlus, "New post", tint = c.ink, modifier = Modifier.size(26.dp))
                }
            },
            actions = {
                IconButton(onClick = { nav.to(ActivityRoute) }) {
                    Box {
                        Icon(Lucide.Heart, "Activity", tint = c.ink, modifier = Modifier.size(26.dp))
                        if (badges.activity > 0) Box(Modifier.align(Alignment.TopEnd).padding(start = 14.dp)) {
                            CountBadge(badges.activity, color = c.like, textColor = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface),
        )
        HorizontalDivider(color = c.line)
        UpdateBanner()

        when {
            vm.loading && vm.posts.isEmpty() -> Loading()
            vm.error != null -> ErrorState(vm.error!!, onRetry = { vm.load() })
            else -> PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.load(silent = true); app.refreshBadges() }) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    item(key = "stories") { me?.let { StoryBar(vm.stories, it) } }
                    if (vm.posts.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                "Your feed is quiet",
                                if (!vm.followsAnyone) "Follow a few people: public accounts straight away, private ones once they accept. Or share the first post yourself."
                                else "The people you follow haven't posted lately. Be the first.",
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SecondaryButton("Find people", { nav.tab(SearchRoute) }, icon = Lucide.Search)
                                    PrimaryButton("Create a post", { nav.to(CreateRoute()) }, icon = Lucide.Camera)
                                }
                            }
                        }
                    }
                    itemsIndexed(vm.posts, key = { _, post -> post.id }) { i, post ->
                        PostCard(post, onDeleted = { vm.removed(post.id) })
                        Spacer(Modifier.height(8.dp).fillMaxWidth().background(c.canvas))
                        if (suggestions.isNotEmpty() && i == minOf(1, vm.posts.lastIndex)) {
                            SuggestedStrip(suggestions, onDismiss = { scope.launch { SuggestionPrefs.dismiss(context.applicationContext) } })
                            Spacer(Modifier.height(8.dp).fillMaxWidth().background(c.canvas))
                        }
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            when {
                                vm.loadingMore -> CircularProgressIndicator(color = c.inkFaint, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                !vm.hasMore && vm.posts.isNotEmpty() -> Text("You're all caught up", color = c.inkFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Dheu 0.4.0 is out": the newest release on GitHub (data/Updates.kt), until
 * it's installed or this banner is closed for that version. Update opens the
 * APK in the browser, which downloads it.
 */
@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Dheu.colors
    var available by remember { mutableStateOf<Updates.Available?>(null) }
    LaunchedEffect(Unit) { available = runCatching { Updates.check(context.applicationContext) }.getOrNull() }
    val update = available ?: return
    Row(
        Modifier.fillMaxWidth().background(c.brandSoft).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Download, null, tint = c.brandStrong, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Dheu ${update.version} is out", color = c.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, update.url.toUri())) } }) {
            Text("Update", color = c.brand, fontWeight = FontWeight.SemiBold)
        }
        IconButton(
            onClick = {
                available = null
                scope.launch { Updates.dismiss(context.applicationContext, update.version) }
            },
            modifier = Modifier.size(36.dp),
        ) { Icon(Lucide.X, "Not now", tint = c.inkMuted, modifier = Modifier.size(16.dp)) }
    }
    HorizontalDivider(color = c.line)
}

/** Story rings across the top of the feed: mine first (add or view), then everyone else's. */
@Composable
private fun StoryBar(groups: List<StoryGroup>, me: Profile) {
    val nav = LocalNav.current
    val c = Dheu.colors
    val mine = groups.firstOrNull { it.author.id == me.id }
    val others = groups.filter { it.author.id != me.id }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().background(c.surface),
    ) {
        item(key = "me") {
            StoryBubble(
                label = "Your story",
                seen = mine?.allSeen,
                closeFriends = mine?.closeFriends == true,
                onClick = { if (mine != null) nav.story(me.id) else nav.to(CreateRoute(story = true)) },
            ) {
                Box {
                    Avatar(me.avatarPath, me.fullName, 58.dp)
                    if (mine == null) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(c.surface).padding(2.dp)
                                .clip(CircleShape).background(c.brand),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.Plus, "Add a story", tint = c.brandInk, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
        }
        items(others, key = { it.author.id }) { g ->
            StoryBubble(label = g.author.username, seen = g.allSeen, closeFriends = g.closeFriends, onClick = { nav.story(g.author.id) }) {
                Avatar(g.author.avatarPath, g.author.fullName, 58.dp)
            }
        }
    }
    HorizontalDivider(color = c.line)
}

@Composable
private fun StoryBubble(label: String, seen: Boolean?, closeFriends: Boolean, onClick: () -> Unit, avatar: @Composable () -> Unit) {
    Column(
        Modifier.width(72.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StoryRing(seen, closeFriends = closeFriends) { avatar() }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Dheu.colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The admin's picks, between the first posts now and then; ✕ hides it for a day. */
@Composable
private fun SuggestedStrip(people: List<Person>, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val c = Dheu.colors
    Column(Modifier.fillMaxWidth().background(c.surface).padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Suggested for you", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("See all", color = c.brand, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { nav.tab(SearchRoute) }.padding(8.dp))
            IconButton(onClick = onDismiss) { Icon(Lucide.X, "Hide suggestions", tint = c.inkFaint, modifier = Modifier.size(18.dp)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(people, key = { it.id }) { p ->
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(p.avatarPath, p.fullName, 64.dp, Modifier.clickable { nav.profile(p.username) })
                    Spacer(Modifier.height(8.dp))
                    Text(p.username, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.fullName, color = c.inkMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    FollowButton(p.id, FollowState.None)
                }
            }
        }
    }
}
