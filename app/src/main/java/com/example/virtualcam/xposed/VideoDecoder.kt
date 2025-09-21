package com.example.virtualcam.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.virtualcam.logVcam
import com.example.virtualcam.prefs.VideoMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

// GREP: DECODER_CODEC_OR_MMR
class VideoDecoder(
    private val context: Context,
    private val uri: Uri,
    private val mode: VideoMode,
    private val targetFps: Float
) {
    private val started = AtomicBoolean(false)
    private var retriever: MediaMetadataRetriever? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var bufferInfo: MediaCodec.BufferInfo? = null
    private var durationUs: Long = 0
    private var stepUs: Long = 33_000L
    private var positionUs: Long = 0
    private var sawInputEos = false
    private var sawOutputEos = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        when (mode) {
            VideoMode.MMR -> setupRetriever()
            VideoMode.CODEC -> if (!setupCodec()) setupRetriever()
        }
    }

    fun nextBitmap(): Bitmap? {
        return when {
            codec != null -> codecFrame()
            retriever != null -> mmrFrame()
            else -> null
        }
    }

    fun release() {
        retriever?.release()
        retriever = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        codec = null
        extractor?.release()
        extractor = null
        bufferInfo = null
        started.set(false)
    }

    private fun setupRetriever() {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        stepUs = computeStepUs()
        positionUs = 0
        this.retriever = retriever
        logVcam("VideoDecoder using MediaMetadataRetriever durationUs=$durationUs stepUs=$stepUs")
    }

    private fun setupCodec(): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return false
            }
            extractor.selectTrack(trackIndex)
            if (!format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
            val codec = if (codecName != null) MediaCodec.createByCodecName(codecName) else MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            this.codec = codec
            this.extractor = extractor
            bufferInfo = MediaCodec.BufferInfo()
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs == 0L) {
                val tmp = MediaMetadataRetriever()
                tmp.setDataSource(context, uri)
                durationUs = (tmp.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
                tmp.release()
            }
            stepUs = computeStepUs()
            positionUs = 0
            logVcam("VideoDecoder using MediaCodec durationUs=$durationUs stepUs=$stepUs")
            true
        } catch (err: Exception) {
            logVcam("VideoDecoder setupCodec error: ${err.message}")
            false
        }
    }

    private fun computeStepUs(): Long {
        return if (targetFps > 0f) (1_000_000f / targetFps).toLong().coerceAtLeast(15_000L) else 33_000L
    }

    private fun mmrFrame(): Bitmap? {
        val retriever = retriever ?: return null
        val bitmap = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)
        positionUs += stepUs
        if (durationUs > 0 && positionUs > durationUs) {
            positionUs = 0
        }
        return bitmap
    }

    private fun codecFrame(): Bitmap? {
        val codec = codec ?: return mmrFrame()
        val extractor = extractor ?: return mmrFrame()
        val info = bufferInfo ?: return mmrFrame()
        var attempts = 0
        var bitmap: Bitmap? = null
        while (attempts < 8 && bitmap == null) {
            if (!sawInputEos) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: continue
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outputIndex >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEos = true
                }
                val image = codec.getOutputImage(outputIndex)
                if (image != null) {
                    bitmap = imageToBitmap(image)
                    image.close()
                }
                codec.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                logVcam("VideoDecoder output format: ${codec.outputFormat}")
            }
            if (sawOutputEos) {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                sawInputEos = false
                sawOutputEos = false
            }
            attempts++
        }
        if (bitmap == null) {
            bitmap = mmrFrame()
        }
        return bitmap
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val output = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, output)
        val bytes = output.toByteArray()
        output.close()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        var outputPos = 0
        for (row in 0 until height) {
            var col = 0
            while (col < width) {
                val yIndex = row * yPlane.rowStride + col * yPlane.pixelStride
                nv21[outputPos++] = yPlane.buffer.get(yIndex)
                col++
            }
        }
        var uvPos = ySize
        for (row in 0 until height / 2) {
            var col = 0
            while (col < width / 2) {
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                nv21[uvPos++] = vPlane.buffer.get(vIndex)
                nv21[uvPos++] = uPlane.buffer.get(uIndex)
                col++
            }
        }
        return nv21
    }
}
