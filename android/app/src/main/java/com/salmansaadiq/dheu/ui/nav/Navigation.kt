package com.salmansaadiq.dheu.ui.nav

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.salmansaadiq.dheu.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.CrashReports
import com.salmansaadiq.dheu.data.Push
import com.salmansaadiq.dheu.data.PushPrefs
import com.salmansaadiq.dheu.ui.AppState
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.activity.ActivityScreen
import com.salmansaadiq.dheu.ui.auth.AuthFlow
import com.salmansaadiq.dheu.ui.auth.ChoosePrivacyScreen
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.CountBadge
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.create.CreateScreen
import com.salmansaadiq.dheu.ui.feed.HomeScreen
import com.salmansaadiq.dheu.ui.friends.FriendsScreen
import com.salmansaadiq.dheu.ui.friends.MatchesScreen
import com.salmansaadiq.dheu.ui.friends.SwipeProfileScreen
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.messages.AddMembersScreen
import com.salmansaadiq.dheu.ui.messages.AnnouncementsScreen
import com.salmansaadiq.dheu.ui.messages.ChatScreen
import com.salmansaadiq.dheu.ui.messages.DirectMessageScreen
import com.salmansaadiq.dheu.ui.messages.GroupInfoScreen
import com.salmansaadiq.dheu.ui.messages.InboxScreen
import com.salmansaadiq.dheu.ui.messages.NewGroupScreen
import com.salmansaadiq.dheu.ui.messages.NewMessageScreen
import com.salmansaadiq.dheu.ui.post.LikesScreen
import com.salmansaadiq.dheu.ui.post.PostScreen
import com.salmansaadiq.dheu.ui.profile.FollowListScreen
import com.salmansaadiq.dheu.ui.profile.ProfileScreen
import com.salmansaadiq.dheu.ui.search.SearchScreen
import com.salmansaadiq.dheu.ui.settings.BlockedScreen
import com.salmansaadiq.dheu.ui.settings.CloseFriendsScreen
import com.salmansaadiq.dheu.ui.settings.EditProfileScreen
import com.salmansaadiq.dheu.ui.settings.SettingsScreen
import com.salmansaadiq.dheu.ui.stories.HighlightViewerScreen
import com.salmansaadiq.dheu.ui.stories.NewHighlightScreen
import com.salmansaadiq.dheu.ui.stories.StoryViewerScreen
import com.salmansaadiq.dheu.ui.theme.Dheu
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

// ---- routes -------------------------------------------------------------------

@Serializable object HomeRoute
@Serializable object SearchRoute
@Serializable data class CreateRoute(val story: Boolean = false)
@Serializable object MessagesRoute
@Serializable object MeRoute
@Serializable data class ProfileRoute(val username: String)
@Serializable data class FollowListRoute(val profileId: String, val username: String, val followers: Boolean)
@Serializable data class PostRoute(val id: String)
@Serializable data class LikesRoute(val postId: String)
@Serializable data class StoryRoute(val authorId: String)
@Serializable object ActivityRoute
@Serializable data class ChatRoute(val conversationId: String)
@Serializable data class DirectMessageRoute(val userId: String)
@Serializable object NewMessageRoute
@Serializable object AnnouncementsRoute
@Serializable object SettingsRoute
@Serializable object EditProfileRoute
@Serializable object BlockedRoute
@Serializable object CloseFriendsRoute
@Serializable object NewGroupRoute
@Serializable data class GroupInfoRoute(val conversationId: String)
@Serializable data class AddMembersRoute(val conversationId: String)
@Serializable data class HighlightRoute(val id: String)
@Serializable object NewHighlightRoute
@Serializable object FriendsRoute
@Serializable object SwipeProfileRoute
@Serializable object MatchesRoute

/** What screens use to move around, without holding the NavController themselves. */
class Nav(private val controller: NavHostController) {
    fun back() {
        controller.popBackStack()
    }

    fun to(route: Any) = controller.navigate(route)

    fun profile(username: String) = to(ProfileRoute(username))
    fun post(id: String) = to(PostRoute(id))
    fun story(authorId: String) = to(StoryRoute(authorId))
    fun chat(conversationId: String) = to(ChatRoute(conversationId))
    fun dm(userId: String) = to(DirectMessageRoute(userId))

