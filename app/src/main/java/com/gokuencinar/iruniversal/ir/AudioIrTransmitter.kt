package com.gokuencinar.iruniversal.ir

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

class AudioIrTransmitter(private val context: Context) : IrTransmitter {
    override val name: String = "Adaptador de audio"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun isAvailable(): Boolean = chooseSampleRate() > 0

    override fun send(code: IrCode) {
        val sampleRate = chooseSampleRate()
        require(sampleRate > 0) { "No hay una salida PCM estéreo compatible" }

        val audioHz = code.effectiveCarrierHz / 2.0
        require(audioHz <= sampleRate * 0.45) {
            "La salida de " + sampleRate + " Hz no puede representar con margen la portadora " +
                code.effectiveCarrierHz + " Hz"
        }

        val pcm = render(code, sampleRate)
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, 16 * 1024))
            .build()

        try {
            track.setVolume(1.0f)
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) error("AudioTrack.write falló: " + written)
                offset += written
            }
            val frames = pcm.size / 2
            val playbackMillis = ceil(frames * 1000.0 / sampleRate).toLong()
            Thread.sleep(playbackMillis + 20L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun render(code: IrCode, sampleRate: Int): FloatArray {
        val prePadMicros = 15_000L
        val gapMicros = 100_000L
        val signalMicros = code.durationsMicros.sumOf { it.toLong() }
        val frames = ceil((prePadMicros + signalMicros + gapMicros) * sampleRate / 1_000_000.0)
            .toInt().coerceAtLeast(1)

        val out = FloatArray(frames * 2)
        var cursor = (prePadMicros * sampleRate / 1_000_000.0).toInt()
        val audioHz = code.effectiveCarrierHz / 2.0
        val phaseIncrement = 2.0 * PI * audioHz / sampleRate
        val amplitude = 0.999f

        code.durationsMicros.forEachIndexed { index, micros ->
            val segmentFrames = (micros * sampleRate / 1_000_000.0).toInt()
            val end = minOf(frames, cursor + segmentFrames)
            if (index % 2 == 0) {
                var phase = 0.0
                while (cursor < end) {
                    val sample = amplitude * sin(phase).toFloat()
                    val pos = cursor * 2
                    out[pos] = sample
                    out[pos + 1] = -sample
                    phase += phaseIncrement
                    if (phase >= 2.0 * PI) phase -= 2.0 * PI
                    cursor++
                }
            } else {
                cursor = end
            }
        }
        return out
    }

    private fun chooseSampleRate(): Int {
        for (rate in intArrayOf(96_000, 88_200, 48_000)) {
            val size = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            if (size > 0) return rate
        }
        return 0
    }

    override fun diagnostics(): String {
        val rate = chooseSampleRate()
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val external = devices.filter {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        val route = if (external.isEmpty()) {
            "no se detecta salida cableada/USB; evita el altavoz interno"
        } else {
            external.joinToString { it.productName.toString() }
        }
        val rateText = if (rate > 0) rate.toString() + " Hz" else "no compatible"
        return "Audio IR: " + rateText + " · " + route
    }
}
