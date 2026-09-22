package com.gokuencinar.iruniversal.ir

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    private val recent = ArrayDeque<IrCode>()

    fun start(codes: List<IrCode>, pace: ScanPace, callback: (Progress) -> Unit) {
        val scanGeneration = generation.incrementAndGet()
        val scanCodes = codes.toList()
        synchronized(recent) { recent.clear() }
        stopped.set(false)
        paused.set(false)

        executor.execute {
            var index = 0
            while (!stopped.get() && generation.get() == scanGeneration && index < scanCodes.size) {
                if (paused.get()) {
                    if (!sleep(40)) break
                    continue
                }

                val code = scanCodes[index]
                var error: String? = null
                try {
                    transmitterProvider().send(code)
                    synchronized(recent) {
                        recent.remove(code)
                        recent.addLast(code)
                        while (recent.size > 8) recent.removeFirst()
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }

                if (stopped.get() || generation.get() != scanGeneration) break
                index++
                callback(Progress(index, scanCodes.size, code, false, error))

                if (!stopped.get() && generation.get() == scanGeneration && !sleep(pace.gapMillis)) {
                    break
                }
            }

            if (!stopped.get() && generation.get() == scanGeneration && index >= scanCodes.size) {
                callback(Progress(scanCodes.size, scanCodes.size, null, false))
                stopped.set(true)
            }
        }
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }
    fun stop() {
        generation.incrementAndGet()
        stopped.set(true)
        paused.set(false)
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

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
