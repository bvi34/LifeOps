package com.lifeops.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.lifeops.app.data.db.dao.TaskAttachmentDao
import com.lifeops.app.data.db.entities.TaskAttachmentEntity
import com.lifeops.app.data.model.TaskAttachment
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class TaskAttachmentRepository(private val dao: TaskAttachmentDao) {

    fun observeByTask(taskId: String): Flow<List<TaskAttachment>> =
        dao.observeByTask(taskId).map { list -> list.map { it.toModel() } }

    /**
     * Copy the picked image into the task as a downscaled base64 JPEG. Downscaling (longest edge
     * capped, JPEG quality [JPEG_QUALITY]) keeps each attachment small so it fits Android's Auto
     * Backup budget and renders cheaply. Returns false if the URI could not be read/decoded.
     */
    suspend fun addFromUri(context: Context, taskId: String, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val encoded = encodeDownscaled(context, uri) ?: return@withContext false
            dao.insert(
                TaskAttachmentEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    imageData = encoded,
                    caption = null,
                    createdAt = DateUtil.now()
                )
            )
            true
        }

    suspend fun delete(id: String) = dao.delete(id)

    private fun encodeDownscaled(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver

        // First pass: read only the bounds so we can pick an integer subsample factor without
        // decoding the full-resolution bitmap into memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        // Subsampling only lands on powers of two, so scale the remainder down exactly to the cap.
        val scaled = scaleToMaxEdge(decoded, MAX_EDGE_PX)
        if (scaled != decoded) decoded.recycle()

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            scaled.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxEdge && h / 2 >= maxEdge) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val MAX_EDGE_PX = 1024
        private const val JPEG_QUALITY = 80
    }
}
