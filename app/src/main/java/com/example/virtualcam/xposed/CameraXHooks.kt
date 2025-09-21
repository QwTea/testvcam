package com.example.virtualcam.xposed

import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.view.Surface
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import com.example.virtualcam.logVcam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// GREP: HOOK_CAMERAX
object CameraXHooks {
    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (installed.getAndSet(true)) return
        hookImageAnalysis(classLoader)
        hookImageCapture(classLoader)
        hookSurfaceRequest(classLoader)
    }

    private fun hookImageAnalysis(classLoader: ClassLoader) {
        try {
            val analysisClass = XposedHelpers.findClass("androidx.camera.core.ImageAnalysis", classLoader)
            val analyzerInterface = Class.forName("androidx.camera.core.ImageAnalysis$Analyzer", false, classLoader)
            XposedHelpers.findAndHookMethod(
                analysisClass,
                "setAnalyzer",
                Executor::class.java,
                analyzerInterface,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val originalAnalyzer = param.args[1]
                        val proxy = Proxy.newProxyInstance(
                            classLoader,
                            arrayOf(analyzerInterface)
                        ) { _, method, args ->
                            if (method.name == "analyze" && args != null && args.isNotEmpty()) {
                                val imageProxy = args[0] as ImageProxy
                                FakeFrameInjector.warmUp()
                                val data = FakeFrameInjector.provideNv21(imageProxy.width, imageProxy.height)
                                val fake = if (data != null) {
                                    VirtualCamImageProxy.fromNv21(
                                        imageProxy.width,
                                        imageProxy.height,
                                        imageProxy.imageInfo.timestamp,
                                        data
                                    )
                                } else {
                                    imageProxy
                                }
                                DiagnosticsState.update {
                                    it.copy(
                                        activePath = "CameraX",
                                        previewSize = "${imageProxy.width}x${imageProxy.height}",
                                        frameFormat = "YUV_420_888"
                                    )
                                }
                                imageProxy.close()
                                method.invoke(originalAnalyzer, fake)
                                null
                            } else {
                                method.invoke(originalAnalyzer, *(args ?: emptyArray()))
                            }
                        }
                        param.args[1] = proxy
                    }
                }
            )
        } catch (err: Throwable) {
            logVcam("CameraX ImageAnalysis hook failed: ${err.message}")
        }
    }

    private fun hookImageCapture(classLoader: ClassLoader) {
        try {
            val captureClass = XposedHelpers.findClass("androidx.camera.core.ImageCapture", classLoader)
            val callbackInterface = Class.forName("androidx.camera.core.ImageCapture$OnImageCapturedCallback", false, classLoader)
            XposedHelpers.findAndHookMethod(
                captureClass,
                "takePicture",
                Executor::class.java,
                callbackInterface,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callback = param.args[1]
                        val method = callbackInterface.getMethod("onCaptureSuccess", ImageProxy::class.java)
                        val proxy = Proxy.newProxyInstance(
                            classLoader,
                            arrayOf(callbackInterface)
                        ) { _, methodInvoked, args ->
                            if (methodInvoked.name == "onCaptureSuccess") {
                                FakeFrameInjector.warmUp()
                                val settings = FakeFrameInjector.currentSettings()
                                val width =  settings?.manualWidth ?: 1280
                                val height = settings?.manualHeight ?: 720
                                val data = FakeFrameInjector.provideNv21(width, height)
                                val fake = if (data != null) {
                                    VirtualCamImageProxy.fromNv21(width, height, System.currentTimeMillis() * 1000, data)
                                } else {
                                    args?.get(0) as? ImageProxy
                                }
                                DiagnosticsState.update {
                                    it.copy(activePath = "CameraX", previewSize = "${width}x${height}")
                                }
                                method.invoke(callback, fake)
                                null
                            } else if (methodInvoked.name == "onError") {
                                methodInvoked.invoke(callback, *(args ?: emptyArray()))
                            } else {
                                methodInvoked.invoke(callback, *(args ?: emptyArray()))
                            }
                        }
                        param.args[1] = proxy
                    }
                }
            )
        } catch (err: Throwable) {
            logVcam("CameraX ImageCapture hook failed: ${err.message}")
        }
    }

    private fun hookSurfaceRequest(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "androidx.camera.core.SurfaceRequest",
                classLoader,
                "provideSurface",
                Surface::class.java,
                Executor::class.java,
                Runnable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        FakeFrameInjector.warmUp()
                        val settings = FakeFrameInjector.currentSettings()
                        if (settings?.injectCam2Preview != true) return
                        val originalSurface = param.args[0] as Surface
                        val dummy = com.example.virtualcam.util.makeDummySurface()
                        com.example.virtualcam.util.startSurfacePainter(originalSurface, {
                            FakeFrameInjector.acquireBitmap(1280, 720)
                        }, settings.fps)
                        DiagnosticsState.update { it.copy(activePath = "CameraX", frameFormat = "Preview", requestedFps = settings.fps) }
                        param.args[0] = dummy
                    }
                }
            )
        } catch (err: Throwable) {
            logVcam("CameraX SurfaceRequest hook failed: ${err.message}")
        }
    }
}

private class VirtualCamImageProxy(
    private val width: Int,
    private val height: Int,
    private val timestampUs: Long,
    private val planes: Array<ImageProxy.PlaneProxy>
) : ImageProxy {
    override fun close() {}

    override fun getWidth(): Int = width

    override fun getHeight(): Int = height

    override fun getImageInfo(): ImageProxy.ImageInfo = object : ImageProxy.ImageInfo {
        override fun getTimestamp(): Long = timestampUs
        override fun getTagBundle(): androidx.camera.core.impl.TagBundle = androidx.camera.core.impl.TagBundle.emptyBundle()
        override fun getSensorToBufferTransformMatrix(): android.graphics.Matrix = android.graphics.Matrix()
        override fun getRotationDegrees(): Int = 0
    }

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = planes

    override fun getFormat(): Int = ImageFormat.YUV_420_888

    override fun getCropRect(): Rect = Rect(0, 0, width, height)

    override fun setCropRect(rect: Rect?) {}

    companion object {
        fun fromNv21(width: Int, height: Int, timestampUs: Long, data: ByteArray): ImageProxy {
            val ySize = width * height
            val uvSize = ySize / 2
            val yBuffer = ByteBuffer.allocateDirect(ySize)
            yBuffer.put(data, 0, ySize)
            val uBuffer = ByteBuffer.allocateDirect(uvSize / 2)
            val vBuffer = ByteBuffer.allocateDirect(uvSize / 2)
            var offset = ySize
            while (offset < data.size) {
                val v = data[offset]
                val u = if (offset + 1 < data.size) data[offset + 1] else 0
                vBuffer.put(v)
                uBuffer.put(u)
                offset += 2
            }
            yBuffer.flip()
            uBuffer.flip()
            vBuffer.flip()
            val planes = arrayOf(
                SimplePlaneProxy(yBuffer.asReadOnlyBuffer(), width, 1),
                SimplePlaneProxy(uBuffer.asReadOnlyBuffer(), width / 2, 1),
                SimplePlaneProxy(vBuffer.asReadOnlyBuffer(), width / 2, 1)
            )
            return VirtualCamImageProxy(width, height, timestampUs, planes)
        }
    }
}

private class SimplePlaneProxy(
    private val buffer: ByteBuffer,
    private val rowStride: Int,
    private val pixelStride: Int
) : ImageProxy.PlaneProxy {
    override fun getBuffer(): ByteBuffer = buffer.duplicate()

    override fun getRowStride(): Int = rowStride

    override fun getPixelStride(): Int = pixelStride
}
