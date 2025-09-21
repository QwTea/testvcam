package com.example.virtualcam.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import com.example.virtualcam.logVcam
import com.example.virtualcam.prefs.ScaleMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

// GREP: BITMAP_TRANSFORM
fun scaleBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int, scaleMode: ScaleMode): Bitmap {
    if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
        return bitmap
    }
    val result = when (scaleMode) {
        ScaleMode.FIT -> {
            val widthRatio = targetWidth.toFloat() / bitmap.width
            val heightRatio = targetHeight.toFloat() / bitmap.height
            val ratio = minOf(widthRatio, heightRatio)
            val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        ScaleMode.CENTER_CROP -> {
            val widthRatio = targetWidth.toFloat() / bitmap.width
            val heightRatio = targetHeight.toFloat() / bitmap.height
            val ratio = maxOf(widthRatio, heightRatio)
            val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }
    if (scaleMode == ScaleMode.CENTER_CROP && (result.width != targetWidth || result.height != targetHeight)) {
        val xOffset = ((result.width - targetWidth) / 2).coerceAtLeast(0)
        val yOffset = ((result.height - targetHeight) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(result, xOffset, yOffset, targetWidth, targetHeight)
    }
    return result
}

fun applyOrientationAndMirror(bitmap: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
    val matrix = Matrix()
    if (rotation != 0) {
        matrix.postRotate(rotation.toFloat())
    }
    if (mirror) {
        matrix.postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

// GREP: YUV_CONVERTERS
// GREP: YUV_CONVERTERS_IMPL
fun bitmapToNV21(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val argb = IntArray(width * height)
    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
    val yuv = ByteArray(width * height * 3 / 2)
    var yIndex = 0
    var uvIndex = width * height
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = argb[y * width + x]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
            val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
            yuv[yIndex++] = yValue.coerceIn(0, 255).toByte()
            if (y % 2 == 0 && x % 2 == 0) {
                yuv[uvIndex++] = vValue.coerceIn(0, 255).toByte()
                yuv[uvIndex++] = uValue.coerceIn(0, 255).toByte()
            }
        }
    }
    return yuv
}

fun writeBitmapIntoYUV420(bitmap: Bitmap, image: Image) {
    val width = image.width
    val height = image.height
    val nv21 = bitmapToNV21(
        if (bitmap.width == width && bitmap.height == height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
    )
    val planes = image.planes
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    yPlane.buffer.position(0)
    copyPlane(nv21, 0, width, height, yPlane.rowStride, yPlane.pixelStride, yPlane.buffer)

    val uvOffset = width * height
    val rowStride = uPlane.rowStride
    val pixelStrideU = uPlane.pixelStride
    val pixelStrideV = vPlane.pixelStride
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    uBuffer.position(0)
    vBuffer.position(0)
    val halfHeight = height / 2
    val halfWidth = width / 2
    for (row in 0 until halfHeight) {
        for (col in 0 until halfWidth) {
            val vuIndex = uvOffset + row * width + col * 2
            val vValue = nv21[vuIndex]
            val uValue = nv21[vuIndex + 1]
            uBuffer.position(row * rowStride + col * pixelStrideU)
            uBuffer.put(uValue)
            vBuffer.position(row * vPlane.rowStride + col * pixelStrideV)
            vBuffer.put(vValue)
        }
    }
}

private fun copyPlane(
    source: ByteArray,
    offset: Int,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    buffer: ByteBuffer
) {
    var index = offset
    for (row in 0 until height) {
        val rowPosition = row * rowStride
        for (col in 0 until width) {
            val destIndex = rowPosition + col * pixelStride
            buffer.put(destIndex, source[index])
            index++
        }
    }
}

fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
    val stream = ByteArrayOutputStream()
    return try {
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        stream.toByteArray()
    } catch (err: Exception) {
        logVcam("bitmapToJpeg error: ${err.message}")
        ByteArray(0)
    } finally {
        stream.close()
    }
}
