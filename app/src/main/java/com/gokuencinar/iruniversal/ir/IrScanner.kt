package com.gokuencinar.iruniversal.ir

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IrScanner(private val transmitterProvider: () -> IrTransmitter) {
    data class Progress(
        val index: Int,
        val total: Int,
        val code: IrCode?,
        val paused: Boolean,
        val error: String? = null
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(true)
    private val paused = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val pendingStep = AtomicInteger(0)
    private val recent = ArrayDeque<IrCode>()

    @Volatile private var currentIndex = 0
    @Volatile private var presentedIndex = -1

    fun start(codes: List<IrCode>, pace: ScanPace, callback: (Progress) -> Unit) {
        val scanGeneration = generation.incrementAndGet()
        val scanCodes = codes.toList()

        synchronized(recent) { recent.clear() }
        currentIndex = 0
        presentedIndex = -1
        pendingStep.set(0)
        stopped.set(false)
        paused.set(false)

        executor.execute {
            while (!stopped.get() && generation.get() == scanGeneration) {
                val manualDelta = consumePendingStep()

                if (manualDelta != 0) {
                    paused.set(true)
                    val base = if (presentedIndex >= 0) presentedIndex else currentIndex
                    currentIndex = (base + manualDelta).coerceIn(0, scanCodes.lastIndex)
                } else if (paused.get()) {
                    if (!sleep(40)) break
                    continue
                }

                if (currentIndex !in scanCodes.indices) {
                    if (!paused.get() && currentIndex >= scanCodes.size) {
                        callback(Progress(scanCodes.size, scanCodes.size, null, false))
                        stopped.set(true)
                    }
                    break
                }

                val codeIndex = currentIndex
                val code = scanCodes[codeIndex]
                var error: String? = null

                try {
                    transmitterProvider().send(code)
                    remember(code)
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }

                if (stopped.get() || generation.get() != scanGeneration) break

                presentedIndex = codeIndex
                callback(
                    Progress(
                        index = codeIndex + 1,
                        total = scanCodes.size,
                        code = code,
                        paused = paused.get(),
                        error = error
                    )
                )

                if (manualDelta != 0) {
                    // Manual stepping mirrors iOS: stepping pauses the automatic
                    // sequence and previews exactly one code at a time.
                    if (!sleep(40)) break
                    continue
                }

                currentIndex = codeIndex + 1

                if (currentIndex >= scanCodes.size) {
                    callback(Progress(scanCodes.size, scanCodes.size, null, false))
                    stopped.set(true)
                    break
                }

                if (!sleep(pace.gapMillis)) break
            }
        }
    }

    fun pause() {
        if (!stopped.get()) paused.set(true)
    }

    fun resume() {
        if (!stopped.get()) {
            pendingStep.set(0)
            paused.set(false)
        }
    }

    fun step(delta: Int) {
        if (!stopped.get() && delta != 0) {
            paused.set(true)
            pendingStep.addAndGet(delta.coerceIn(-1, 1))
        }
    }

    fun stop() {
        generation.incrementAndGet()
        stopped.set(true)
        paused.set(false)
        pendingStep.set(0)
        currentIndex = 0
        presentedIndex = -1
    }

    fun isRunning(): Boolean = !stopped.get()
    fun isPaused(): Boolean = paused.get()

    fun candidates(): List<IrCode> = synchronized(recent) {
        recent.toList().asReversed().take(4)
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun remember(code: IrCode) {
        synchronized(recent) {
            recent.remove(code)
            recent.addLast(code)
            while (recent.size > 8) recent.removeFirst()
        }
    }

    private fun consumePendingStep(): Int {
        while (true) {
            val value = pendingStep.get()
            if (value == 0) return 0
            val step = if (value > 0) 1 else -1
            if (pendingStep.compareAndSet(value, value - step)) return step
        }
    }

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
