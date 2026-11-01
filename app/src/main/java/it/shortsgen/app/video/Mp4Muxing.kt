package it.shortsgen.app.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Unisce la traccia video di un file e la traccia audio di un altro in un unico MP4. */
object Mp4Muxing {

    fun mux(videoFile: File, audioFile: File, output: File) {
        output.delete()
        val video = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audio = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vIndex = selectTrack(video, "video/") ?: throw IOException("Traccia video mancante")
            val aIndex = selectTrack(audio, "audio/") ?: throw IOException("Traccia audio mancante")
            val vFormat = video.getTrackFormat(vIndex)
            val outV = muxer.addTrack(vFormat)
            val outA = muxer.addTrack(audio.getTrackFormat(aIndex))
            if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION))
            }
            muxer.start()

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            // Scrive i campioni alternando le tracce in ordine di tempo.
            while (!videoDone || !audioDone) {
                val vTime = if (videoDone) Long.MAX_VALUE else video.sampleTime
                val aTime = if (audioDone) Long.MAX_VALUE else audio.sampleTime
                if (!videoDone && vTime < 0) { videoDone = true; continue }
                if (!audioDone && aTime < 0) { audioDone = true; continue }
                val useVideo = vTime <= aTime
                val extractor = if (useVideo) video else audio
                val track = if (useVideo) outV else outA
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                info.set(0, size, extractor.sampleTime, toCodecFlags(extractor.sampleFlags))
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            video.release()
            audio.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) {
                extractor.selectTrack(i)
                return i
            }
        }
        return null
    }

    private fun toCodecFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        return flags
    }
}
