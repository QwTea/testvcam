package com.example.virtualcam.xposed

import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import android.view.Surface
import com.example.virtualcam.logVcam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

// GREP: HOOK_CAMERAX
object CameraXHooks {
    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (installed.getAndSet(true)) return
        val types = runCatching { CameraXTypes(classLoader) }.getOrElse { err ->
            logVcam("CameraX class discovery failed: ${err.message}")
            return
        }
        hookImageAnalysis(types)
        hookImageCapture(types)
        hookSurfaceRequest(classLoader)
    }

    private fun hookImageAnalysis(types: CameraXTypes) {
        try {
            XposedHelpers.findAndHookMethod(
                types.imageAnalysisClass,
                "setAnalyzer",
                Executor::class.java,
                types.analyzerInterface,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val originalAnalyzer = param.args[1]
                        val proxy = Proxy.newProxyInstance(
                            types.classLoader,
                            arrayOf(types.analyzerInterface)
                        ) { _, method, args ->
                            if (method.name == "analyze" && !args.isNullOrEmpty()) {
                                val imageProxy = args[0]
                                FakeFrameInjector.warmUp()
                                val width = types.width(imageProxy)
                                val height = types.height(imageProxy)
                                val info = types.imageInfo(imageProxy)
                                val data = FakeFrameInjector.provideNv21(width, height)
                                val fake = if (data != null) {
                                    VirtualCamImageProxyFactory.fromNv21(
                                        types,
                                        width,
                                        height,
                                        types.timestampUs(info),
                                        data,
                                        info
                                    )
                                } else {
                                    imageProxy
                                }
                                DiagnosticsState.update {
                                    it.copy(
                                        activePath = "CameraX",
                                        previewSize = "${width}x${height}",
                                        frameFormat = "YUV_420_888"
                                    )
                                }
                                if (data != null) {
                                    types.close(imageProxy)
                                }
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

    private fun hookImageCapture(types: CameraXTypes) {
        try {
            XposedHelpers.findAndHookMethod(
                types.imageCaptureClass,
                "takePicture",
                Executor::class.java,
                types.onImageCapturedCallbackClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callback = param.args[1]
                        val successMethod = types.onImageCapturedCallbackClass.getMethod(
                            "onCaptureSuccess",
                            types.imageProxyClass
                        )
                        val proxy = Proxy.newProxyInstance(
                            types.classLoader,
                            arrayOf(types.onImageCapturedCallbackClass)
                        ) { _, methodInvoked, args ->
                            if (methodInvoked.name == "onCaptureSuccess" && !args.isNullOrEmpty()) {
                                FakeFrameInjector.warmUp()
                                val settings = FakeFrameInjector.currentSettings()
                                val width = settings?.manualWidth ?: 1280
                                val height = settings?.manualHeight ?: 720
                                val data = FakeFrameInjector.provideNv21(width, height)
                                val original = args[0]
                                val info = types.imageInfo(original)
                                val fake = if (data != null) {
                                    VirtualCamImageProxyFactory.fromNv21(
                                        types,
                                        width,
                                        height,
                                        System.currentTimeMillis() * 1000,
                                        data,
                                        info
                                    )
                                } else {
                                    original
                                }
                                DiagnosticsState.update {
                                    it.copy(
                                        activePath = "CameraX",
                                        previewSize = "${width}x${height}"
                                    )
                                }
                                if (data != null) {
                                    types.close(original)
                                }
                                successMethod.invoke(callback, fake)
                                null
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
                        com.example.virtualcam.util.startSurfacePainter(
                            originalSurface,
                            { FakeFrameInjector.acquireBitmap(1280, 720) },
                            settings.fps
                        )
                        DiagnosticsState.update {
                            it.copy(
                                activePath = "CameraX",
                                frameFormat = "Preview",
                                requestedFps = settings.fps
                            )
                        }
                        param.args[0] = dummy
                    }
                }
            )
        } catch (err: Throwable) {
            logVcam("CameraX SurfaceRequest hook failed: ${err.message}")
        }
    }
}

private object VirtualCamImageProxyFactory {
    fun fromNv21(
        types: CameraXTypes,
        width: Int,
        height: Int,
        timestampUs: Long,
        data: ByteArray,
        templateInfo: Any?
    ): Any {
        return VirtualCamImageProxyHandler(types, width, height, timestampUs, data, templateInfo).proxy()
    }
}

private class VirtualCamImageProxyHandler(
    private val types: CameraXTypes,
    private val width: Int,
    private val height: Int,
    timestampUs: Long,
    data: ByteArray,
    templateInfo: Any?
) : InvocationHandler {
    private val cropRect = Rect(0, 0, width, height)
    private val closed = AtomicBoolean(false)
    private val imageInfo: Any = CameraXImageInfoProxy.create(types, templateInfo, timestampUs)
    private val planes: Any = buildPlanes(data)

    fun proxy(): Any = Proxy.newProxyInstance(types.classLoader, arrayOf(types.imageProxyClass), this)

    private fun buildPlanes(data: ByteArray): Any {
        val ySize = width * height
        val uvSize = ySize / 2
        val yBuffer = ByteBuffer.allocateDirect(ySize).apply {
            put(data, 0, min(data.size, ySize))
            flip()
        }
        val uBuffer = ByteBuffer.allocateDirect(uvSize / 2)
        val vBuffer = ByteBuffer.allocateDirect(uvSize / 2)
        var offset = ySize
        while (offset < data.size && uBuffer.hasRemaining() && vBuffer.hasRemaining()) {
            val v = data[offset]
            val u = if (offset + 1 < data.size) data[offset + 1] else 0
            vBuffer.put(v)
            uBuffer.put(u)
            offset += 2
        }
        uBuffer.flip()
        vBuffer.flip()
        val array = Array.newInstance(types.planeProxyClass, 3)
        Array.set(array, 0, PlaneProxyHandler(types, yBuffer.asReadOnlyBuffer(), width, 1).proxy())
        Array.set(array, 1, PlaneProxyHandler(types, uBuffer.asReadOnlyBuffer(), width / 2, 1).proxy())
        Array.set(array, 2, PlaneProxyHandler(types, vBuffer.asReadOnlyBuffer(), width / 2, 1).proxy())
        return array
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = when (method.name) {
        "close" -> {
            closed.set(true)
            null
        }
        "getWidth" -> width
        "getHeight" -> height
        "getImage" -> null
        "getImageInfo" -> imageInfo
        "getPlanes" -> planes
        "getFormat" -> ImageFormat.YUV_420_888
        "getCropRect" -> Rect(cropRect)
        "setCropRect" -> {
            val rect = args.firstOrNull() as? Rect
            if (rect != null) {
                cropRect.set(rect)
            } else {
                cropRect.set(0, 0, width, height)
            }
            null
        }
        "toString" -> "VirtualCamImageProxy(width=$width,height=$height,closed=${closed.get()})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.firstOrNull()
        else -> defaultReturnValue(method.returnType)
    }
}

private class PlaneProxyHandler(
    private val types: CameraXTypes,
    private val buffer: ByteBuffer,
    private val rowStride: Int,
    private val pixelStride: Int
) : InvocationHandler {
    fun proxy(): Any = Proxy.newProxyInstance(types.classLoader, arrayOf(types.planeProxyClass), this)

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = when (method.name) {
        "getBuffer" -> buffer.asReadOnlyBuffer()
        "getRowStride" -> rowStride
        "getPixelStride" -> pixelStride
        "toString" -> "VirtualCamPlaneProxy(rowStride=$rowStride,pixelStride=$pixelStride)"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.firstOrNull()
        else -> defaultReturnValue(method.returnType)
    }
}

private object CameraXImageInfoProxy {
    fun create(types: CameraXTypes, template: Any?, fallbackTimestampUs: Long): Any {
        val timestampUs = template?.let { types.timestampUs(it) } ?: fallbackTimestampUs
        val tagBundle = types.tagBundle(template)
        val rotation = types.rotationDegrees(template)
        val transform = types.transform(template)
        val iface = types.imageInfoInterface
        return if (iface != null) {
            Proxy.newProxyInstance(
                types.classLoader,
                arrayOf(iface),
                ImageInfoHandler(timestampUs, tagBundle, rotation, transform)
            )
        } else {
            template ?: ImageInfoHandler(timestampUs, tagBundle, rotation, transform).fallbackStub()
        }
    }
}

private class ImageInfoHandler(
    private val timestampUs: Long,
    private val tagBundle: Any?,
    private val rotationDegrees: Int,
    transform: Matrix
) : InvocationHandler {
    private val transformCopy: Matrix = copyMatrix(transform)

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? = when (method.name) {
        "getTimestamp", "getTimestampUs", "getTimestampMicros" -> timestampUs
        "getTimestampMillis" -> TimeUnit.MICROSECONDS.toMillis(timestampUs)
        "getTimestampNano", "getTimestampNanos" -> TimeUnit.MICROSECONDS.toNanos(timestampUs)
        "getTagBundle" -> tagBundle
        "getSensorToBufferTransformMatrix" -> copyMatrix(transformCopy)
        "getRotationDegrees" -> rotationDegrees
        "toString" -> "VirtualCamImageInfo(timestampUs=$timestampUs,rotation=$rotationDegrees)"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.firstOrNull()
        else -> defaultReturnValue(method.returnType)
    }

    fun fallbackStub(): Any = object {
        @Suppress("unused")
        fun getTimestamp(): Long = timestampUs

        @Suppress("unused")
        fun getTagBundle(): Any? = tagBundle

        @Suppress("unused")
        fun getSensorToBufferTransformMatrix(): Matrix = copyMatrix(transformCopy)

        @Suppress("unused")
        fun getRotationDegrees(): Int = rotationDegrees
    }
}

private class CameraXTypes(val classLoader: ClassLoader) {
    val imageAnalysisClass: Class<*> =
        XposedHelpers.findClass("androidx.camera.core.ImageAnalysis", classLoader)
    val analyzerInterface: Class<*> =
        Class.forName("androidx.camera.core.ImageAnalysis\$Analyzer", false, classLoader)
    val imageCaptureClass: Class<*> =
        XposedHelpers.findClass("androidx.camera.core.ImageCapture", classLoader)
    val onImageCapturedCallbackClass: Class<*> =
        Class.forName("androidx.camera.core.ImageCapture\$OnImageCapturedCallback", false, classLoader)
    val imageProxyClass: Class<*> =
        Class.forName("androidx.camera.core.ImageProxy", false, classLoader)

    private val planesMethod: Method? = runCatching { imageProxyClass.getMethod("getPlanes") }.getOrNull()
    val planeProxyClass: Class<*> = planesMethod?.returnType?.componentType
        ?: imageProxyClass.declaredClasses.firstOrNull { it.simpleName == "PlaneProxy" }
        ?: Class.forName("androidx.camera.core.ImageProxy\$PlaneProxy", false, classLoader)

    private val imageInfoMethodInternal: Method? =
        runCatching { imageProxyClass.getMethod("getImageInfo") }.getOrNull()
    val imageInfoMethod: Method? = imageInfoMethodInternal
    private val rawImageInfoType: Class<*>? = imageInfoMethodInternal?.returnType
        ?: runCatching { Class.forName("androidx.camera.core.ImageInfo", false, classLoader) }.getOrNull()
    val imageInfoInterface: Class<*>? = when {
        rawImageInfoType == null -> null
        rawImageInfoType.isInterface -> rawImageInfoType
        rawImageInfoType.interfaces.isNotEmpty() -> rawImageInfoType.interfaces[0]
        else -> null
    }

    val widthMethod: Method = imageProxyClass.getMethod("getWidth")
    val heightMethod: Method = imageProxyClass.getMethod("getHeight")
    val closeMethod: Method = imageProxyClass.getMethod("close")

    private val tagBundleClass: Class<*>? = runCatching {
        Class.forName("androidx.camera.core.impl.TagBundle", false, classLoader)
    }.getOrNull()
    private val emptyTagBundleMethod = tagBundleClass?.getMethod("emptyBundle")
    val emptyTagBundle: Any? = emptyTagBundleMethod?.invoke(null)

    private val infoClass = rawImageInfoType

    private val timestampMethods: List<Method> = infoClass?.methods?.filter {
        it.parameterCount == 0 &&
            (it.returnType == Long::class.javaPrimitiveType || it.returnType == java.lang.Long::class.java) &&
            it.name.contains("timestamp", ignoreCase = true)
    } ?: emptyList()

    private val timestampFields: List<Field> = infoClass?.declaredFields?.filter {
        (it.type == Long::class.javaPrimitiveType || it.type == java.lang.Long::class.java) &&
            it.name.contains("timestamp", ignoreCase = true)
    } ?: emptyList()

    private val tagBundleMethod: Method? = infoClass?.methods?.firstOrNull {
        it.parameterCount == 0 && tagBundleClass?.isAssignableFrom(it.returnType) == true &&
            it.name.contains("tag", ignoreCase = true)
    }
    private val tagBundleField: Field? = infoClass?.declaredFields?.firstOrNull {
        tagBundleClass?.isAssignableFrom(it.type) == true
    }

    private val rotationMethod: Method? = infoClass?.methods?.firstOrNull {
        it.parameterCount == 0 &&
            (it.returnType == Int::class.javaPrimitiveType || it.returnType == java.lang.Integer::class.java) &&
            it.name.contains("rotation", ignoreCase = true)
    }
    private val rotationField: Field? = infoClass?.declaredFields?.firstOrNull {
        (it.type == Int::class.javaPrimitiveType || it.type == java.lang.Integer::class.java) &&
            it.name.contains("rotation", ignoreCase = true)
    }

    private val transformMethod: Method? = infoClass?.methods?.firstOrNull {
        it.parameterCount == 0 &&
            Matrix::class.java.isAssignableFrom(it.returnType) &&
            it.name.contains("transform", ignoreCase = true)
    }
    private val transformField: Field? = infoClass?.declaredFields?.firstOrNull {
        Matrix::class.java.isAssignableFrom(it.type)
    }

    fun width(proxy: Any?): Int = (proxy?.let { widthMethod.invoke(it) } as? Number)?.toInt() ?: 0

    fun height(proxy: Any?): Int = (proxy?.let { heightMethod.invoke(it) } as? Number)?.toInt() ?: 0

    fun close(proxy: Any?) {
        if (proxy != null) {
            runCatching { closeMethod.invoke(proxy) }
        }
    }

    fun imageInfo(proxy: Any?): Any? = proxy?.let { imageInfoMethod?.invoke(it) }

    fun timestampUs(info: Any?): Long {
        if (info == null) return System.currentTimeMillis() * 1000
        for (method in timestampMethods) {
            val value = (runCatching { method.invoke(info) }.getOrNull() as? Number)?.toLong() ?: continue
            val name = method.name.lowercase()
            return when {
                name.contains("nano") -> TimeUnit.NANOSECONDS.toMicros(value)
                name.contains("milli") -> TimeUnit.MILLISECONDS.toMicros(value)
                name.contains("micro") -> value
                name.contains("us") -> value
                else -> value
            }
        }
        for (field in timestampFields) {
            field.isAccessible = true
            val value = (runCatching { field.get(info) }.getOrNull() as? Number)?.toLong()
            if (value != null) return value
        }
        return System.currentTimeMillis() * 1000
    }

    fun tagBundle(info: Any?): Any? {
        if (info == null) return emptyTagBundle
        tagBundleMethod?.let {
            val value = runCatching { it.invoke(info) }.getOrNull()
            if (value != null) return value
        }
        val field = tagBundleField
        if (field != null) {
            field.isAccessible = true
            val value = runCatching { field.get(info) }.getOrNull()
            if (value != null) return value
        }
        return emptyTagBundle
    }

    fun rotationDegrees(info: Any?): Int {
        if (info == null) return 0
        rotationMethod?.let {
            val value = runCatching { it.invoke(info) }.getOrNull() as? Number
            if (value != null) return value.toInt()
        }
        val field = rotationField
        if (field != null) {
            field.isAccessible = true
            val value = runCatching { field.get(info) }.getOrNull() as? Number
            if (value != null) return value.toInt()
        }
        return 0
    }

    fun transform(info: Any?): Matrix {
        if (info == null) return Matrix()
        transformMethod?.let {
            val value = runCatching { it.invoke(info) }.getOrNull()
            if (value is Matrix) return copyMatrix(value)
        }
        val field = transformField
        if (field != null) {
            field.isAccessible = true
            val value = runCatching { field.get(info) }.getOrNull()
            if (value is Matrix) return copyMatrix(value)
        }
        return Matrix()
    }
}

private fun defaultReturnValue(type: Class<*>): Any? = when {
    !type.isPrimitive -> null
    type == java.lang.Boolean.TYPE -> false
    type == java.lang.Byte.TYPE -> 0.toByte()
    type == java.lang.Short.TYPE -> 0.toShort()
    type == java.lang.Character.TYPE -> 0.toChar()
    type == java.lang.Integer.TYPE -> 0
    type == java.lang.Long.TYPE -> 0L
    type == java.lang.Float.TYPE -> 0f
    type == java.lang.Double.TYPE -> 0.0
    type == java.lang.Void.TYPE -> null
    else -> null
}

private fun copyMatrix(matrix: Matrix): Matrix = Matrix().apply { set(matrix) }

private fun Array<out Any>?.firstOrNull(): Any? = if (this != null && isNotEmpty()) this[0] else null
