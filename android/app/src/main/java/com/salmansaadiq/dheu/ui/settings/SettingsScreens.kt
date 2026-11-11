package com.salmansaadiq.dheu.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salmansaadiq.dheu.BuildConfig
import com.salmansaadiq.dheu.core.parseInstant
import com.salmansaadiq.dheu.data.Account
import com.salmansaadiq.dheu.data.InviteCode
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.Push
import com.salmansaadiq.dheu.data.PushPrefs
import com.salmansaadiq.dheu.data.ThemePrefs
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.auth.AccountTypePicker
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuSheet
import com.salmansaadiq.dheu.ui.components.DheuTextField
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.EmptyState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PersonRow
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.fieldColors
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.BlockedRoute
import com.salmansaadiq.dheu.ui.nav.CloseFriendsRoute
import com.salmansaadiq.dheu.ui.nav.EditProfileRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.CloseFriendsGreen
import com.salmansaadiq.dheu.ui.theme.Dheu
import com.salmansaadiq.dheu.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
private fun Card(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Dheu.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, color = c.inkMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val c = Dheu.colors
    val profile = me ?: return
    val themes = remember { ThemePrefs.flow(context.applicationContext) }
    val theme by themes.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    var privacy by remember(profile.isPrivate) { mutableStateOf(if (profile.isPrivate) "private" else "public") }
    var confirmPrivacy by remember { mutableStateOf(false) }
    var savingPrivacy by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        DheuTopBar("Settings", onBack = nav::back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card("Profile") {
                Row(Modifier.fillMaxWidth().clickable { nav.to(EditProfileRoute) }, verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile.avatarPath, profile.fullName, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.fullName, fontWeight = FontWeight.SemiBold)
                        Text("@${profile.username}", color = c.inkMuted)
                    }
                    Icon(Lucide.ChevronRight, null, tint = c.inkFaint)
                }
            }

            Card("Account privacy", "Who can see your posts, stories and followers.") {
                AccountTypePicker(privacy) { privacy = it }
                if (privacy != (if (profile.isPrivate) "private" else "public")) {
                    PrimaryButton("Save", { confirmPrivacy = true }, Modifier.padding(top = 12.dp), loading = savingPrivacy)
                }
            }

            Card("Read receipts", "Whether people see \"Seen\" when you've read their messages.") {
                ReadReceiptsSetting(profile.id, profile.readReceipts, onSaved = app::refreshMe)
            }

            CloseFriendsCard(profile.id)

            Card("Notifications", "Messages, mentions, comments, likes and follow requests on this phone.") {
                NotificationsSetting()
            }

            InvitesCard(app.userId)

            Card("Appearance", "Choose how Dheu looks on this device.") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = theme == mode,
                            onClick = { scope.launch { ThemePrefs.set(context.applicationContext, mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = c.brandSoft, activeContentColor = c.ink, inactiveContainerColor = c.surface),
                        ) { Text(mode.name) }
                    }
                }
            }

            PasswordCard()

            Card("Blocked accounts", "Blocked members can't see your photos or message you, and you won't see theirs.") {
                SecondaryButton("Manage blocked accounts", { nav.to(BlockedRoute) }, icon = Lucide.Ban)
            }

            if (profile.isAdmin) {
                Card("Admin", "Invite codes, members, moderation and announcements live on the website.") {
                    SecondaryButton("Open the admin panel", {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "${BuildConfig.SITE_URL}/admin".toUri()))
                    }, icon = Lucide.ShieldCheck)
                }
            }

            DeleteAccountCard(isAdmin = profile.isAdmin)

            SecondaryButton("Log out", { confirmLogout = true }, Modifier.fillMaxWidth(), icon = Lucide.LogOut, danger = true)
            Text(
                "Dheu ${BuildConfig.VERSION_NAME} · posts expire after 3 months · stories after 24 hours",
                color = c.inkFaint,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
            )
        }
    }

    if (confirmPrivacy) {
        ConfirmDialog(
            title = if (privacy == "public") "Make your account public?" else "Make your account private?",
            text = if (privacy == "public") "Anyone on Dheu will see your posts and stories, and pending follow requests will be approved."
            else "New followers will need your approval. People who already follow you will stay.",
            confirmLabel = "Save",
            danger = false,
            onConfirm = {
                savingPrivacy = true
                scope.launch {
                    try {
                        Profiles.setPrivacy(privacy == "private")
                        app.refreshMe()
                        toast("Saved")
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't save that."))
                    } finally {
                        savingPrivacy = false
                    }
                }
            },
            onDismiss = { confirmPrivacy = false },
        )
    }
    if (confirmLogout) {
        ConfirmDialog("Log out?", "You'll need your email and password to log back in.", "Log out", onConfirm = {
            scope.launch { runCatching { Account.signOut() } }
        }, onDismiss = { confirmLogout = false })
    }
}

