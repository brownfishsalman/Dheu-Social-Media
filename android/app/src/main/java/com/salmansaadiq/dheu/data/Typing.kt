package com.salmansaadiq.dheu.data

import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Typing…" in one conversation, over the private Realtime channel
 * "typing:<conversation id>" that only its members may join or send on
 * (0023). The topic is fixed, so one channel per chat is shared by every
 * screen showing it and kept for a moment after the last one leaves, rather
 * than opened twice.
 */
object Typing {
    private const val SEND_EVERY_MS = 2_500L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val links = mutableMapOf<String, TypingLink>()

    fun open(conversationId: String): TypingLink = synchronized(links) {
        val link = links.getOrPut(conversationId) { TypingLink(conversationId).also { it.start() } }
        link.closing?.cancel()
        link.closing = null
        link.users++
        link
    }

    class TypingLink internal constructor(private val conversationId: String) {
        private val channel: RealtimeChannel = Backend.client.channel("typing:$conversationId") { isPrivate = true }
        private val events = MutableSharedFlow<String>(extraBufferCapacity = 16)
        internal var users = 0
        internal var closing: Job? = null
        @Volatile private var lastSent = 0L

        /** Ids of members as they say they're typing (never my own). */
        val typing: SharedFlow<String> get() = events

        internal fun start() {
            scope.launch {
                launch {
                    channel.broadcastFlow<JsonObject>("typing").collect { payload ->
                        payload.string("user_id")?.let { events.tryEmit(it) }
                    }
                }
                runCatching { channel.subscribe() }
            }
        }

        /** Say I'm typing: at most every 2.5 seconds, and only once joined. */
        fun announce(me: String) {
            val now = System.currentTimeMillis()
            if (now - lastSent < SEND_EVERY_MS || channel.status.value != RealtimeChannel.Status.SUBSCRIBED) return
            lastSent = now
            scope.launch { runCatching { channel.broadcast("typing", buildJsonObject { put("user_id", me) }) } }
        }

        /** This screen is done with it; the channel closes when no screen uses it for 5 seconds. */
        fun close() {
            synchronized(links) {
                users--
                if (users > 0) return
                closing = scope.launch {
                    delay(5_000)
                    val gone = synchronized(links) {
                        if (users > 0) false else { links.remove(conversationId); true }
                    }
                    if (gone) runCatching { Backend.client.realtime.removeChannel(channel) }
                }
            }
        }
    }
}
