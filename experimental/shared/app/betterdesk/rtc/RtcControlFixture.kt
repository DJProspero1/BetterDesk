package app.betterdesk.rtc

import app.betterdesk.core.Message
import app.betterdesk.core.Wire
import app.betterdesk.core.isValidInput

/** Synthetic protocol messages only: never delivers them to OS input or a clipboard. */
object RtcControlFixture {
    private fun messages(): List<Message> {
        val empty = Message("exchangeText", action = "synthetic-revision")
        val overhead = RtcControlEnvelope.fragment(empty, 1, 1).sumOf { it.size - RtcControlEnvelope.HEADER }
        val maximum = empty.copy(text = "x".repeat(Wire.MAX_MESSAGE - overhead))
        return listOf(
            Message("input", action = "text", text = "Olá · synthetic", frame = 1, epoch = 2),
            Message("input", action = "key", key = "Enter", frame = 1, epoch = 2),
            Message("exchangeBarrier", text = "synthetic-barrier"),
            maximum,
            Message("exchangeBarrierAck", text = "synthetic-barrier", control = true),
            Message("switchRequest", epoch = 2),
            Message("ping", frame = 1, epoch = 2),
            Message("capability", control = false, epoch = 2)
        ).also { check(it[0].isValidInput() && it[1].isValidInput()) }
    }
    /** Use both ends concurrently. Leaves peer cleanup to the enclosing fixture. */
    fun exchange(peer: RtcPeer, first: Boolean) {
        val channel = RtcControlChannel(peer, 1)
        fun write() { messages().forEach(channel::send) }
        fun read() { messages().forEach { check(channel.read() == it) { "Synthetic control mismatch" } } }
        if (first) { write(); read() } else { read(); write() }
    }
}
