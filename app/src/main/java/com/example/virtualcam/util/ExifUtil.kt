package com.example.virtualcam.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.virtualcam.logVcam
import java.io.InputStream

// GREP: EXIF_ORIENTATION
object ExifUtil {

    data class OrientationInfo(val rotationDegrees: Int, val mirrored: Boolean)

    fun readOrientation(context: Context, uri: Uri): OrientationInfo {
        val resolver: ContentResolver = context.contentResolver
        var stream: InputStream? = null
        return try {
            stream = resolver.openInputStream(uri)
            if (stream != null) {
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val (rotation, flip) = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
                    ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
                    else -> 0 to false
                }
                OrientationInfo(rotation, flip)
            } else {
                OrientationInfo(0, false)
            }
        } catch (err: Exception) {
            logVcam("ExifUtil readOrientation error: ${err.message}")
            OrientationInfo(0, false)
        } finally {
            stream?.close()
        }
    }
}
