package com.vic.inkflow.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.vic.inkflow.BuildConfig
import com.vic.inkflow.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-app backup (.inkbak / ZIP container).
 *
 * Layout:
 *   manifest.json   { formatVersion, createdAtEpochMs, appVersionName, databaseSchemaVersion,
 *                     baseDir, documents:[{uri, displayName}] }
 *   database.db     consistent SQLite snapshot (VACUUM INTO)
 *   pdfs/<name>.pdf one entry per document's working PDF
 *   images/<name>   image annotation assets (deduplicated)
 *
 * Restore is staged into filesDir/restore_staging and swapped in by
 * [applyPendingRestoreIfNeeded] before the Room singleton is built (next app launch).
 */
object BackupManager {
    private const val TAG = "BackupManager"
    const val FORMAT_VERSION = 1
    const val MIME_TYPE = "application/zip"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DB_ENTRY = "database.db"
    private const val PDF_PREFIX = "pdfs/"
    private const val IMG_PREFIX = "images/"
    private const val DB_NAME = "ink_layer_database"
    private const val PREFS_NAME = "inkflow_backup_prefs"
    private const val KEY_PENDING_RESTORE_DIR = "pending_restore_dir"

    fun backupPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun workingDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        return dir.also { it.mkdirs() }
    }

    suspend fun createBackup(
        context: Context,
        destinationUri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDatabase.getDatabase(context)
            val docs = db.documentDao().getAllDocumentsSync()
            onProgress("收集註解資料…")
            val imagesByDocument = docs.associate { doc ->
                doc.uri to db.imageAnnotationDao().getAllForDocument(doc.uri)
            }

            onProgress("建立資料庫快照…")
            val snapshot = File(context.cacheDir, "backup_snapshot_${System.currentTimeMillis()}.db")
            snapshot.delete()
            try {
                db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snapshot.path.replace("'", "''")}'")

                val schemaVersion = db.openHelper.writableDatabase.version
                onProgress("打包檔案…")
                val out = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw IllegalStateException("無法寫入目的地檔案")
                ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                    val manifest = JSONObject()
                        .put("formatVersion", FORMAT_VERSION)
                        .put("createdAtEpochMs", System.currentTimeMillis())
                        .put("appVersionName", BuildConfig.VERSION_NAME)
                        .put("databaseSchemaVersion", schemaVersion)
                        .put("baseDir", workingDir(context).absolutePath)
                        .put("documents", JSONArray().apply {
                            docs.forEach { doc ->
                                put(JSONObject().put("uri", doc.uri).put("displayName", doc.displayName))
                            }
                        })
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(DB_ENTRY))
                    snapshot.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    val seenImages = HashSet<String>()
                    docs.forEachIndexed { index, doc ->
                        onProgress("打包 PDF ${index + 1}/${docs.size}…")
                        val pdfPath = Uri.parse(doc.uri).path
                        if (pdfPath != null) {
                            val f = File(pdfPath)
                            if (f.exists()) {
                                zip.putNextEntry(ZipEntry("$PDF_PREFIX${f.name}"))
                                FileInputStream(f).use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        imagesByDocument[doc.uri]?.forEach { img ->
                            val imgPath = Uri.parse(img.uri).path ?: return@forEach
                            val name = imgPath.substringAfterLast('/')
                            if (name.isNotEmpty() && seenImages.add(name)) {
                                val imf = File(imgPath)
                                if (imf.exists()) {
                                    zip.putNextEntry(ZipEntry("$IMG_PREFIX$name"))
                                    FileInputStream(imf).use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                }
            } finally {
                snapshot.delete()
            }
            docs.size
        }.onFailure { Log.e(TAG, "createBackup failed", it) }
    }

    /** Unzips and validates the backup; the swap happens on next launch. Returns document count. */
    suspend fun stageRestore(context: Context, sourceUri: Uri): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val staging = File(context.filesDir, "restore_staging")
                staging.deleteRecursively()
                staging.mkdirs()

                var documentCount = -1
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException("無法讀取備份檔")
                ZipInputStream(input.buffered()).use { zis ->
                    var sawManifest = false
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        require(!name.contains("..")) { "備份檔包含非法路徑" }
                        when {
                            name == MANIFEST_ENTRY -> {
                                val json = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                                val version = json.optInt("formatVersion", -1)
                                require(version in 1..FORMAT_VERSION) { "不支援的備份格式版本：$version" }
                                backupPrefs(context).edit()
                                    .putString("pending_restore_base_dir", json.optString("baseDir", ""))
                                    .apply()
                                sawManifest = true
                            }
                            name == DB_ENTRY -> File(staging, DB_ENTRY).outputStream().use { zis.copyTo(it) }
                            name.startsWith(PDF_PREFIX) && !entry.isDirectory ->
                                File(staging, name).parentFile?.mkdirs().let {
                                    File(staging, name).outputStream().use { zis.copyTo(it) }
                                }
                            name.startsWith(IMG_PREFIX) && !entry.isDirectory ->
                                File(staging, name).parentFile?.mkdirs().let {
                                    File(staging, name).outputStream().use { zis.copyTo(it) }
                                }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    require(sawManifest) { "不是有效的 InkFlow 備份檔（缺少 manifest）" }
                }
                require(File(staging, DB_ENTRY).exists()) { "備份檔缺少資料庫" }

                val restoredDb = SQLiteDatabase.openDatabase(
                    File(staging, DB_ENTRY).absolutePath, null, SQLiteDatabase.OPEN_READONLY
                )
                documentCount = try {
                    restoredDb.rawQuery("SELECT COUNT(*) FROM documents", null).use { c ->
                        c.moveToFirst(); c.getInt(0)
                    }
                } finally {
                    restoredDb.close()
                }

                backupPrefs(context).edit()
                    .putString(KEY_PENDING_RESTORE_DIR, staging.absolutePath)
                    .apply()
                documentCount
            }.onFailure { Log.e(TAG, "stageRestore failed", it) }
        }

    /**
     * Called from AppDatabase.getDatabase() BEFORE the Room instance exists.
     * Swaps the staged database + assets into place so the new session opens the restored data.
     */
    fun applyPendingRestoreIfNeeded(context: Context) {
        val prefs = backupPrefs(context)
        val stagingPath = prefs.getString(KEY_PENDING_RESTORE_DIR, null) ?: return
        val staging = File(stagingPath)
        Log.w(TAG, "Applying pending restore from $stagingPath")
        try {
            if (!staging.isDirectory || !File(staging, DB_ENTRY).exists()) {
                Log.e(TAG, "Staged restore missing — aborting")
                return
            }
            val dbFile = context.getDatabasePath(DB_NAME)
            listOf("-wal", "-shm").forEach { suffix ->
                File(dbFile.absolutePath + suffix).delete()
            }
            FileInputStream(File(staging, DB_ENTRY)).use { input ->
                FileOutputStream(dbFile).use { output -> input.copyTo(output) }
            }

            val targetBase = workingDir(context)
            val oldBase = prefs.getString("pending_restore_base_dir", "") ?: ""
            listOf("pdfs", "images").forEach { sub ->
                val dir = File(staging, sub)
                dir.listFiles()?.forEach { file ->
                    FileInputStream(file).use { input ->
                        FileOutputStream(File(targetBase, file.name)).use { output -> input.copyTo(output) }
                    }
                }
            }

            if (oldBase.isNotEmpty() && oldBase != targetBase.absolutePath) {
                rewriteBaseDirectory(dbFile, oldBase, targetBase.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyPendingRestoreIfNeeded failed", e)
        } finally {
            prefs.edit()
                .remove(KEY_PENDING_RESTORE_DIR)
                .remove("pending_restore_base_dir")
                .apply()
            staging.deleteRecursively()
        }
    }

    /** Document/annotation URIs embed the absolute working dir; remap after cross-device restore. */
    private fun rewriteBaseDirectory(dbFile: File, oldBase: String, newBase: String) {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
        )
        try {
            db.beginTransaction()
            try {
                val escapedOld = oldBase.replace("'", "''")
                val escapedNew = newBase.replace("'", "''")
                data class Col(val table: String, val col: String)
                listOf(
                    Col("documents", "uri"),
                    Col("strokes", "documentUri"),
                    Col("text_annotations", "documentUri"),
                    Col("image_annotations", "documentUri"),
                    Col("image_annotations", "uri"),
                    Col("document_preferences", "documentUri"),
                    Col("bookmarks", "documentUri")
                ).forEach { target ->
                    db.execSQL(
                        "UPDATE ${target.table} SET ${target.col} = REPLACE(${target.col}, '$escapedOld', '$escapedNew') WHERE ${target.col} LIKE '$escapedOld%'"
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }
}
