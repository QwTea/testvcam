package com.example.virtualcam.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.Image
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.example.virtualcam.logVcam
import com.example.virtualcam.prefs.ModulePrefs
import com.example.virtualcam.prefs.ModuleSettings
import com.example.virtualcam.prefs.OrientationOption
import com.example.virtualcam.prefs.SourceType
import com.example.virtualcam.util.ExifUtil
import com.example.virtualcam.util.SizeUtil
import com.example.virtualcam.util.applyOrientationAndMirror
import com.example.virtualcam.util.bitmapToJpeg
import com.example.virtualcam.util.bitmapToNV21
import com.example.virtualcam.util.scaleBitmap
import com.example.virtualcam.util.writeBitmapIntoYUV420
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// GREP: INJECT_PREVIEW_AND_FRAMES
object FakeFrameInjector {
    private val legacyCallbacks = ConcurrentHashMap<Camera.PreviewCallback, Camera.PreviewCallback>()
    private val legacySessions = ConcurrentHashMap<Camera, LegacyCameraSession>()
    private val painterThreads = ConcurrentHashMap<Surface, Thread>()
    private val installedLegacy = AtomicBoolean(false)
    private val installedCamera2 = AtomicBoolean(false)
    private val suppressPreviewTexture = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private var appContext: Context? = null
    private var lastSettingsSignature: String? = null
    private var lastSettings: ModuleSettings? = null
    private var baseBitmap: Bitmap? = null
    private var orientedBitmap: Bitmap? = null
    private var exifRotation: Int = 0
    private var exifMirror: Boolean = false
    private var videoDecoder: VideoDecoder? = null
    private var lastFrameTimestampNs: Long = 0

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun installHooks(classLoader: ClassLoader) {
        installLegacyHooks(classLoader)
        installCamera2Hooks(classLoader)
        installImageHooks(classLoader)
    }