@Composable
private fun InvitesCard(me: String) {
    val c = Dheu.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val loader = rememberLoader("invites") { Account.inviteCodes(me) }
    var creating by remember { mutableStateOf(false) }

    fun share(code: String) {
        val text = "Join me on Dheu: ${Account.inviteLink(code)}"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invite"))
    }

    Card("Share with friends", "Each code lets one friend create an account and is valid for 30 days. You can hold 5 unused codes at a time.") {
        when (val s = loader.state) {
            Load.Loading -> Text("Loading…", color = c.inkFaint)
            is Load.Failed -> Text(s.message, color = c.danger)
            is Load.Ready -> {
                val now = Instant.now()
                s.value.forEach { code -> InviteRow(code, now) { share(code.code) } }
                if (s.value.isEmpty()) Text("No codes yet.", color = c.inkFaint)
            }
        }
        PrimaryButton(
            "Create an invite code",
            {
                creating = true
                scope.launch {
                    try {
                        val code = Account.createInviteCode()
                        loader.reload(silent = true)
                        share(code)
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't create a code."))
                    } finally {
                        creating = false
                    }
                }
            },
            Modifier.padding(top = 12.dp),
            loading = creating,
            icon = Lucide.Ticket,
        )
    }
}

@Composable
private fun InviteRow(code: InviteCode, now: Instant, onShare: () -> Unit) {
    val c = Dheu.colors
    val used = code.useCount >= code.maxUses
    val expired = code.expiresAt?.let { parseInstant(it).isBefore(now) } == true
    val status = when {
        used -> "Used"
        code.disabled -> "Disabled"
        expired -> "Expired"
        else -> null
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(code.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), color = if (status != null) c.inkFaint else c.ink)
        if (status != null) Text(status, color = c.inkFaint, style = MaterialTheme.typography.bodySmall)
        else SecondaryButton("Share", onShare, compact = true, icon = Lucide.Share)
    }
}

@Composable
private fun PasswordCard() {
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Card("Password") {
        DheuTextField(password, { password = it }, "New password", password = true, supporting = "At least 8 characters")
        Spacer(Modifier.height(8.dp))
        DheuTextField(again, { again = it }, "Repeat new password", password = true)
        message?.let { (ok, text) -> Text(text, color = if (ok) c.brand else c.danger, modifier = Modifier.padding(top = 8.dp)) }
        PrimaryButton(
            "Change password",
            {
                message = when {
                    password.length < 8 -> false to "Use at least 8 characters."
                    password != again -> false to "The two passwords don't match."
                    else -> null
                }
                if (message != null) return@PrimaryButton
                busy = true
                scope.launch {
                    message = try {
                        Account.changePassword(password)
                        password = ""
                        again = ""
                        true to "Password changed."
                    } catch (e: Exception) {
                        false to e.friendly("Couldn't change your password.")
                    }
                    busy = false
                }
            },
            Modifier.padding(top = 12.dp),
            loading = busy,
        )
    }
}

private val USERNAME = Regex("^[a-z0-9_.]{3,30}$")

