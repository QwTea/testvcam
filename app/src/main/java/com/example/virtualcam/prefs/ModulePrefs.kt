package com.example.virtualcam.prefs

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import com.example.virtualcam.logVcam
import java.io.File

// GREP: PREF_KEYS
object PrefKeys {
    const val PREF_FILE = "virtualcam_prefs"
    const val ENABLED = "enabled"
    const val SOURCE_TYPE = "source_type"
    const val SOURCE_URI = "source_uri"
    const val SCALE_MODE = "scale_mode"
    const val MANUAL_W = "manual_w"
    const val MANUAL_H = "manual_h"
    const val FPS = "fps"
    const val ORIENTATION = "orientation"
    const val MIRROR = "mirror"
    const val FORMAT = "format"
    const val API_PRIORITY = "api_priority"
    const val VIDEO_MODE = "video_mode"
    const val INJECT_CAM2_PREVIEW = "inject_cam2_preview"
    const val VERBOSE = "verbose"
}

enum class SourceType(val storageValue: String) {
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun fromStorage(value: String?): SourceType = values().firstOrNull { it.storageValue == value } ?: IMAGE
    }
}

enum class ScaleMode(val storageValue: String) {
    FIT("FIT"),
    CENTER_CROP("CENTER_CROP");

    companion object {
        fun fromStorage(value: String?): ScaleMode = values().firstOrNull { it.storageValue == value } ?: FIT
    }
}

enum class OrientationOption(val storageValue: String, val degrees: Int?) {
    AUTO("auto", null),
    DEG_90("90", 90),
    DEG_180("180", 180),
    DEG_270("270", 270);

    companion object {
        fun fromStorage(value: String?): OrientationOption = values().firstOrNull { it.storageValue == value } ?: AUTO
    }
}

enum class FrameFormat(val storageValue: String) {
    NV21("NV21"),
    YUV_420_888("YUV_420_888"),
    JPEG("JPEG");

    companion object {
        fun fromStorage(value: String?): FrameFormat = values().firstOrNull { it.storageValue == value } ?: NV21
    }
}

enum class ApiPriority(val storageValue: String) {
    AUTO("Auto"),
    LEGACY("Legacy"),
    CAMERA2("Camera2"),
    CAMERAX("CameraX");

    companion object {
        fun fromStorage(value: String?): ApiPriority = values().firstOrNull { it.storageValue == value } ?: AUTO
    }
}

enum class VideoMode(val storageValue: String) {
    MMR("MMR"),
    CODEC("Codec");

    companion object {
        fun fromStorage(value: String?): VideoMode = values().firstOrNull { it.storageValue == value } ?: MMR
    }
}

data class ModuleSettings(
    val enabled: Boolean = false,
    val sourceType: SourceType = SourceType.IMAGE,
    val sourceUri: Uri? = null,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val manualWidth: Int? = null,
    val manualHeight: Int? = null,
    val fps: Float = 30f,
    val orientation: OrientationOption = OrientationOption.AUTO,
    val mirror: Boolean = false,
    val format: FrameFormat = FrameFormat.NV21,
    val apiPriority: ApiPriority = ApiPriority.AUTO,
    val videoMode: VideoMode = VideoMode.MMR,
    val injectCam2Preview: Boolean = false,
    val verbose: Boolean = false
)

class ModulePrefs private constructor(context: Context) {

    // GREP: PREFS_DPS_WORLD_READABLE
    private val dpsContext: Context = context.createDeviceProtectedStorageContext()
    private val prefs: SharedPreferences

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpsContext.moveSharedPreferencesFrom(context, PrefKeys.PREF_FILE)
        }
        @Suppress("DEPRECATION")
        val mode = Context.MODE_WORLD_READABLE
        prefs = dpsContext.getSharedPreferences(PrefKeys.PREF_FILE, mode)
        ensureWorldReadable()
    }

    private fun ensureWorldReadable() {
        val file = File(dpsContext.dataDir, "shared_prefs/${PrefKeys.PREF_FILE}.xml")
        if (file.exists()) {
            val readable = file.setReadable(true, false)
            if (!readable) {
                logVcam("ModulePrefs failed to mark prefs world-readable")
            }
        }
    }

    fun read(): ModuleSettings {
        val uriString = prefs.getString(PrefKeys.SOURCE_URI, null)
        return ModuleSettings(
            enabled = prefs.getBoolean(PrefKeys.ENABLED, false),
            sourceType = SourceType.fromStorage(prefs.getString(PrefKeys.SOURCE_TYPE, SourceType.IMAGE.storageValue)),
            sourceUri = uriString?.let { Uri.parse(it) },
            scaleMode = ScaleMode.fromStorage(prefs.getString(PrefKeys.SCALE_MODE, ScaleMode.FIT.storageValue)),
            manualWidth = prefs.getInt(PrefKeys.MANUAL_W, 0).let { if (it <= 0) null else it },
            manualHeight = prefs.getInt(PrefKeys.MANUAL_H, 0).let { if (it <= 0) null else it },
            fps = prefs.getFloat(PrefKeys.FPS, 30f),
            orientation = OrientationOption.fromStorage(prefs.getString(PrefKeys.ORIENTATION, OrientationOption.AUTO.storageValue)),
            mirror = prefs.getBoolean(PrefKeys.MIRROR, false),
            format = FrameFormat.fromStorage(prefs.getString(PrefKeys.FORMAT, FrameFormat.NV21.storageValue)),
            apiPriority = ApiPriority.fromStorage(prefs.getString(PrefKeys.API_PRIORITY, ApiPriority.AUTO.storageValue)),
            videoMode = VideoMode.fromStorage(prefs.getString(PrefKeys.VIDEO_MODE, VideoMode.MMR.storageValue)),
            injectCam2Preview = prefs.getBoolean(PrefKeys.INJECT_CAM2_PREVIEW, false),
            verbose = prefs.getBoolean(PrefKeys.VERBOSE, false)
        )
    }

    fun write(settings: ModuleSettings) {
        prefs.edit(commit = true) {
            putBoolean(PrefKeys.ENABLED, settings.enabled)
            putString(PrefKeys.SOURCE_TYPE, settings.sourceType.storageValue)
            putString(PrefKeys.SOURCE_URI, settings.sourceUri?.toString())
            putString(PrefKeys.SCALE_MODE, settings.scaleMode.storageValue)
            putInt(PrefKeys.MANUAL_W, settings.manualWidth ?: 0)
            putInt(PrefKeys.MANUAL_H, settings.manualHeight ?: 0)
            putFloat(PrefKeys.FPS, settings.fps)
            putString(PrefKeys.ORIENTATION, settings.orientation.storageValue)
            putBoolean(PrefKeys.MIRROR, settings.mirror)
            putString(PrefKeys.FORMAT, settings.format.storageValue)
            putString(PrefKeys.API_PRIORITY, settings.apiPriority.storageValue)
            putString(PrefKeys.VIDEO_MODE, settings.videoMode.storageValue)
            putBoolean(PrefKeys.INJECT_CAM2_PREVIEW, settings.injectCam2Preview)
            putBoolean(PrefKeys.VERBOSE, settings.verbose)
        }
        ensureWorldReadable()
    }

    companion object {
        @Volatile
        private var instance: ModulePrefs? = null

        fun getInstance(context: Context): ModulePrefs {
            return instance ?: synchronized(this) {
                instance ?: ModulePrefs(context.applicationContext).also { instance = it }
            }
        }
    }
}