    private fun installLegacyHooks(classLoader: ClassLoader) {
        if (installedLegacy.getAndSet(true)) return
        try {
            val cameraClass = XposedHelpers.findClass("android.hardware.Camera", classLoader)
            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallback", Camera.PreviewCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as Camera
                        val original = param.args[0] as? Camera.PreviewCallback
                        if (original != null) {
                            param.args[0] = wrapPreviewCallback(camera, original)
                        }
                    }
                })

            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewCallbackWithBuffer", Camera.PreviewCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as Camera
                        val original = param.args[0] as? Camera.PreviewCallback
                        if (original != null) {
                            param.args[0] = wrapPreviewCallback(camera, original)
                        }
                    }
                })

            XposedHelpers.findAndHookMethod(cameraClass, "setOneShotPreviewCallback", Camera.PreviewCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as Camera
                        val original = param.args[0] as? Camera.PreviewCallback
                        if (original != null) {
                            param.args[0] = wrapPreviewCallback(camera, original)
                        }
                    }
                })

            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewTexture", SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (suppressPreviewTexture.get()) return
                        val camera = param.thisObject as Camera
                        val texture = param.args[0] as? SurfaceTexture
                        val session = legacySessions.getOrPut(camera) { LegacyCameraSession() }
                        if (texture == null) {
                            clearLegacySession(session)
                            return
                        }
                        refreshSettings()
                        val targetSurface = if (session.ownsPreviewSurface && session.attachedSurfaceTexture === texture && session.previewSurface != null) {
                            session.previewSurface!!
                        } else {
                            Surface(texture).also { session.attachedSurfaceTexture = texture }
                        }
                        session.attachedSurfaceTexture = texture
                        session.updatePreviewSurface(targetSurface, ownsSurface = true)
                        param.args[0] = session.obtainDummySurfaceTexture()
                        markLegacyPathActive(camera)
                    }
                })

            XposedHelpers.findAndHookMethod(cameraClass, "setPreviewDisplay", SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as Camera
                        val holder = param.args[0] as? SurfaceHolder
                        val session = legacySessions.getOrPut(camera) { LegacyCameraSession() }
                        if (holder == null) {
                            clearLegacySession(session)
                            return
                        }
                        val surface = holder.surface
                        if (surface == null || !surface.isValid) {
                            clearLegacySession(session)
                            return
                        }
                        refreshSettings()
                        session.attachedSurfaceTexture = null
                        session.updatePreviewSurface(surface, ownsSurface = false)
                        markLegacyPathActive(camera)
                        val dummy = session.obtainDummySurfaceTexture()
                        var replaced = false
                        try {
                            withSuppressedPreviewTexture {
                                camera.setPreviewTexture(dummy)
                            }
                            replaced = true
                        } catch (err: Throwable) {
                            logVcam("Legacy setPreviewDisplay swap failed: ${err.message}")
                        }
                        if (replaced) {
                            param.result = null
                        }
                    }
                })

            XposedHelpers.findAndHookMethod(
                cameraClass,
                "takePicture",
                Camera.ShutterCallback::class.java,
                Camera.PictureCallback::class.java,
                Camera.PictureCallback::class.java,
                Camera.PictureCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as Camera
                        val jpeg = param.args[3] as? Camera.PictureCallback
                        if (jpeg != null) {
                            param.args[3] = Camera.PictureCallback { data, cam ->
                                refreshSettings()
                                val pictureSize = cam?.parameters?.pictureSize
                                val width = pictureSize?.width ?: 1280
                                val height = pictureSize?.height ?: 720
                                val frame = provideJpeg(width, height)
                                logVcam("Legacy takePicture override: ${frame?.size ?: 0} bytes")
                                jpeg.onPictureTaken(frame ?: data, cam)
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(cameraClass, "startPreview", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as Camera
                    val session = legacySessions[camera] ?: return
                    val surface = session.previewSurface
                    if (surface != null && surface.isValid) {
                        refreshSettings()
                        startPainter(surface, session)
                        markLegacyPathActive(camera)
                    }
                }
            })

            XposedHelpers.findAndHookMethod(cameraClass, "stopPreview", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as Camera
                    legacySessions[camera]?.let { pauseLegacyPreview(it) }
                }
            })

            XposedHelpers.findAndHookMethod(cameraClass, "release", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as Camera
                    legacySessions.remove(camera)?.let { clearLegacySession(it) }
                }
            })
        } catch (err: Throwable) {
            logVcam("Legacy hook install failed: ${err.message}")
        }
    }

    private fun installCamera2Hooks(classLoader: ClassLoader) {
        if (installedCamera2.getAndSet(true)) return
        try {
            val stateCallbackClass = Class.forName("android.hardware.camera2.CameraCaptureSession\$StateCallback", false, classLoader)
            val cameraDeviceClass = XposedHelpers.findClass("android.hardware.camera2.CameraDevice", classLoader)
            XposedHelpers.findAndHookMethod(
                cameraDeviceClass,
                "createCaptureSession",
                MutableList::class.java,
                stateCallbackClass,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshSettings()
                        val settings = lastSettings ?: return
                        if (!settings.injectCam2Preview) return
                        val surfaces = param.args[0] as MutableList<Surface>
                        var replaced = 0
                        for (i in surfaces.indices) {
                            val surface = surfaces[i]
                            if (isSurfaceEligible(surface)) {
                                surfaces[i] = com.example.virtualcam.util.makeDummySurface()
                                startPainter(surface)
                                replaced++
                            }
                        }
                        if (replaced > 0) {
                            logVcam("createCaptureSession: replaced $replaced preview surface(s) with dummy")
                            DiagnosticsState.update { it.copy(activePath = "Camera2") }
                        }
                    }
                }
            )
        } catch (err: Throwable) {
            logVcam("Camera2 hook install failed: ${err.message}")
        }
    }

    private fun installImageHooks(classLoader: ClassLoader) {
        try {
            val imageReaderClass = XposedHelpers.findClass("android.media.ImageReader", classLoader)
            XposedHelpers.findAndHookMethod(imageReaderClass, "acquireNextImage", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val image = param.result as? Image ?: return
                    refreshSettings()
                    provideImage(image)
                }
            })
            XposedHelpers.findAndHookMethod(imageReaderClass, "acquireLatestImage", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val image = param.result as? Image ?: return
                    refreshSettings()
                    provideImage(image)
                }
            })
        } catch (err: Throwable) {
            logVcam("ImageReader hook install failed: ${err.message}")
        }
    }

    private fun isSurfaceEligible(surface: Surface): Boolean {
        return com.example.virtualcam.util.isPreviewSurface(surface) && !com.example.virtualcam.util.isImageReaderSurface(surface)
    }

    private fun startPainter(targetSurface: Surface, session: LegacyCameraSession? = null) {
        painterThreads.remove(targetSurface)?.interrupt()
        val fps = lastSettings?.fps ?: 30f
        val thread = GlSurfacePusher.start(targetSurface, {
            val width = session?.previewSize?.width?.takeIf { it > 0 }
                ?: lastSettings?.manualWidth?.takeIf { it > 0 }
                ?: 1280
            val height = session?.previewSize?.height?.takeIf { it > 0 }
                ?: lastSettings?.manualHeight?.takeIf { it > 0 }
                ?: 720
            fetchBitmap(width, height)
        }, fps)
        painterThreads[targetSurface] = thread
    }

    private inline fun <T> withSuppressedPreviewTexture(block: () -> T): T {
        val previous = suppressPreviewTexture.get()
        suppressPreviewTexture.set(true)
        return try {
            block()
        } finally {
            suppressPreviewTexture.set(previous)
        }
    }

    private fun stopPainter(surface: Surface?) {
        if (surface == null) return
        painterThreads.remove(surface)?.interrupt()
    }

    private fun LegacyCameraSession.updatePreviewSurface(targetSurface: Surface, ownsSurface: Boolean) {
        val previousSurface = previewSurface
        val previousOwned = ownsPreviewSurface
        if (previousSurface !== targetSurface) {
            stopPainter(previousSurface)
            if (previousOwned) {
                runCatching { previousSurface?.release() }
            }
            previewSurface = targetSurface
        }
        ownsPreviewSurface = ownsSurface
        startPainter(targetSurface, this)
    }

    private fun LegacyCameraSession.obtainDummySurfaceTexture(): SurfaceTexture {
        val existing = dummySurfaceTexture
        if (existing != null) {
            return existing
        }
        val texture = SurfaceTexture(0)
        texture.setDefaultBufferSize(16, 16)
        dummySurfaceTexture = texture
        return texture
    }

    private fun pauseLegacyPreview(session: LegacyCameraSession) {
        stopPainter(session.previewSurface)
    }

    private fun clearLegacySession(session: LegacyCameraSession) {
        pauseLegacyPreview(session)
        if (session.ownsPreviewSurface) {
            runCatching { session.previewSurface?.release() }
        }
        session.previewSurface = null
        session.ownsPreviewSurface = false
        session.attachedSurfaceTexture = null
        session.dummySurfaceTexture?.let { runCatching { it.release() } }
        session.dummySurfaceTexture = null
        session.previewSize = Size(0, 0)
    }

    private fun markLegacyPathActive(camera: Camera) {
        val previewSize = runCatching { camera.parameters?.previewSize }.getOrNull()
        if (previewSize != null && previewSize.width > 0 && previewSize.height > 0) {
            recordLegacySession(camera, previewSize.width, previewSize.height)
        } else {
            DiagnosticsState.update {
                it.copy(
                    activePath = "Legacy",
                    frameFormat = lastSettings?.format?.storageValue ?: "NV21",
                    requestedFps = lastSettings?.fps ?: 30f
                )
            }
        }
    }

    private fun wrapPreviewCallback(camera: Camera, original: Camera.PreviewCallback): Camera.PreviewCallback {
        val cached = legacyCallbacks[original]
        if (cached != null) {
            return cached
        }
        val wrapper = Camera.PreviewCallback { data, cam ->
            refreshSettings()
            val parameters = cam?.parameters
            val previewSize = parameters?.previewSize
            if (previewSize != null) {
                val frame = provideNv21(previewSize.width, previewSize.height)
                if (frame != null) {
                    if (data != null && data.size == frame.size) {
                        System.arraycopy(frame, 0, data, 0, frame.size)
                        original.onPreviewFrame(data, cam)
                    } else {
                        original.onPreviewFrame(frame, cam)
                    }
                    recordLegacySession(cam ?: camera, previewSize.width, previewSize.height)
                } else {
                    original.onPreviewFrame(data, cam)
                }
            } else {
                original.onPreviewFrame(data, cam)
            }
        }
        legacyCallbacks[original] = wrapper
        return wrapper
    }

    private fun recordLegacySession(camera: Camera, width: Int, height: Int) {
        val session = legacySessions.getOrPut(camera) { LegacyCameraSession() }
        session.previewSize = Size(width, height)
        val texture = session.attachedSurfaceTexture
        if (texture != null && texture !== session.dummySurfaceTexture) {
            runCatching { texture.setDefaultBufferSize(width, height) }
        }
        DiagnosticsState.update {
            it.copy(
                activePath = "Legacy",
                previewSize = "${width}x${height}",
                frameFormat = lastSettings?.format?.storageValue ?: "NV21",
                requestedFps = lastSettings?.fps ?: 30f,
                actualFps = computeActualFps()
            )
        }
    }

    private fun computeActualFps(): Float {
        val now = SystemClock.elapsedRealtimeNanos()
        val previous = lastFrameTimestampNs
        lastFrameTimestampNs = now
        return if (previous == 0L) 0f else 1_000_000_000f / (now - previous)
    }

    private fun refreshSettings() {
        val context = appContext ?: return
        val prefs = ModulePrefs.getInstance(context)
        val settings = prefs.read()
        val signature = buildString {
            append(settings.sourceType.storageValue)
            append('|')
            append(settings.sourceUri?.toString() ?: "")
            append('|')
            append(settings.scaleMode.storageValue)
            append('|')
            append(settings.orientation.storageValue)
            append('|')
            append(settings.mirror)
            append('|')
            append(settings.videoMode.storageValue)
        }
        if (signature != lastSettingsSignature) {
            configure(context, settings)
            lastSettingsSignature = signature
        }
        lastSettings = settings
    }

    private fun configure(context: Context, settings: ModuleSettings) {
        releaseResources()
        when (settings.sourceType) {
            SourceType.IMAGE -> settings.sourceUri?.let { loadBitmap(context, it, settings) }
            SourceType.VIDEO -> {
                val uri = settings.sourceUri ?: return
                videoDecoder = VideoDecoder(context, uri, settings.videoMode, settings.fps).also { it.start() }
            }
        }
        DiagnosticsState.update {
            it.copy(
                activePath = if (settings.sourceType == SourceType.IMAGE) "Static" else "Video",
                frameFormat = settings.format.storageValue,
                requestedFps = settings.fps
            )
        }
    }

    private fun loadBitmap(context: Context, uri: Uri, settings: ModuleSettings) {
        var stream: InputStream? = null
        try {
            stream = context.contentResolver.openInputStream(uri)
            val original = BitmapFactory.decodeStream(stream) ?: return
            val orientation = if (settings.orientation == OrientationOption.AUTO) {
                ExifUtil.readOrientation(context, uri)
            } else {
                ExifUtil.OrientationInfo(settings.orientation.degrees ?: 0, false)
            }
            exifRotation = orientation.rotationDegrees
            exifMirror = orientation.mirrored
            val oriented = applyOrientationAndMirror(original, exifRotation, settings.mirror xor exifMirror)
            baseBitmap = original
            orientedBitmap = oriented
        } catch (err: Exception) {
            logVcam("loadBitmap error: ${err.message}")
        } finally {
            stream?.close()
        }
    }

    private fun fetchBitmap(targetWidth: Int, targetHeight: Int): Bitmap? {
        val settings = lastSettings ?: return null
        val base = when (settings.sourceType) {
            SourceType.IMAGE -> orientedBitmap
            SourceType.VIDEO -> videoDecoder?.nextBitmap()?.let { frame ->
                val rotation = if (settings.orientation == OrientationOption.AUTO) 0 else settings.orientation.degrees ?: 0
                val transformed = applyOrientationAndMirror(frame, rotation, settings.mirror)
                if (transformed != frame) {
                    frame.recycle()
                }
                transformed
            }
        } ?: return null
        val negotiated = SizeUtil.negotiateTargetSize(Size(targetWidth, targetHeight), settings.manualWidth, settings.manualHeight)
        val scaled = scaleBitmap(base, negotiated.width, negotiated.height, settings.scaleMode)
        return if (scaled == base) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun provideNv21(width: Int, height: Int): ByteArray? {
        val bitmap = fetchBitmap(width, height) ?: return null
        val data = bitmapToNV21(bitmap)
        DiagnosticsState.update {
            it.copy(previewSize = "${width}x${height}", actualFps = computeActualFps())
        }
        return data
    }

    fun provideJpeg(width: Int, height: Int): ByteArray? {
        val bitmap = fetchBitmap(width, height) ?: return null
        return bitmapToJpeg(bitmap)
    }

    fun provideImage(image: Image) {
        val bitmap = fetchBitmap(image.width, image.height) ?: return
        when (image.format) {
            android.graphics.ImageFormat.YUV_420_888 -> writeBitmapIntoYUV420(bitmap, image)
            android.graphics.ImageFormat.JPEG -> {
                val buffer = image.planes[0].buffer
                buffer.position(0)
                buffer.put(bitmapToJpeg(bitmap))
            }
            android.graphics.ImageFormat.NV21 -> {
                val buffer = image.planes[0].buffer
                buffer.position(0)
                buffer.put(bitmapToNV21(bitmap))
            }
        }
        DiagnosticsState.update {
            it.copy(
                previewSize = "${image.width}x${image.height}",
                frameFormat = when (image.format) {
                    android.graphics.ImageFormat.YUV_420_888 -> "YUV_420_888"
                    android.graphics.ImageFormat.JPEG -> "JPEG"
                    android.graphics.ImageFormat.NV21 -> "NV21"
                    else -> image.format.toString()
                },
                actualFps = computeActualFps()
            )
        }
    }

    private fun releaseResources() {
        orientedBitmap?.recycle()
        orientedBitmap = null
        baseBitmap?.recycle()
        baseBitmap = null
        videoDecoder?.release()
        videoDecoder = null
    }

    private class LegacyCameraSession {
        var previewSize: Size = Size(0, 0)
        var previewSurface: Surface? = null
        var ownsPreviewSurface: Boolean = false
        var dummySurfaceTexture: SurfaceTexture? = null
        var attachedSurfaceTexture: SurfaceTexture? = null
    }

    fun warmUp() {
        refreshSettings()
    }

    fun currentSettings(): ModuleSettings? = lastSettings

    fun acquireBitmap(width: Int, height: Int): Bitmap? = fetchBitmap(width, height)
}
