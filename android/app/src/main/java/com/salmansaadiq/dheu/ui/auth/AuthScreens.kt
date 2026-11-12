package com.salmansaadiq.dheu.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salmansaadiq.dheu.R
import com.salmansaadiq.dheu.data.Account
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.SignupRequest
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.components.DheuTextField
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.theme.Dheu
import com.salmansaadiq.dheu.ui.theme.Outfit
import kotlinx.coroutines.launch

/** Logged-out flow: log in, or sign up (with an invite code while sign-up is invite-only). */
@Composable
fun AuthFlow() {
    var signingUp by rememberSaveable { mutableStateOf(false) }
    if (signingUp) SignupScreen(onLogin = { signingUp = false }) else LoginScreen(onSignup = { signingUp = true })
}

@Composable
private fun AuthFrame(subtitle: String, content: @Composable () -> Unit) {
    val c = Dheu.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.canvas)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.dheu_mark), contentDescription = null, modifier = Modifier.height(64.dp))
        Spacer(Modifier.height(12.dp))
        Wordmark()
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = c.inkMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** "Dheu" in the logo's wave gradient. */
@Composable
fun Wordmark(fontSize: Int = 34) {
    val c = Dheu.colors
    Text(
        "Dheu",
        style = TextStyle(
            brush = Brush.linearGradient(listOf(c.waveA, c.waveB)),
            fontFamily = Outfit,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
        ),
    )
}

@Composable
private fun ErrorText(message: String?) {
    if (message == null) return
    Text(
        message,
        color = Dheu.colors.danger,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Dheu.colors.dangerSoft)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun LoginScreen(onSignup: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthFrame("Welcome back. Your people are here.") {
        DheuTextField(email, { email = it }, "Email", keyboardType = KeyboardType.Email)
        DheuTextField(password, { password = it }, "Password", password = true)
        ErrorText(error)
        PrimaryButton(
            "Log in",
            onClick = {
                if (email.isBlank() || password.isEmpty()) {
                    error = "Enter your email and password."
                    return@PrimaryButton
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        Account.signIn(email, password) // the session flow takes it from here
                    } catch (e: Exception) {
                        error = e.friendly("Wrong email or password.")
                    } finally {
                        busy = false
                    }
                }
            },
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onSignup, modifier = Modifier.fillMaxWidth()) {
            Text("New here? Create an account", color = Dheu.colors.brand, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val USERNAME = Regex("^[a-z0-9_.]{3,30}$")

@Composable
private fun SignupScreen(onLogin: () -> Unit) {
    var invite by rememberSaveable { mutableStateOf("") }
    var fullName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var accountType by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The invite field shows only while sign-up is invite-only (0028). If the
    // website can't be asked, it shows once the website says a code is needed.
    var inviteOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (Account.inviteOnly() == true) inviteOnly = true }

    fun validate(): String? = when {
        inviteOnly && invite.isBlank() -> "Enter your invite code"
        fullName.isBlank() -> "Enter your name"
        !USERNAME.matches(username.trim().lowercase()) -> "Username: 3–30 letters, numbers, dots or underscores"
        !email.contains('@') -> "Enter a valid email address"
        password.length < 8 -> "Use at least 8 characters for your password"
        accountType == null -> "Choose a public or private account"
        else -> null
    }

    AuthFrame(if (inviteOnly) "Join with an invite code from a member." else "Create your account and say hi.") {
        if (inviteOnly) DheuTextField(invite, { invite = it.uppercase() }, "Invite code")
        DheuTextField(fullName, { fullName = it }, "Full name", maxLength = 60)
        DheuTextField(username, { username = it.lowercase() }, "Username", maxLength = 30, supporting = "Letters, numbers, dots or underscores")
        DheuTextField(email, { email = it }, "Email", keyboardType = KeyboardType.Email)
        DheuTextField(password, { password = it }, "Password", password = true, supporting = "At least 8 characters")
        Text("Account type", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        AccountTypePicker(selected = accountType, onSelect = { accountType = it })
        Text("You can change this later in Settings.", color = Dheu.colors.inkFaint, style = MaterialTheme.typography.bodySmall)
        ErrorText(error)
        PrimaryButton(
            "Create account",
            onClick = {
                error = validate()
                if (error != null) return@PrimaryButton
                busy = true
                scope.launch {
                    try {
                        Account.signUp(
                            SignupRequest(fullName.trim(), username.trim().lowercase(), email.trim(), password, invite.trim(), accountType!!),
                        )
                    } catch (e: Exception) {
                        error = e.friendly("Could not create your account. Please try again.")
                        if (e.message?.contains("invite code is required", ignoreCase = true) == true) inviteOnly = true
                    } finally {
                        busy = false
                    }
                }
            },
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Already a member? Log in", color = Dheu.colors.brand, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Public / private radio cards, the same wording as the website. */
@Composable
fun AccountTypePicker(selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TypeCard("public", "Public", "Anyone on Dheu can see your posts, stories and followers, and can follow you without asking.", selected, onSelect)
        TypeCard("private", "Private", "Only people you approve can see your posts and stories. Following you takes a request.", selected, onSelect)
    }
}

@Composable
private fun TypeCard(value: String, title: String, text: String, selected: String?, onSelect: (String) -> Unit) {
    val c = Dheu.colors
    val on = selected == value
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) c.brandSoft else c.surface)
            .border(1.dp, if (on) c.brand else c.line, RoundedCornerShape(14.dp))
            .clickable { onSelect(value) }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = on, onClick = { onSelect(value) }, colors = RadioButtonDefaults.colors(selectedColor = c.brand, unselectedColor = c.inkFaint))
        Spacer(Modifier.width(4.dp))
        Column(Modifier.padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (value == "public") Lucide.Globe else Lucide.Lock, null, tint = c.ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(2.dp))
            Text(text, color = c.inkMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Members from before accounts could be public pick one before going on. */
@Composable
fun ChoosePrivacyScreen(onDone: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthFrame("Accounts on Dheu can now be public. Until today every account was private. Choose what suits you; you can change it any time in Settings.") {
        Text("Public or private?", style = MaterialTheme.typography.headlineSmall)
        AccountTypePicker(choice) { choice = it }
        ErrorText(error)
        PrimaryButton(
            "Continue",
            onClick = {
                val picked = choice ?: return@PrimaryButton
                busy = true
                error = null
                scope.launch {
                    try {
                        Profiles.setPrivacy(picked == "private")
                        onDone()
                    } catch (e: Exception) {
                        error = e.friendly("Couldn't save that. Please try again.")
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = choice != null,
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
