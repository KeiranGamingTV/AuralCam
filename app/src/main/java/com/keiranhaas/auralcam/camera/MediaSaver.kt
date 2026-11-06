.
package com.keiranhaas.auralcam.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes into DCIM/AuralCam through MediaStore (scoped storage, no permission needed on API 30+). */
class MediaSaver(private val context: Context) {
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /** Call from one thread only (the camera thread). */
    fun newBaseName(): String = "AURAL_" + dateFormat.format(Date())

    fun saveJpeg(baseName: String, bytes: ByteArray): Uri? =
        save("$baseName.jpg", "image/jpeg") { it.write(bytes) }

    fun saveDng(baseName: String, writer: (OutputStream) -> Unit): Uri? =
        save("$baseName.dng", "image/x-adobe-dng", writer)

    private fun save(displayName: String, mime: String, writer: (OutputStream) -> Unit): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AuralCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open output stream")
            BufferedOutputStream(stream, 1 shl 20).use { writer(it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}