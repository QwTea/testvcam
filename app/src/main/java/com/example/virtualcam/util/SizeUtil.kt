package com.example.virtualcam.util

import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Surface
import android.util.Size
import com.example.virtualcam.logVcam
import kotlin.math.roundToInt

// GREP: SIZE_NEGOTIATION
object SizeUtil {
    fun negotiateTargetSize(
        requestedSize: Size,
        manualWidth: Int?,
        manualHeight: Int?
    ): Size {
        val width = manualWidth?.takeIf { it > 0 } ?: requestedSize.width
        val height = manualHeight?.takeIf { it > 0 } ?: requestedSize.height
        val evenWidth = if (width % 2 == 0) width else width + 1
        val evenHeight = if (height % 2 == 0) height else height + 1
        return Size(evenWidth, evenHeight)
    }

    fun scaleToFit(source: Size, targetWidth: Int, targetHeight: Int): Size {
        if (source.width == 0 || source.height == 0) return Size(targetWidth, targetHeight)
        val scale = minOf(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height
        )
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Size(width, height)
    }
}

// GREP: SURFACE_DETECT_IR
fun isImageReaderSurface(surface: Surface): Boolean {
    val descriptor = surface.toString()
    return descriptor.contains("ImageReader") || descriptor.contains("ImageWriter")
}

// GREP: SURFACE_DETECT_PREVIEW
fun isPreviewSurface(surface: Surface): Boolean {
    return surface.isValid && !isImageReaderSurface(surface)
}

// GREP: DUMMY_SURFACE
fun makeDummySurface(): Surface {
    val texture = SurfaceTexture(0)
    texture.setDefaultBufferSize(16, 16)
    return Surface(texture)
}

// GREP: SURFACE_PAINTER
fun startSurfacePainter(
    surface: Surface,
    frameProvider: () -> android.graphics.Bitmap?,
    fps: Float,
    clearColor: Int = Color.BLACK
): Thread {
    val frameInterval = if (fps > 0f) (1000f / fps).toLong().coerceAtLeast(10L) else 33L
    val painter = Thread {
        try {
            while (!Thread.currentThread().isInterrupted && surface.isValid) {
                val canvas = try {
                    surface.lockCanvas(null)
                } catch (err: Exception) {
                    logVcam("SurfacePainter lock failed: ${err.message}")
                    break
                }
                try {
                    canvas.drawColor(clearColor)
                    val bitmap = frameProvider()
                    if (bitmap != null && !bitmap.isRecycled) {
                        val dest = Rect(0, 0, canvas.width, canvas.height)
                        canvas.drawBitmap(bitmap, null, dest, null)
                    }
                } finally {
                    try {
                        surface.unlockCanvasAndPost(canvas)
                    } catch (err: Exception) {
                        logVcam("SurfacePainter unlock failed: ${err.message}")
                    }
                }
                try {
                    Thread.sleep(frameInterval)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } catch (err: Exception) {
            logVcam("SurfacePainter loop error: ${err.message}")
        }
    }
    painter.name = "VirtualCamSurfacePainter"
    painter.isDaemon = true
    painter.start()
    return painter
}
