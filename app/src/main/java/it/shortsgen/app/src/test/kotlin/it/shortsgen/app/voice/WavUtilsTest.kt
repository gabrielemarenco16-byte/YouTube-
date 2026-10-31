package it.shortsgen.app.voice

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WavUtilsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun wav(durationMs: Long, sampleRate: Int = 24000, channels: Int = 1, extraChunk: Boolean = false): ByteArray {
        val dataSize = (sampleRate * durationMs / 1000 * channels * 2).toInt()
        val header = WavUtils.header(sampleRate, channels, 16, dataSize)
        val data = ByteArray(dataSize) { (it % 7).toByte() }
        if (!extraChunk) return header + data
        // inserisce un chunk "LIST" prima di "data", come fanno alcuni motori
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        return header.copyOfRange(0, 36) + list + header.copyOfRange(36, 44) + data
    }

    private fun write(name: String, bytes: ByteArray): File = tmp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun parsesDuration() {
        val info = WavUtils.parse(wav(1500))
        assertEquals(24000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(1500, info.durationMs)
    }

    @Test
    fun skipsUnknownChunks() {
        assertEquals(800, WavUtils.parse(wav(800, extraChunk = true)).durationMs)
    }

    @Test
    fun handlesStreamingHeaderWithZeroSize() {
        val bytes = wav(1000)
        // dimensione data = 0 (scrittura in streaming)
        bytes[40] = 0; bytes[41] = 0; bytes[42] = 0; bytes[43] = 0
        assertEquals(1000, WavUtils.parse(bytes).durationMs)
    }

    @Test(expected = IOException::class)
    fun rejectsNonWav() {
        WavUtils.parse(ByteArray(64))
    }

    @Test
    fun concatAddsSilence() {
        val a = write("a.wav", wav(1000))
        val b = write("b.wav", wav(2000))
        val out = File(tmp.root, "out.wav")
        val segments = WavUtils.concat(listOf(a, b), listOf(250, 500), out)
        assertEquals(listOf(1250L, 2500L), segments)
        assertEquals(3750, WavUtils.durationMs(out))
    }

    @Test(expected = IOException::class)
    fun concatRejectsDifferentFormats() {
        val a = write("a.wav", wav(1000, sampleRate = 24000))
        val b = write("b.wav", wav(1000, sampleRate = 16000))
        WavUtils.concat(listOf(a, b), listOf(0, 0), File(tmp.root, "o.wav"))
    }
}
