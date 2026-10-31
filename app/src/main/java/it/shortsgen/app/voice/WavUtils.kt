package it.shortsgen.app.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
    val dataSize: Int,
) {
    val bytesPerFrame: Int get() = channels * bitsPerSample / 8
    val byteRate: Int get() = sampleRate * bytesPerFrame
    val durationMs: Long get() = if (byteRate == 0) 0 else dataSize * 1000L / byteRate

    fun sameFormatAs(other: WavInfo) =
        sampleRate == other.sampleRate && channels == other.channels && bitsPerSample == other.bitsPerSample
}

/** Lettura/scrittura di file WAV PCM (formato prodotto da TextToSpeech.synthesizeToFile). */
object WavUtils {

    fun parse(bytes: ByteArray): WavInfo {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw IOException("Il file audio non è un WAV valido")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var fmtFound = false
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val format = buf.getShort(body).toInt() and 0xFFFF
                    if (format != 1 && format != 0xFFFE) throw IOException("Formato audio WAV non supportato ($format)")
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                    fmtFound = true
                }
                "data" -> {
                    if (!fmtFound) throw IOException("WAV senza intestazione fmt")
                    val available = bytes.size - body
                    // Alcuni motori scrivono 0 o 0xFFFFFFFF come dimensione (scrittura in streaming).
                    val dataSize = if (size <= 0 || size > available) available else size
                    val info = WavInfo(sampleRate, channels, bits, body, dataSize)
                    val aligned = dataSize - dataSize % info.bytesPerFrame.coerceAtLeast(1)
                    return info.copy(dataSize = aligned)
                }
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        throw IOException("WAV senza dati audio")
    }

    fun parse(file: File): WavInfo = parse(file.readBytes())

    /**
     * Inizio e fine (ms) della voce: salta il silenzio iniziale e finale che i motori
     * di sintesi aggiungono, così i sottotitoli partono insieme alla voce.
     */
    fun voiceBounds(bytes: ByteArray, windowMs: Int = 20): Pair<Long, Long> {
        val info = parse(bytes)
        if (info.bitsPerSample != 16 || info.byteRate == 0) return 0L to info.durationMs
        val buf = ByteBuffer.wrap(bytes, info.dataOffset, info.dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val samplesPerWindow = (info.sampleRate * windowMs / 1000).coerceAtLeast(1) * info.channels
        val totalSamples = info.dataSize / 2
        val rms = ArrayList<Double>()
        var i = 0
        while (i < totalSamples) {
            val n = minOf(samplesPerWindow, totalSamples - i)
            var sum = 0.0
            for (k in 0 until n) {
                val v = buf.getShort(info.dataOffset + (i + k) * 2).toDouble()
                sum += v * v
            }
            rms += Math.sqrt(sum / n)
            i += n
        }
        val peak = rms.maxOrNull() ?: return 0L to info.durationMs
        if (peak < 200) return 0L to info.durationMs // tutto silenzio: nessuna informazione utile
        val threshold = maxOf(peak * 0.06, 150.0)
        val first = rms.indexOfFirst { it >= threshold }
        val last = rms.indexOfLast { it >= threshold }
        val start = (first.toLong() * windowMs).coerceAtMost(info.durationMs)
        val end = ((last + 1).toLong() * windowMs).coerceIn(start, info.durationMs)
        return start to end
    }

    fun durationMs(file: File): Long = parse(file).durationMs

    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataSize: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
    }

    fun silenceBytes(info: WavInfo, durationMs: Long): Int {
        val frames = info.sampleRate.toLong() * durationMs / 1000L
        return (frames * info.bytesPerFrame).toInt()
    }

    /**
     * Unisce più WAV con lo stesso formato, aggiungendo dopo ciascuno il silenzio indicato.
     * Restituisce la durata finale (ms) di ogni segmento, silenzio compreso.
     */
    fun concat(inputs: List<File>, silenceAfterMs: List<Long>, output: File): List<Long> {
        require(inputs.isNotEmpty())
        require(silenceAfterMs.size == inputs.size)
        val parsed = inputs.map { f -> f.readBytes().let { it to parse(it) } }
        val first = parsed.first().second
        parsed.forEachIndexed { i, (_, info) ->
            if (!info.sameFormatAs(first)) throw IOException("Formato audio diverso nella scena ${i + 1}")
        }
        val pcm = ByteArrayOutputStream()
        val segmentDurations = ArrayList<Long>()
        parsed.forEachIndexed { i, (bytes, info) ->
            pcm.write(bytes, info.dataOffset, info.dataSize)
            val silence = silenceBytes(first, silenceAfterMs[i])
            pcm.write(ByteArray(silence))
            segmentDurations += (info.dataSize + silence) * 1000L / first.byteRate
        }
        val data = pcm.toByteArray()
        output.outputStream().use {
            it.write(header(first.sampleRate, first.channels, first.bitsPerSample, data.size))
            it.write(data)
        }
        return segmentDurations
    }
}
