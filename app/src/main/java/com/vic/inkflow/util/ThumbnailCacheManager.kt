package com.vic.inkflow.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

object ThumbnailCacheManager {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    // Use 1/16th of the available memory for this document cover cache.
    private val cacheSize = maxMemory / 16
    
    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            // The cache size will be measured in kilobytes rather than number of items.
            return bitmap.byteCount / 1024
        }
    }

    private fun getDiskCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun encodeKey(key: String): String {
        return Base64.encodeToString(key.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun get(key: String): Bitmap? {
        return cache.get(key)
    }

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
    
    fun remove(key: String) {
        cache.remove(key)
    }

    fun clear() {
        cache.evictAll()
    }

    fun loadFromDisk(context: Context, key: String): Bitmap? {
        val file = File(getDiskCacheDir(context), encodeKey(key))
        if (!file.exists()) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    fun saveToDisk(context: Context, key: String, bitmap: Bitmap) {
        val file = File(getDiskCacheDir(context), encodeKey(key))
        try {
            FileOutputStream(file).use { out ->
                // Use WebP or PNG for better quality, but JPEG is faster. We use PNG to avoid quality loss over time/black background
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeFromDisk(context: Context, key: String) {
        val file = File(getDiskCacheDir(context), encodeKey(key))
        if (file.exists()) {
            file.delete()
        }
    }
}