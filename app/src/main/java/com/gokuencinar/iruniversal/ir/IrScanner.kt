package com.gokuencinar.iruniversal.ir

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val recent = ArrayDeque<IrCode>()
    @Volatile private var currentCodes: List<IrCode> = emptyList()
    @Volatile private var currentIndex = 0
    @Volatile private var pace = ScanPace.FAST

    fun start(codes: List<IrCode>, pace: ScanPace, callback: (Progress) -> Unit) {
        stop()
        currentCodes = codes
        currentIndex = 0
        this.pace = pace
        recent.clear()
        stopped.set(false)
        paused.set(false)

        executor.execute {
            while (!stopped.get() && currentIndex < currentCodes.size) {
                if (paused.get()) {
                    Thread.sleep(40)
                    continue
                }

                val code = currentCodes[currentIndex]
                try {
                    transmitterProvider().send(code)
                    synchronized(recent) {
                        recent.remove(code)
                        recent.addLast(code)
                        while (recent.size > 8) recent.removeFirst()
                    }
                    currentIndex++
                    callback(Progress(currentIndex, currentCodes.size, code, false))
                } catch (e: Exception) {
                    currentIndex++
                    callback(Progress(currentIndex, currentCodes.size, code, false, e.message))
                }

                if (!stopped.get()) Thread.sleep(pace.gapMillis)
            }

            if (!stopped.get()) {
                callback(Progress(currentCodes.size, currentCodes.size, null, false))
                stopped.set(true)
            }
        }
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }
    fun stop() { stopped.set(true); paused.set(false) }
    fun isRunning(): Boolean = !stopped.get()
    fun isPaused(): Boolean = paused.get()

    fun candidates(): List<IrCode> = synchronized(recent) {
        recent.toList().asReversed().take(4)
    }
}