    /** Swap the current screen for another (e.g. after posting, or once a DM is resolved). */
    fun replace(route: Any) {
        controller.popBackStack()
        controller.navigate(route)
    }

    /** Back to [route] if it's on the stack (Messages after leaving a group), else open it as a tab. */
    fun backTo(route: Any) {
        if (!controller.popBackStack(route, inclusive = false)) tab(route)
    }

    /** Bottom-bar tabs keep their own back stacks. */
    fun tab(route: Any) = controller.navigate(route) {
        popUpTo(controller.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

/** A screen to open from outside the app (a tapped notification), waiting until the app can show it. */
object DeepLinks {
    private val _pending = MutableStateFlow<String?>(null)
    val pending = _pending.asStateFlow()

    fun open(url: String) {
        _pending.value = url
    }

    fun consume(): String? = _pending.value.also { _pending.value = null }
}

/** The website path a push carries (see src/app/api/push/route.ts), as the app's screen. */
private fun routeFor(url: String): Any? {
    val parts = url.substringBefore('?').trim('/').split('/')
    val id = parts.getOrNull(1)
    return when {
        parts == listOf("messages", "announcements") -> AnnouncementsRoute
        parts == listOf("friends", "matches") -> MatchesRoute
        parts == listOf("friends") -> FriendsRoute
        parts.size == 2 && parts[0] == "messages" -> ChatRoute(id!!)
        parts.size == 2 && parts[0] == "p" -> PostRoute(id!!)
        parts.size == 2 && parts[0] == "u" -> ProfileRoute(id!!)
        parts.size == 2 && parts[0] == "stories" -> StoryRoute(id!!)
        parts.firstOrNull() == "activity" -> ActivityRoute
        else -> null
    }
}

/**
 * Once signed in: hand this phone's push token to the website, asking for the
 * notification permission the first time (Android 13+). Settings can switch it off.
 */
@Composable
private fun PushSetup() {
    if (!Push.configured) return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { runCatching { Push.register() } }
    }
    LaunchedEffect(Unit) {
        if (!PushPrefs.enabledNow(context)) return@LaunchedEffect
        when {
            Push.allowed(context) -> runCatching { Push.register() }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && PushPrefs.firstAsk(context) -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ---- motion -------------------------------------------------------------------
// Instagram-style: a screen slides in from the right over the one before,
// which drifts a little to the left, and back out the same way. The back
// gesture drags the page with the finger (predictive back) instead of
// shrinking it into a card. Stories fade, and switching tabs is instant.

// New posts start from the + at the top of Home (CreateRoute), which slides in like any screen.
private val TAB_ROUTES = listOf(HomeRoute::class, SearchRoute::class, FriendsRoute::class, MessagesRoute::class, MeRoute::class)

/** Screens that keep the bottom bar, like Instagram's profile and lists. */
private val BAR_ROUTES = TAB_ROUTES + listOf(ProfileRoute::class, FollowListRoute::class, LikesRoute::class, ActivityRoute::class, MatchesRoute::class)

/** Full-screen photo viewers: they fade over the screen below instead of sliding. */
private val MEDIA_ROUTES = listOf(StoryRoute::class, HighlightRoute::class)

private fun NavBackStackEntry.isIn(routes: List<KClass<*>>) = routes.any { destination.hasRoute(it) }

private const val SLIDE_MS = 280
private const val FADE_MS = 200
private fun <T> slide() = tween<T>(SLIDE_MS, easing = FastOutSlowInEasing)
private fun <T> fade() = tween<T>(FADE_MS, easing = FastOutSlowInEasing)

private typealias Move = AnimatedContentTransitionScope<NavBackStackEntry>

private fun Move.pushIn(): EnterTransition = when {
    targetState.isIn(TAB_ROUTES) -> EnterTransition.None
    targetState.isIn(MEDIA_ROUTES) -> fadeIn(fade()) + scaleIn(fade(), initialScale = 0.94f)
    else -> slideInHorizontally(slide()) { it }
}

private fun Move.pushOut(): ExitTransition = when {
    targetState.isIn(TAB_ROUTES) -> ExitTransition.None
    targetState.isIn(MEDIA_ROUTES) -> ExitTransition.KeepUntilTransitionsFinished
    else -> slideOutHorizontally(slide()) { -it / 4 }
}

private fun Move.popIn(): EnterTransition = when {
    initialState.isIn(MEDIA_ROUTES) -> EnterTransition.None
    else -> slideInHorizontally(slide()) { -it / 4 }
}

private fun Move.popOut(): ExitTransition = when {
    initialState.isIn(MEDIA_ROUTES) -> fadeOut(fade()) + scaleOut(fade(), targetScale = 0.94f)
    else -> slideOutHorizontally(slide()) { it }
}

// ---- root -------------------------------------------------------------------

/** Signed out → log in / sign up; signed in → privacy choice if needed, then the app. */
@Composable
fun DheuRoot() {
    if (!Backend.configured) {
        ErrorState("This build has no Supabase settings. See android/README.md (local.properties).", onRetry = null)
        return
    }
    val status by Backend.auth.sessionStatus.collectAsStateWithLifecycle()
    when (val s = status) {
        is SessionStatus.Initializing -> Loading()
        is SessionStatus.NotAuthenticated -> AuthFlow()
        is SessionStatus.Authenticated -> {
            val id = s.session.user?.id ?: Backend.userId
            if (id != null) SignedIn(id) else AuthFlow()
        }
        // Token refresh failed (often offline): keep the member signed in; requests retry.
        is SessionStatus.RefreshFailure -> Backend.userId?.let { SignedIn(it) } ?: AuthFlow()
    }
}

@Composable
private fun SignedIn(userId: String) {
    val app: AppState = viewModel(key = "app-$userId") { AppState(userId) }
    val me by app.me.collectAsStateWithLifecycle()
    val meError by app.meError.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAppState provides app) {
        val profile = me
        when {
            profile == null && meError != null -> ErrorState(meError!!, onRetry = app::refreshMe)
            profile == null -> Loading()
            profile.privacyChosenAt == null -> ChoosePrivacyScreen(onDone = app::refreshMe)
            else -> MainNav()
        }
    }
}

private data class Tab(val route: Any, val label: String, val icon: ImageVector?)

@Composable
private fun MainNav() {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val haptics = LocalHapticFeedback.current
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val badges by app.badges.collectAsStateWithLifecycle()
    val entry by controller.currentBackStackEntryAsState()
    val destination = entry?.destination

    val tabs = listOf(
        Tab(HomeRoute, "Home", Lucide.Home),
        Tab(SearchRoute, "Search", Lucide.Search),
        Tab(FriendsRoute, "Find friends", Lucide.Flame),
        Tab(MessagesRoute, "Messages", Lucide.MessageCircle),
        Tab(MeRoute, "Profile", null),
    )
    val showBar = BAR_ROUTES.any { destination?.hasRoute(it) == true }
    // The tab whose stack is showing: the current one, or the last one visited
    // (Home while on a profile opened from the feed).
    val currentTab = TAB_ROUTES.firstOrNull { destination?.hasRoute(it) == true }
    var lastTab by remember { mutableStateOf<KClass<*>>(HomeRoute::class) }
    SideEffect { if (currentTab != null) lastTab = currentTab }
    val activeTab = currentTab ?: lastTab
    val c = Dheu.colors
    // Each screen reserves room for the bar itself, so the bar appearing or
    // going never resizes a screen in the middle of a slide.
    val density = LocalDensity.current
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var barHeight by remember { mutableStateOf(navBarInset + 81.dp) }

    PushSetup()
    val context = LocalContext.current
    // Crash reports saved on earlier runs go to the admin, now that we're signed in (0026).
    LaunchedEffect(app.userId) { runCatching { CrashReports.sendWaiting(context.applicationContext, app.userId) } }
    // A tapped notification: open its screen on top of wherever the app is.
    val link by DeepLinks.pending.collectAsStateWithLifecycle()
    // (Waits for the first screen: on a cold start the NavHost isn't set up yet.)
    val ready = entry != null
    LaunchedEffect(link, ready) {
        if (link == null || !ready) return@LaunchedEffect
        val url = DeepLinks.consume() ?: return@LaunchedEffect
        // The admin's report pushes point at the website's admin pages (0025).
        if (url.trim('/').startsWith("admin")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, (BuildConfig.SITE_URL + url).toUri())) }
            return@LaunchedEffect
        }
        routeFor(url)?.let(nav::to)
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Scaffold(
            containerColor = c.canvas,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                AnimatedVisibility(showBar, enter = fadeIn(fade()), exit = fadeOut(fade())) {
                    Column(Modifier.onSizeChanged { if (it.height > 0) barHeight = with(density) { it.height.toDp() } }) {
                        HorizontalDivider(color = c.line)
                        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                            tabs.forEach { tab ->
                                val selected = activeTab == tab.route::class
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        nav.tab(tab.route)
                                    },
                                    icon = {
                                        Box {
                                            if (tab.icon != null) {
                                                Icon(tab.icon, tab.label, modifier = Modifier.size(26.dp))
                                            } else {
                                                me?.let { Avatar(it.avatarPath, it.fullName, 26.dp) }
                                            }
                                            if (tab.route == MessagesRoute && badges.messages > 0) {
                                                Box(Modifier.align(Alignment.TopEnd).padding(start = 16.dp)) { CountBadge(badges.messages) }
                                            }
                                        }
                                    },
                                    label = null,
                                    alwaysShowLabel = false,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.ink,
                                        unselectedIconColor = c.inkMuted,
                                        indicatorColor = c.surface2,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                controller,
                startDestination = HomeRoute,
                // Only the top: each page keeps room for the bottom bar itself (see Page).
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                enterTransition = { pushIn() },
                exitTransition = { pushOut() },
                popEnterTransition = { popIn() },
                popExitTransition = { popOut() },
                predictivePopEnterTransition = { popIn() },
                predictivePopExitTransition = { popOut() },
            ) {
                composable<HomeRoute> { Page(barHeight) { HomeScreen() } }
                composable<SearchRoute> { Page(barHeight) { SearchScreen() } }
                composable<CreateRoute> { Page { CreateScreen(startWithStory = it.toRoute<CreateRoute>().story) } }
                composable<FriendsRoute> { Page(barHeight) { FriendsScreen() } }
                composable<SwipeProfileRoute> { Page { SwipeProfileScreen() } }
                composable<MatchesRoute> { Page(barHeight) { MatchesScreen() } }
                composable<MessagesRoute> { Page(barHeight) { InboxScreen() } }
                composable<MeRoute> { Page(barHeight) { me?.let { profile -> ProfileScreen(profile.username, isTab = true) } } }
                composable<ProfileRoute> { Page(barHeight) { ProfileScreen(it.toRoute<ProfileRoute>().username) } }
                composable<FollowListRoute> {
                    val r = it.toRoute<FollowListRoute>()
                    Page(barHeight) { FollowListScreen(r.profileId, r.username, r.followers) }
                }
                composable<PostRoute> { Page { PostScreen(it.toRoute<PostRoute>().id) } }
                composable<LikesRoute> { Page(barHeight) { LikesScreen(it.toRoute<LikesRoute>().postId) } }
                composable<StoryRoute> { StoryViewerScreen(it.toRoute<StoryRoute>().authorId) }
                composable<ActivityRoute> { Page(barHeight) { ActivityScreen() } }
                composable<ChatRoute> { Page { ChatScreen(it.toRoute<ChatRoute>().conversationId) } }
                composable<DirectMessageRoute> { Page { DirectMessageScreen(it.toRoute<DirectMessageRoute>().userId) } }
                composable<NewMessageRoute> { Page { NewMessageScreen() } }
                composable<AnnouncementsRoute> { Page { AnnouncementsScreen() } }
                composable<SettingsRoute> { Page { SettingsScreen() } }
                composable<EditProfileRoute> { Page { EditProfileScreen() } }
                composable<BlockedRoute> { Page { BlockedScreen() } }
                composable<CloseFriendsRoute> { Page { CloseFriendsScreen() } }
                composable<NewGroupRoute> { Page { NewGroupScreen() } }
                composable<GroupInfoRoute> { Page { GroupInfoScreen(it.toRoute<GroupInfoRoute>().conversationId) } }
                composable<AddMembersRoute> { Page { AddMembersScreen(it.toRoute<AddMembersRoute>().conversationId) } }
                composable<HighlightRoute> { HighlightViewerScreen(it.toRoute<HighlightRoute>().id) }
                composable<NewHighlightRoute> { Page { NewHighlightScreen() } }
            }
        }
    }
}

/** An opaque screen (so a sliding page never shows the one beneath), with room for the bottom bar when it has one. */
@Composable
private fun Page(barHeight: Dp = 0.dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Dheu.colors.canvas).padding(bottom = barHeight)) { content() }
}
