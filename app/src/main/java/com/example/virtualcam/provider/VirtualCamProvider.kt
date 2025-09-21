package com.example.virtualcam.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.virtualcam.logVcam
import com.example.virtualcam.prefs.ModulePrefs
import java.io.FileNotFoundException

// GREP: PROVIDER_SAF_PROXY
class VirtualCamProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? {
        val context = context ?: return null
        val settings = ModulePrefs.getInstance(context).read()
        val sourceUri = settings.sourceUri ?: return null
        return context.contentResolver.getType(sourceUri)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: throw FileNotFoundException("No context")
        val settings = ModulePrefs.getInstance(context).read()
        val sourceUri = settings.sourceUri ?: throw FileNotFoundException("Source not configured")
        if (uri.path != SELECTED_PATH) {
            throw FileNotFoundException("Unsupported path: ${uri.path}")
        }
        val callingPkg = callingPackage
        if (callingPkg != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.grantUriPermission(callingPkg, sourceUri, flags)
            } catch (err: SecurityException) {
                logVcam("grantUriPermission failed: ${err.message}")
            }
        }
        return context.contentResolver.openFileDescriptor(sourceUri, mode)
    }

    companion object {
        const val AUTHORITY: String = "com.example.virtualcam.provider"
        private const val SELECTED_PATH: String = "/selected"
        val SELECTED_URI: Uri = Uri.parse("content://$AUTHORITY$SELECTED_PATH")
    }
}
