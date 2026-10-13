package com.vic.inkflow.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.vic.inkflow.BuildConfig
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.repository.InkFlowRepositories
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
            val out = context.contentResolver.openOutputStream(destinationUri)
                ?: throw IllegalStateException("無法寫入目的地檔案")
            out.use { writeBackupZip(context, it, onProgress) }
        }.onFailure { Log.e(TAG, "createBackup failed", it) }
    }

    /** 公開備份目錄：檔案管理→文件→InkFlow（自動備份公開軌用）。 */
    fun publicBackupDir(): File = File(
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS
        ),
        "InkFlow"
    )

    /** 寫 zip 本體；呼叫方負責開/關 stream。傳回文件數。 */
    suspend fun writeBackupZip(
        context: Context,
        out: java.io.OutputStream,
        onProgress: (String) -> Unit = {}
    ): Int {
        return runCatching {
            val db = AppDatabase.getDatabase(context)
            val repos = InkFlowRepositories(db)
            val docs = repos.documents.getAllDocumentsSync()
            onProgress("收集註解資料…")
            val imagesByDocument = docs.associate { doc ->
                doc.uri to repos.images.getAllForDocument(doc.uri)
            }

            onProgress("建立資料庫快照…")
            val snapshot = File(context.cacheDir, "backup_snapshot_${System.currentTimeMillis()}.db")
            snapshot.delete()
            try {
                db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snapshot.path.replace("'", "''")}'")

                val schemaVersion = db.openHelper.writableDatabase.version
                onProgress("打包檔案…")
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
        }.getOrThrow()
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
            // 歷史遺留修復：AI 公式/裁圖曾落 filesDir 根（math_*.png/crop_*.png），
            // 上面的前綴 remap 夠不到它們。DB 指著卻不存在的檔，若包裡有同名，
            // 搬進 workingDir 並重寫該列；檔案還在的一律不碰。
            repairOrphanedImageUris(dbFile, File(staging, "images"), targetBase)
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
                    Col("bookmarks", "documentUri"),
                    Col("math_sources", "documentUri"),
                    Col("math_sources", "imageUri")
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

    /**
     * 斷鏈圖片修復（歷史遺留：AI 圖曾落 filesDir，前綴 remap 夠不到）。
     * 只動「DB 指著但檔案不存在」的列：包裡有同名就搬進 [targetBase] 並重寫該列；
     * 檔案還在的、非 file:// 的一律不碰。表不存在（舊庫）就跳過該表。
     */
    private fun repairOrphanedImageUris(dbFile: File, stagedImagesDir: File, targetBase: File) {
        val staged = stagedImagesDir.listFiles()?.associateBy { it.name }.orEmpty()
        if (staged.isEmpty()) return
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
        )
        try {
            data class Target(val table: String, val col: String)
            listOf(
                Target("image_annotations", "uri"),
                Target("math_sources", "imageUri")
            ).forEach { target ->
                runCatching {
                    val pending = ArrayList<Pair<String, String>>()
                    db.rawQuery("SELECT id, ${target.col} FROM ${target.table}", null).use { c ->
                        while (c.moveToNext()) {
                            val uriStr = c.getString(1) ?: continue
                            if (!uriStr.startsWith("file://")) continue
                            val path = android.net.Uri.parse(uriStr).path ?: continue
                            if (java.io.File(path).exists()) continue
                            val base = path.substringAfterLast('/')
                            if (staged.containsKey(base)) pending.add(c.getString(0) to base)
                        }
                    }
                    pending.forEach { (id, base) ->
                        val dest = java.io.File(targetBase, base)
                        runCatching {
                            java.io.FileInputStream(staged.getValue(base)).use { input ->
                                java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
                            }
                        }.onFailure {
                            Log.e(TAG, "repair copy failed $base", it)
                            return@forEach
                        }
                        db.execSQL(
                            "UPDATE ${target.table} SET ${target.col} = ? WHERE id = ?",
                            arrayOf(android.net.Uri.fromFile(dest).toString(), id)
                        )
                    }
                    if (pending.isNotEmpty()) Log.w(TAG, "repaired ${pending.size} orphaned ${target.table}.${target.col}")
                }.onFailure { Log.e(TAG, "repair skipped ${target.table}", it) }
            }
        } finally {
            db.close()
        }
    }
}
