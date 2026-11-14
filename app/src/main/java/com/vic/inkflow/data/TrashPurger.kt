package com.vic.inkflow.data

import android.content.Context
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.ui.AiProvider
import com.vic.inkflow.ui.aiDocProviderKey
import com.vic.inkflow.ui.aiDocUrlKey

/**
 * The one true hard-delete path for a document.
 *
 * Shared by the trash UI (``永久刪除``) and [com.vic.inkflow.util.TrashCleanupWorker]
 * (auto-purge after the retention window) so there is exactly one copy of the
 * "delete everything this document owns" logic. It lives in `data` rather than
 * the ViewModel because the worker has no ViewModel; the only `ui` imports are
 * the AI preference-key builders, which are part of the persisted contract and
 * must not be re-spelled here.
 *
 * A trashed document is only ever soft-deleted first, so reaching this function
 * means the user (or the retention policy) really meant it.
 */
object TrashPurger {

    suspend fun purge(context: Context, repos: InkFlowRepositories, uri: String) {
        val appContext = context.applicationContext

        // 必須在交易「之前」抓：deleteAllForDocument 會把 image_annotations 的列刪掉，
        // 之後就再也問不出這份文件用過哪些圖檔，磁碟會永久留下看不見的孤兒。
        val ownedImages = repos.images.getAllForDocument(uri).map { it.uri }

        // 整份連帶清（含書籤/公式源，不留孤兒）＋文件列，同一交易。
        repos.transaction {
            // P2：五張頁範圍表收斂成一個呼叫。順序與重構前逐表相同，
            // document_preferences 不在頁範圍表內所以另外清。
            repos.pageOps.deleteAllForDocument(uri)
            repos.documentPreferences.deleteByDocumentUri(uri)
            repos.documents.delete(uri)
        }

        // Delete the physical file for app-private documents (file:// URIs).
        try {
            val parsed = android.net.Uri.parse(uri)
            if (parsed.scheme == "file") {
                val file = java.io.File(parsed.path!!)
                if (file.exists()) file.delete()
            }
        } catch (_: Exception) { }

        // 連帶清圖檔，但只清「已經沒有任何一份文件引用」的。
        // 套圈貼／複製選取會讓同一個 uri 字串出現在多份文件裡（DrawCommand.AddSelectionCopies
        // 複製的是快照、uri 不變），不確認就直接刪會把別人正在看的圖弄成壞圖。
        for (imageUri in ownedImages) {
            try {
                val parsed = android.net.Uri.parse(imageUri)
                if (parsed.scheme != "file") continue
                val path = parsed.path ?: continue
                if (repos.images.countByUri(imageUri) > 0) continue
                java.io.File(path).takeIf { it.exists() }?.delete()
            } catch (_: Exception) { }
        }

        // AI 記憶 key（AiContract.aiDocProviderKey／aiDocUrlKey）：同一份
        // inkflow_settings 裡按文件 URI 記的 provider＋上次對話 URL。
        // 刪文件時一起清，否則每刪一份永久留下數個孤兒 key。
        // 走 entries 而不是點名兩站：將來加第三個站也不會漏。
        try {
            val prefsEdit = appContext
                .getSharedPreferences("inkflow_settings", Context.MODE_PRIVATE)
                .edit()
            prefsEdit.remove(aiDocProviderKey(uri))
            AiProvider.entries.forEach { p -> prefsEdit.remove(aiDocUrlKey(uri, p)) }
            prefsEdit.apply()
        } catch (_: Exception) { }
    }
}
