package com.gokuencinar.iruniversal.learn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.gokuencinar.iruniversal.ir.IrCode
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

class IrLearner(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    data class CaptureResult(
        val code: IrCode?,
        val message: String,
        val sampleRate: Int
    )

    data class InputInfo(
        val description: String,
        val isExternal: Boolean,
        val inputChannels: Int
    )

    fun inspectInput(): InputInfo {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val external = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (external != null) {
            val channels = external.channelCounts.maxOrNull() ?: 1
            return InputInfo(
                external.productName?.toString()?.ifBlank { "Entrada de audio externa" }
                    ?: "Entrada de audio externa",
                true,
                channels.coerceAtLeast(1)
            )
        }
        val internal = devices.firstOrNull()
        return InputInfo(
            internal?.productName?.toString()?.ifBlank { "Micrófono interno" } ?: "Micrófono interno",
            false,
            internal?.channelCounts?.maxOrNull()?.coerceAtLeast(1) ?: 0
        )
    }

    fun capture(carrierHz: Int = 38_000, captureMillis: Int = 1300): CaptureResult {
        val sampleRate = 48_000
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return CaptureResult(null, "No hay permiso de micrófono para capturar la señal IR.", sampleRate)
        }
        val input = inspectInput()
        if (!input.isExternal) {
            return CaptureResult(
                null,
                "No se detecta una entrada de audio externa. El micrófono interno no sirve para aprender IR con este accesorio.",
                sampleRate
            )
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return CaptureResult(null, "La entrada de audio no admite PCM mono a 48 kHz.", sampleRate)

        val totalSamples = sampleRate * captureMillis / 1000
        val record = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, 8192)
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return CaptureResult(null, "No se pudo inicializar AudioRecord.", sampleRate)
        }

        val samples = ShortArray(totalSamples)
        var offset = 0
        try {
            record.startRecording()
            while (offset < samples.size) {
                val n = record.read(samples, offset, samples.size - offset)
                if (n <= 0) break
                offset += n
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        if (offset < sampleRate / 10) {
            return CaptureResult(null, "La captura fue demasiado corta.", sampleRate)
        }

        val usable = samples.copyOf(offset)
        val durations = reconstructDurations(usable, sampleRate)
        if (durations.size < 6) {
            return CaptureResult(
                null,
                "No se detectó una trama IR clara. Comprueba el receptor IR y el nivel de entrada.",
                sampleRate
            )
        }

        return CaptureResult(
            IrCode("learned:" + UUID.randomUUID(), carrierHz, durations),
            "Captura reconstruida: " + durations.size + " segmentos.",
            sampleRate
        )
    }

    private fun reconstructDurations(samples: ShortArray, sampleRate: Int): List<Int> {
        if (samples.size < 3) return emptyList()

        var maxDerivative = 0
        val derivatives = IntArray(samples.size - 1)
        for (i in 1 until samples.size) {
            val d = abs(samples[i].toInt() - samples[i - 1].toInt())
            derivatives[i - 1] = d
            if (d > maxDerivative) maxDerivative = d
        }
        if (maxDerivative < 600) return emptyList()

        val threshold = max(900, (maxDerivative * 0.32).toInt())
        val minGapSamples = max(2, (sampleRate * 120L / 1_000_000L).toInt())
        val edges = mutableListOf<Int>()
        var last = -minGapSamples

        derivatives.forEachIndexed { index, d ->
            if (d >= threshold && index - last >= minGapSamples) {
                edges += index
                last = index
            }
        }

        if (edges.size < 7) return emptyList()
        val raw = edges.zipWithNext { a, b ->
            (((b - a) * 1_000_000L) / sampleRate).toInt()
        }.filter { it in 100..120_000 }

        if (raw.size < 6) return emptyList()

        val firstUseful = raw.indexOfFirst { it < 30_000 }.coerceAtLeast(0)
        val trimmed = raw.drop(firstUseful).take(6000).toMutableList()

        while (trimmed.isNotEmpty() && trimmed.last() > 80_000) {
            // Keep this compatible with Android 11: MutableList.removeLast()
            // may compile to java.util.List.removeLast() against API 35.
            trimmed.removeAt(trimmed.lastIndex)
        }
        return trimmed
    }
}