@Composable
fun EditProfileScreen() {
    val nav = LocalNav.current
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val profile = me ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val c = Dheu.colors
    var fullName by remember { mutableStateOf(profile.fullName) }
    var username by remember { mutableStateOf(profile.username) }
    var bio by remember { mutableStateOf(profile.bio) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            try {
                Profiles.replaceAvatar(context, profile.id, uri, profile.avatarPath)
                app.refreshMe()
            } catch (e: Exception) {
                error = e.friendly("Couldn't update your photo.")
            } finally {
                uploading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface).imePadding()) {
        DheuTopBar("Edit profile", onBack = nav::back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box { Avatar(profile.avatarPath, profile.fullName, 80.dp) }
                Spacer(Modifier.width(16.dp))
                SecondaryButton(
                    "Change photo",
                    { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    loading = uploading,
                    icon = Lucide.Camera,
                )
            }
            DheuTextField(fullName, { fullName = it }, "Name", maxLength = 60)
            DheuTextField(username, { username = it.lowercase() }, "Username", maxLength = 30)
            DheuTextField(bio, { bio = it }, "Bio", singleLine = false, minLines = 3, maxLength = 300, supporting = "${bio.length}/300")
            error?.let { Text(it, color = c.danger) }
            PrimaryButton(
                "Save",
                {
                    val name = fullName.trim()
                    val uname = username.trim().lowercase()
                    error = when {
                        name.isEmpty() -> "Enter your name."
                        !USERNAME.matches(uname) -> "Username: 3–30 letters, numbers, dots or underscores."
                        else -> null
                    }
                    if (error != null) return@PrimaryButton
                    saving = true
                    scope.launch {
                        try {
                            Profiles.updateDetails(profile.id, name, uname, bio.trim())
                            app.refreshMe()
                            toast("Saved")
                            nav.back()
                        } catch (e: Exception) {
                            error = if (e.message.orEmpty().contains("duplicate") || e.message.orEmpty().contains("23505")) "That username is already taken." else e.friendly("Couldn't save your profile.")
                        } finally {
                            saving = false
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                loading = saving,
            )
        }
    }
}

@Composable
fun BlockedScreen() {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val loader = rememberLoader("blocked") { Profiles.blocked(me) }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("Blocked accounts", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready ->
                if (s.value.isEmpty()) Text("You haven't blocked anyone.", color = Dheu.colors.inkMuted, modifier = Modifier.padding(24.dp))
                else Column {
                    s.value.forEach { person ->
                        PersonRow(person, relationships = null, trailing = {
                            SecondaryButton("Unblock", {
                                scope.launch {
                                    try {
                                        Profiles.unblock(me, person.id)
                                        loader.reload(silent = true)
                                    } catch (e: Exception) {
                                        toast(e.friendly())
                                    }
                                }
                            }, compact = true)
                        })
                    }
                }
        }
    }
}

/**
 * Settings → Delete account (0025): says what goes, asks for the password,
 * then the website deletes it all for good. The admin account can't go.
 */
@Composable
private fun DeleteAccountCard(isAdmin: Boolean) {
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Card("Delete account", if (isAdmin) "The admin account can't be deleted." else "Removes your account and everything you've shared, for good.") {
        if (!isAdmin) SecondaryButton("Delete my account", { open = true }, icon = Lucide.Trash, danger = true)
    }
    if (open) {
        DheuSheet("Delete your account?", onDismiss = {
            if (!deleting) {
                open = false
                password = ""
                error = null
            }
        }) {
            Text(
                "Your profile, posts, stories, comments, likes and messages are deleted for good, along with your one-to-one chats. This can't be undone.",
                color = c.inkMuted,
            )
            DheuTextField(password, { password = it }, "Your password", password = true, modifier = Modifier.padding(top = 12.dp))
            error?.let { Text(it, color = c.danger, modifier = Modifier.padding(top = 8.dp)) }
            SecondaryButton(
                "Delete my account for good",
                onClick = {
                    deleting = true
                    error = null
                    scope.launch {
                        try {
                            Account.deleteAccount(password)
                            toast("Your account has been deleted.")
                        } catch (e: Exception) {
                            error = e.friendly("Couldn't delete your account. Please try again.")
                        } finally {
                            deleting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = password.isNotEmpty(),
                loading = deleting,
                icon = Lucide.Trash,
                danger = true,
            )
        }
    }
}

/** How many people are on my close friends list (0024), and the way in to edit it. */
@Composable
private fun CloseFriendsCard(me: String) {
    val nav = LocalNav.current
    // Kept across the trip to the list and back, then caught up, so it doesn't flicker.
    var count by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { runCatching { Profiles.closeFriendIds(me).size }.onSuccess { count = it } }
    Card("Close friends", "The people who see the stories you share to close friends, with a green ring.") {
        val n = count ?: 0
        SecondaryButton(if (n > 0) "Edit your list ($n)" else "Choose close friends", { nav.to(CloseFriendsRoute) }, icon = Lucide.Star)
    }
}

/**
 * Settings → Close friends (0024): star the people who see your close friends
 * stories. Only you can see this list; nobody is told they're on it.
 */
@Composable
fun CloseFriendsScreen() {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val loader = rememberLoader("close-friends") { Profiles.closeFriendChoices(me) }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("Close friends", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> CloseFriendsList(me, s.value)
        }
    }
}

@Composable
private fun CloseFriendsList(me: String, choices: Profiles.CloseFriendChoices) {
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var on by remember(choices) { mutableStateOf(choices.chosen) }
    var query by rememberSaveable { mutableStateOf("") }
    // Close friends first (as the list opened), then everyone else, each by name.
    // The order stays put while you tap, so rows don't jump under your finger.
    val ordered = remember(choices) { choices.people.sortedBy { it.id !in choices.chosen } }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) ordered else ordered.filter { it.username.contains(q) || it.fullName.lowercase().contains(q) }

    fun toggle(p: Person) {
        val adding = p.id !in on
        on = if (adding) on + p.id else on - p.id
        scope.launch {
            try {
                if (adding) Profiles.addCloseFriend(me, p.id) else Profiles.removeCloseFriend(me, p.id)
            } catch (e: Exception) {
                on = if (adding) on - p.id else on + p.id
                toast(e.friendly("Couldn't save that. Please try again."))
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding()) {
        item(key = "intro") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    (if (on.isEmpty()) "Nobody yet." else "${on.size} ${if (on.size == 1) "person" else "people"}.") +
                        " Only you can see this list, and nobody is told when you add them.",
                    color = c.inkMuted,
                )
                if (choices.people.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search people you follow or who follow you") },
                        leadingIcon = { Icon(Lucide.Search, null, tint = c.inkFaint) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (choices.people.isEmpty()) {
            item(key = "empty") { EmptyState("No one to add yet", "Follow people, or wait for them to follow you, to add them here.") }
        } else if (shown.isEmpty()) {
            item(key = "none") { Text("No one matches that.", color = c.inkMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) }
        }
        items(shown, key = { it.id }) { p ->
            val isOn = p.id in on
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = isOn, role = Role.Checkbox, onValueChange = { toggle(p) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.avatarPath, p.fullName, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.fullName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${p.username}", color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .then(if (isOn) Modifier.background(CloseFriendsGreen) else Modifier.border(2.dp, c.line, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isOn) Lucide.StarFilled else Lucide.Star,
                        null,
                        tint = if (isOn) Color.White else c.inkFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        item(key = "end") { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
    }
}

/**
 * On / off for showing others when I've read their messages. It applies to my
 * account everywhere (profiles.read_receipts, 0022); I keep seeing theirs.
 */
@Composable
private fun ReadReceiptsSetting(me: String, saved: Boolean, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val c = Dheu.colors
    var on by remember(saved) { mutableStateOf(saved) }
    var saving by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (on) "On. People can see when you've read their messages."
            else "Off. People won't see when you've read their messages. You'll still see when they've read yours.",
            color = c.inkMuted,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            enabled = !saving,
            onCheckedChange = { value ->
                on = value
                saving = true
                scope.launch {
                    try {
                        Profiles.setReadReceipts(me, value)
                        onSaved()
                    } catch (e: Exception) {
                        on = !value
                        toast(e.friendly("Couldn't save that."))
                    } finally {
                        saving = false
                    }
                }
            },
            colors = SwitchDefaults.colors(checkedTrackColor = c.brand, checkedThumbColor = c.brandInk),
        )
    }
}

/** On / off for push on this phone. Off removes the phone from the member's devices, so nothing is sent to it. */
@Composable
private fun NotificationsSetting() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val c = Dheu.colors
    if (!Push.configured) {
        Text("This build of the app was made without push settings (see android/README.md).", color = c.inkMuted)
        return
    }
    val enabled by remember { PushPrefs.enabled(app) }.collectAsStateWithLifecycle(initialValue = true)
    // The permission can change in Android's settings while Dheu is in the background.
    var allowed by remember { mutableStateOf(Push.allowed(app)) }
    LifecycleResumeEffect(Unit) {
        allowed = Push.allowed(app)
        onPauseOrDispose {}
    }
    fun openSystemSettings() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (granted) {
            scope.launch { runCatching { Push.register() }.onFailure { toast(it.friendly("Couldn't turn notifications on.")) } }
        } else {
            toast("Allow notifications for Dheu in Android's settings.")
            openSystemSettings()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                !enabled -> "Off on this phone."
                !allowed -> "Android is blocking Dheu's notifications."
                else -> "On. You'll hear about new activity even when the app is closed."
            },
            color = c.inkMuted,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled && allowed,
            onCheckedChange = { on ->
                scope.launch {
                    PushPrefs.setEnabled(app, on)
                    when {
                        !on -> {
                            Push.unregister()
                            Push.forgetToken()
                        }
                        allowed -> runCatching { Push.register() }.onFailure { toast(it.friendly("Couldn't turn notifications on.")) }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> openSystemSettings()
                    }
                }
            },
            colors = SwitchDefaults.colors(checkedTrackColor = c.brand, checkedThumbColor = c.brandInk),
        )
    }
    Text(
        "If you've also installed the website on this phone and turned its notifications on, you'll get each one twice. Keep one of the two.",
        color = c.inkFaint,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}
