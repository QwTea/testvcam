package com.example.virtualcam.xposed

import android.graphics.Bitmap
import android.view.Surface
import com.example.virtualcam.logVcam
import com.example.virtualcam.util.startSurfacePainter

// GREP: PREVIEW_CANVAS_OR_EGL
object GlSurfacePusher {
    fun start(surface: Surface, frameProvider: () -> Bitmap?, fps: Float): Thread {
        logVcam("GlSurfacePusher using Canvas fallback")
        return startSurfacePainter(surface, frameProvider, fps)
    }
}
