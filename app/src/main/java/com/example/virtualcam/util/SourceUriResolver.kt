package com.example.virtualcam.util

import android.content.Context
import android.net.Uri
import com.example.virtualcam.logVcam
import com.example.virtualcam.provider.VirtualCamProvider
import java.io.FileNotFoundException
import kotlin.io.use

object SourceUriResolver {
    fun resolve(context: Context, preferred: Uri?): Uri? {
        if (preferred == null) {
            return null
        }
        if (canRead(context, preferred)) {
            return preferred
        }
        val proxy = VirtualCamProvider.SELECTED_URI
        if (preferred != proxy && canRead(context, proxy)) {
            logVcam("SourceUriResolver: falling back to provider URI for $preferred")
            return proxy
        }
        logVcam("SourceUriResolver: unable to access $preferred")
        return null
    }

    private fun canRead(context: Context, uri: Uri): Boolean {
        return try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
            if (fd != null) {
                fd.use { }
                true
            } else {
                false
            }
        } catch (err: SecurityException) {
            false
        } catch (err: FileNotFoundException) {
            false
        } catch (err: Exception) {
            false
        }
    }
}
