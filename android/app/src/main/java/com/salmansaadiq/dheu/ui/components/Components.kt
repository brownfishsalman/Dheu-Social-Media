package com.salmansaadiq.dheu.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.storageImage
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.theme.CloseFriendsGreen
import com.salmansaadiq.dheu.ui.theme.Dheu

// ---- people -------------------------------------------------------------------

/** Deterministic soft hue for initials, keyed on the name (same as the website). */
private fun hue(name: String): Float {
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) % 360
    return h.toFloat()
}

@Composable
fun Avatar(path: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val image = storageImage(Buckets.AVATARS, path)
    if (image != null) {
        AsyncImage(
            model = image,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape).background(Dheu.colors.surface2),
        )
    } else {
        val initials = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
        val h = hue(name)
        val dark = Dheu.colors.isDark
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.hsl(h, 0.55f, if (dark) 0.66f else 0.88f)),
        ) {
            Text(
                initials.ifEmpty { "?" },
                color = Color.hsl(h, 0.45f, if (dark) 0.22f else 0.30f),
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.38f).coerceAtLeast(10f).sp,
            )
        }
    }
}

/**
 * Gradient ring for unseen stories (green when one of them is for close
 * friends, 0024), flat for seen ones, none when null.
 */
@Composable
fun StoryRing(seen: Boolean?, modifier: Modifier = Modifier, closeFriends: Boolean = false, content: @Composable () -> Unit) {
    val c = Dheu.colors
    val ring = when (seen) {
        null -> null
        true -> Modifier.border(2.5.dp, c.line, CircleShape)
        false -> if (closeFriends) Modifier.border(2.5.dp, CloseFriendsGreen, CircleShape)
        else Modifier.border(BorderStroke(2.5.dp, Brush.sweepGradient(listOf(c.waveA, c.waveB, c.waveA))), CircleShape)
    }
    Box(modifier.then(ring ?: Modifier).padding(4.dp), contentAlignment = Alignment.Center) { content() }
}

// ---- buttons -------------------------------------------------------------------

private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    compact: Boolean = false,
    /** Another fill than the brand teal (e.g. close friends green), with white text. */
    color: Color? = null,
) {
    val c = Dheu.colors
    val bg = color ?: c.brand
    val fg = if (color != null) Color.White else c.brandInk
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg, disabledContainerColor = bg.copy(alpha = 0.55f), disabledContentColor = fg),
        contentPadding = if (compact) PaddingValues(horizontal = 14.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
        modifier = modifier,
    ) { ButtonContent(text, loading, icon, fg) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    compact: Boolean = false,
    danger: Boolean = false,
) {
    val c = Dheu.colors
    val fg = if (danger) c.danger else c.ink
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = ButtonShape,
        border = BorderStroke(1.dp, if (danger) c.dangerSoft else c.line),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) c.dangerSoft else c.surface,
            contentColor = fg,
            disabledContainerColor = c.surface,
            disabledContentColor = c.inkFaint,
        ),
        contentPadding = if (compact) PaddingValues(horizontal = 14.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
        modifier = modifier,
    ) { ButtonContent(text, loading, icon, fg) }
}

@Composable
private fun ButtonContent(text: String, loading: Boolean, icon: ImageVector?, color: Color) {
    if (loading) {
        CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
    } else if (icon != null) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Dheu.colors.ink, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp)) }
}

// ---- inputs -------------------------------------------------------------------

@Composable
fun DheuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    maxLength: Int? = null,
    minLines: Int = 1,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val c = Dheu.colors
    OutlinedTextField(
        value = value,
        onValueChange = { if (maxLength == null || it.length <= maxLength) onValueChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = supporting?.let { { Text(it, color = c.inkFaint) } },
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun fieldColors() = Dheu.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surface2,
        disabledContainerColor = c.surface2,
        focusedBorderColor = c.brand,
        unfocusedBorderColor = c.line,
        focusedLabelColor = c.brand,
        unfocusedLabelColor = c.inkMuted,
        cursorColor = c.brand,
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        focusedPlaceholderColor = c.inkFaint,
        unfocusedPlaceholderColor = c.inkFaint,
    )
}

// ---- structure -------------------------------------------------------------------

@Composable
fun DheuTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val c = Dheu.colors
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 1)
                }
            },
            navigationIcon = { if (onBack != null) IconAction(Lucide.ChevronLeft, "Back", onBack) },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface, titleContentColor = c.ink),
        )
        HorizontalDivider(color = c.line)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Dheu.colors.brand, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Alert, null, tint = Dheu.colors.inkFaint, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = Dheu.colors.inkMuted)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Try again", onRetry, icon = Lucide.Refresh)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Dheu.colors.inkMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Small red / teal count pill (99+ cap), as on the website's nav. */
@Composable
fun CountBadge(count: Int, color: Color = Dheu.colors.brand, textColor: Color = Dheu.colors.brandInk) {
    if (count <= 0) return
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.sizeIn(minWidth = 18.dp, minHeight = 18.dp).clip(CircleShape).background(color).padding(horizontal = 5.dp),
    ) {
        Text(if (count > 99) "99+" else count.toString(), color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** A bottom sheet in the app's colours, with a title row. */
@Composable
fun DheuSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Dheu.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            content()
        }
    }
}

/** One tappable row in a sheet or menu. */
@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val c = Dheu.colors
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (danger) c.danger else c.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (danger) c.danger else c.ink, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    val c = Dheu.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, color = c.inkMuted) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = if (danger) c.danger else c.brand, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.inkMuted) } },
    )
}

@Composable
fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Dheu.colors.inkMuted,
        letterSpacing = 1.5.sp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
