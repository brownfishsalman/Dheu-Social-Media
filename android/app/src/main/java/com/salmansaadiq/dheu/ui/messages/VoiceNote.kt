package com.salmansaadiq.dheu.ui.messages

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salmansaadiq.dheu.core.formatDuration
import com.salmansaadiq.dheu.data.SignedLinks
import com.salmansaadiq.dheu.ui.icons.Lucide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlayState { Idle, Preparing, Playing, Paused }

/**
 * A sent voice message, streamed from Storage (webm/opus or mp4/aac, both
 * decoded by MediaPlayer) through a signed link asked for on the first play,
 * so the access rules (0025) apply as they do to photos.
 */
@Composable
fun VoiceNote(bucket: String, path: String, durationMs: Long?, tint: Color, track: Color) =
    VoicePlayer("$bucket/$path", durationMs, tint, track) { SignedLinks.of(bucket, path) }

/** A clip just recorded, played from the phone before it's sent. */
@Composable
fun RecordedVoiceNote(filePath: String, durationMs: Long?, tint: Color, track: Color) =
    VoicePlayer(filePath, durationMs, tint, track) { filePath }

/** Play / pause, with progress; [source] gives what MediaPlayer opens (a link or a file path). */
@Composable
private fun VoicePlayer(key: String, durationMs: Long?, tint: Color, track: Color, source: suspend () -> String) {
    var state by remember(key) { mutableStateOf(PlayState.Idle) }
    var progress by remember(key) { mutableFloatStateOf(0f) }
    var positionMs by remember(key) { mutableFloatStateOf(0f) }
    val player = remember(key) { arrayOfNulls<MediaPlayer>(1) }
    val scope = rememberCoroutineScope()

    DisposableEffect(key) {
        onDispose {
            player[0]?.release()
            player[0] = null
        }
    }
    LaunchedEffect(state) {
        while (state == PlayState.Playing) {
            player[0]?.let { p ->
                positionMs = p.currentPosition.toFloat()
                if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration
            }
            delay(100)
        }
    }

    fun play(url: String) {
        player[0] = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(url)
            setOnPreparedListener { it.start(); state = PlayState.Playing }
            setOnCompletionListener {
                state = PlayState.Idle
                progress = 0f
                positionMs = 0f
                it.release()
                player[0] = null
            }
            setOnErrorListener { mp, _, _ ->
                state = PlayState.Idle
                mp.release()
                player[0] = null
                true
            }
            prepareAsync()
        }
    }

    fun toggle() {
        val existing = player[0]
        when (state) {
            PlayState.Playing -> { existing?.pause(); state = PlayState.Paused }
            PlayState.Paused -> { existing?.start(); state = PlayState.Playing }
            PlayState.Preparing -> Unit
            PlayState.Idle -> {
                state = PlayState.Preparing
                scope.launch {
                    val from = runCatching { source() }.getOrNull()
                    if (from == null) state = PlayState.Idle else runCatching { play(from) }.onFailure { state = PlayState.Idle }
                }
            }
        }
    }

    Row(Modifier.clickable(onClick = ::toggle).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            PlayState.Preparing -> CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            PlayState.Playing -> Icon(Lucide.Pause, "Pause", tint = tint, modifier = Modifier.size(22.dp))
            else -> Icon(Lucide.PlayFilled, "Play voice message", tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            color = tint,
            trackColor = track,
            drawStopIndicator = {},
            modifier = Modifier.width(140.dp).height(3.dp),
        )
        Spacer(Modifier.width(10.dp))
        val shown = if (state == PlayState.Idle) (durationMs ?: 0L) else positionMs.toLong()
        Text(formatDuration(shown), color = tint, fontSize = 12.sp)
    }
}
