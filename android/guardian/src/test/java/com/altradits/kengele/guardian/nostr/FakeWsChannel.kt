package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.NostrJson
import com.altradits.kengele.message.nostr.RelayMessages
import com.altradits.kengele.message.nostr.WsException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A scriptable [WsChannel] for tests: records everything sent, delivers
 * queued replies, and runs [responder] on every send. All keys and data in
 * tests are made up.
 */
class FakeWsChannel : WsChannel {
    val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Runs on every [sendText]; use it to answer with [deliver]. */
    @Volatile
    var responder: ((String) -> Unit)? = null

    private val toClient = LinkedBlockingQueue<Any>()
    private val closedFlag = AtomicBoolean(false)
    private object Closed

    /** Queues a message for the client's [nextText]. */
    fun deliver(text: String) {
        toClient.offer(text)
    }

    override fun sendText(text: String) {
        if (closedFlag.get()) throw WsException("closed")
        sent.add(text)
        responder?.invoke(text)
    }

    override fun nextText(): String {
        if (closedFlag.get() && toClient.isEmpty()) throw WsException("closed")
        try {
            val v = toClient.take()
            if (v === Closed) throw WsException("closed")
            return v as String
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WsException("closed")
        }
    }

    override fun close() {
        closedFlag.set(true)
        toClient.offer(Closed)
    }
}

/**
 * A minimal in-memory Nostr relay hanging off a [FakeWsChannel]: answers
 * publishes with OK (or a refusal), and pushes stored events to subscribers.
 */
class FakeRelay(val url: String, @Volatile var accept: Boolean = true) {
    val received: MutableList<NostrEvent> = Collections.synchronizedList(mutableListOf())
    val subIds: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun attach(ch: FakeWsChannel): FakeWsChannel {
        ch.responder = { text ->
            when (val m = RelayMessages.parse(text)) {
                is RelayMessages.Incoming.Publish -> {
                    received.add(m.event)
                    ch.deliver(ok(m.event.id, accept))
                }
                is RelayMessages.Incoming.Unknown -> {
                    // A REQ from the client: record the subscription id.
                    reqSubId(text)?.let { subIds.add(it) }
                }
                else -> Unit
            }
        }
        return ch
    }

    /** Pushes [event] to every subscription on [ch], followed by EOSE. */
    fun push(ch: FakeWsChannel, event: NostrEvent) {
        for (sub in subIds.toList()) {
            ch.deliver("[\"EVENT\",\"$sub\",${event.toJson()}]")
        }
        for (sub in subIds.toList()) {
            ch.deliver("[\"EOSE\",\"$sub\"]")
        }
    }

    private fun ok(id: String, ok: Boolean): String =
        if (ok) "[\"OK\",\"$id\",true,\"\"]" else "[\"OK\",\"$id\",false,\"blocked: test\"]"

    private fun reqSubId(text: String): String? {
        return try {
            val arr = NostrJson.parse(text) as? NostrJson.Arr ?: return null
            if ((arr.items.firstOrNull() as? NostrJson.Str)?.v != "REQ") return null
            (arr.items.getOrNull(1) as? NostrJson.Str)?.v
        } catch (e: Exception) {
            null
        }
    }
}
