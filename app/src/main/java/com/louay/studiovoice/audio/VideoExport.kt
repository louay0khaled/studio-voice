package com.louay.studiovoice.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Build a new MP4: video track copied bit-for-bit, audio replaced by the
 * enhanced PCM encoded as AAC. Two phases because MediaMuxer needs every
 * track registered before start(): (1) encode enhanced audio to a temp .m4a
 * via [AudioIo.writeM4a]; (2) mux video + that audio, interleaved by PTS.
 */
object VideoExport {

    fun exportMp4(context: Context, sourceUri: Uri, enhancedWav: File, outFile: File) {
        val tmpAudio = File(outFile.parentFile, "enhanced_audio_tmp.m4a")
        check(AacExport.encode(enhancedWav, tmpAudio)) { "AAC encode failed" }
        try {
            muxVideoWithAudio(context, sourceUri, tmpAudio, outFile)
        } finally {
            tmpAudio.delete()
        }
    }

    private fun muxVideoWithAudio(context: Context, sourceUri: Uri, audioFile: File, outFile: File) {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        try {
            videoEx.setDataSource(context, sourceUri, null)
            var vTrack = -1; var vFormat: MediaFormat? = null
            for (i in 0 until videoEx.trackCount) {
                val f = videoEx.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    vTrack = i; vFormat = f; break
                }
            }
            require(vTrack >= 0 && vFormat != null) { "لا يوجد مسار فيديو في هذا الملف" }

            audioEx.setDataSource(audioFile.absolutePath)
            var aTrack = -1; var aFormat: MediaFormat? = null
            for (i in 0 until audioEx.trackCount) {
                val f = audioEx.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    aTrack = i; aFormat = f; break
                }
            }
            require(aTrack >= 0 && aFormat != null) { "فشل إنشاء المسار الصوتي المحسّن" }

            videoEx.selectTrack(vTrack)
            audioEx.selectTrack(aTrack)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outV = muxer.addTrack(vFormat)
                val outA = muxer.addTrack(aFormat)
                muxer.start()
                val buf = ByteBuffer.allocate(1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var vDone = false; var aDone = false
                while (!vDone || !aDone) {
                    val vTime = if (vDone) Long.MAX_VALUE else videoEx.sampleTime
                    val aTime = if (aDone) Long.MAX_VALUE else audioEx.sampleTime
                    if (!vDone && (aDone || vTime <= aTime)) {
                        val size = videoEx.readSampleData(buf, 0)
                        if (size < 0) { vDone = true } else {
                            info.offset = 0; info.size = size
                            info.presentationTimeUs = videoEx.sampleTime
                            info.flags = videoEx.sampleFlags
                            muxer.writeSampleData(outV, buf, info)
                            videoEx.advance()
                        }
                    } else if (!aDone) {
                        val size = audioEx.readSampleData(buf, 0)
                        if (size < 0) { aDone = true } else {
                            info.offset = 0; info.size = size
                            info.presentationTimeUs = audioEx.sampleTime
                            info.flags = audioEx.sampleFlags
                            muxer.writeSampleData(outA, buf, info)
                            audioEx.advance()
                        }
                    }
                }
            } finally {
                try { muxer.stop() } catch (_: Exception) {}
                muxer.release()
            }
        } finally {
            videoEx.release()
            audioEx.release()
        }
    }
}
