package app.betterdesk.rtc

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Development proof only: synthetic data/video, no OS capture, input or session access. */
abstract class RtcPeer(private val videoProof: Boolean = false) : Closeable {
    val opened = CompletableFuture<Unit>()
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val inbox = ArrayBlockingQueue<ByteArray>(32)
    private val videoInbox = ArrayBlockingQueue<RtcVideoSample>(1)
    private val videoSlots = Semaphore(2)
    private var lastVideoTimestamp = 0L
    protected val isClosed: Boolean get() = closed.get()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64), { runnable -> Thread(runnable, "BetterDesk RTC proof").apply { isDaemon = true } })
    private val deadline = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "BetterDesk RTC proof deadline").apply { isDaemon = true }
    }
    private var gathering: CompletableFuture<String>? = null
    private var deadlineStarted = false
    private enum class Negotiation { NEW, LOCAL_OFFER, REMOTE_OFFER, ANSWER_ACCEPTED }
    private var negotiation = Negotiation.NEW

    protected abstract fun createDescription(offer: Boolean, result: (String?) -> Unit)
    protected abstract fun setDescription(local: Boolean, offer: Boolean, sdp: String, result: (Boolean) -> Unit)
    protected abstract fun gatheredDescription(): String?
    protected abstract fun gatheringComplete(): Boolean
    protected abstract fun sendNative(bytes: ByteArray): Boolean
    protected abstract fun closeNative()
    protected open fun pushVideoNative(frame: RtcVideoFrame) { error("RTC video proof unavailable") }

    protected fun dispatch(block: () -> Unit) {
        synchronized(lock) {
            if (closed.get()) return
            try { worker.execute { if (!closed.get()) try { block() } catch (_: Exception) { close() } } }
            catch (_: RejectedExecutionException) { close() }
        }
    }

    protected fun <T> operation(block: (CompletableFuture<T>) -> Unit): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        synchronized(lock) {
            if (closed.get()) return result.apply { completeExceptionally(IOException("RTC proof closed")) }
            if (!deadlineStarted) {
                deadlineStarted = true
                deadline.schedule({ close() }, 30, TimeUnit.SECONDS)
            }
            pending.add(result)
            result.whenComplete { _, _ -> pending.remove(result) }
            dispatch { block(result) }
        }
        return result
    }

    /** One offer/answer per peer. Complete SDP includes ICE candidates and DTLS fingerprint. */
    fun offer(): CompletableFuture<String> = operation { result ->
        check(negotiation == Negotiation.NEW)
        negotiation = Negotiation.LOCAL_OFFER
        localDescription(true, result)
    }

    fun answer(authenticatedOffer: String): CompletableFuture<String> = operation { result ->
        check(negotiation == Negotiation.NEW)
        negotiation = Negotiation.REMOTE_OFFER
        RtcDescription.validate(authenticatedOffer, videoProof)
        setDescription(false, true, authenticatedOffer) { success -> dispatch {
            if (!success) close() else localDescription(false, result)
        } }
    }

    fun acceptAnswer(authenticatedAnswer: String): CompletableFuture<Unit> = operation { result ->
        check(negotiation == Negotiation.LOCAL_OFFER && gathering?.isDone == true)
        negotiation = Negotiation.ANSWER_ACCEPTED
        RtcDescription.validate(authenticatedAnswer, videoProof)
        setDescription(false, false, authenticatedAnswer) { success -> dispatch {
            if (!success) close() else result.complete(Unit)
        } }
    }

    private fun localDescription(offer: Boolean, result: CompletableFuture<String>) {
        check(gathering == null)
        gathering = result
        createDescription(offer) { sdp -> dispatch {
            if (sdp == null) close() else setDescription(true, offer, sdp) { success -> dispatch {
                if (!success) close() else gathered()
            } }
        } }
    }

    protected fun gathered() {
        if (!gatheringComplete()) return
        val sdp = gatheredDescription() ?: return
        RtcDescription.validate(sdp, videoProof)
        gathering?.complete(sdp)
    }

    protected fun channelOpened() { opened.complete(Unit) }
    protected fun channelFailed() { close() }

    /** Two pending raw frames at most; callers drop an unaccepted frame. */
    fun sendVideo(frame: RtcVideoFrame): CompletableFuture<Boolean> {
        check(videoProof)
        if (!videoSlots.tryAcquire()) return CompletableFuture.completedFuture(false)
        return operation<Boolean> { result ->
            check(opened.isDone && !opened.isCompletedExceptionally)
            check(frame.timestampNs > lastVideoTimestamp)
            check(System.nanoTime() - frame.timestampNs in 0..TimeUnit.SECONDS.toNanos(2))
            lastVideoTimestamp = frame.timestampNs
            pushVideoNative(frame)
            result.complete(true)
        }.whenComplete { _, _ -> videoSlots.release() }
    }

    protected fun incomingVideo(sample: RtcVideoSample) {
        synchronized(lock) {
            if (closed.get()) return
            videoInbox.poll() // Latest decoded sample only; never queue stale media.
            videoInbox.offer(sample)
        }
    }

    fun receiveVideo(timeoutMillis: Long): RtcVideoSample {
        check(videoProof)
        require(timeoutMillis in 1..30_000)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!closed.get()) {
            val remaining = until - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("RTC video proof receive timeout")
            videoInbox.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)?.let {
                if (!closed.get()) return it
            }
        }
        throw IOException("RTC proof closed")
    }

    /** Copy the callback-owned native buffer before returning; fail on overflow. */
    protected fun incoming(bytes: ByteBuffer, binary: Boolean) {
        if (closed.get()) return
        if (!binary || bytes.remaining() !in 1..MAX_BYTES) { close(); return }
        val copy = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
        if (!inbox.offer(copy)) close()
    }

    fun send(bytes: ByteArray): CompletableFuture<Unit> {
        require(bytes.size in 1..MAX_BYTES)
        val copy = bytes.copyOf()
        return operation { result ->
            check(opened.isDone && !opened.isCompletedExceptionally)
            if (!sendNative(copy)) close() else result.complete(Unit)
        }
    }

    fun receive(timeoutMillis: Long): ByteArray {
        require(timeoutMillis in 1..30_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!closed.get()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("RTC proof receive timeout")
            inbox.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)?.let {
                if (!closed.get()) return it
            }
        }
        throw IOException("RTC proof closed")
    }

    override fun close() {
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            val error = IOException("RTC proof closed")
            opened.completeExceptionally(error)
            pending.toList().forEach { it.completeExceptionally(error) }
            inbox.clear()
            videoInbox.clear()
            deadline.shutdownNow()
            worker.queue.clear()
            worker.execute { try { closeNative() } finally { worker.shutdown() } }
        }
    }

    /** Wait for native teardown in bounded fixtures, never on a native callback. */
    fun awaitClosed(timeoutMillis: Long): Boolean = worker.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS) &&
        deadline.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)

    companion object { const val MAX_BYTES = 16_384; const val MAX_BUFFERED = 65_536L }
}
