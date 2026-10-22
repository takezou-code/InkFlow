package com.vic.inkflow.data.store

import com.vic.inkflow.data.StrokeVersionRow

/**
 * The version bookkeeping that only the **author** (the tablet) keeps.
 *
 * ## Why it is not part of [StrokeStore]
 *
 * A mirror (the desktop, and later the iOS app) receives the author's decisions;
 * it does not arbitrate. Making it implement `bumpStrokeVersion` or
 * `tombstoneStrokes` would mean implementing them as no-ops or throwing — and a
 * no-op here is worse than not having the method at all, because the shared
 * editor core would then be able to call it and silently lose the arbitration.
 *
 * The split is the honest statement of who owns what: the tablet arbitrates, the
 * mirrors display.
 */
interface StrokeArbitration {

    /** 本機改寫一筆筆跡：前進版本序號並換 tiebreak nonce。0 = 這筆不存在。 */
    suspend fun bumpStrokeVersion(id: String): Int

    /** 整份文件的 (id, version, nonce)，供提案逐 op 仲裁（§15.2）。 */
    suspend fun getStrokeVersions(documentUri: String): List<StrokeVersionRow>

    /** 單筆的合併狀態，含墓碑；刪除仲裁需要（§15.4）。 */
    suspend fun getStrokeVersion(id: String): StrokeVersionRow?

    /** 活著（非墓碑）的筆數。docVersion 與 page_counts 都用這個語意。 */
    suspend fun countLiveStrokes(documentUri: String): Int

    /** 回收比 [cutoff] 更早的墓碑。回傳刪掉幾列。 */
    suspend fun purgeTombstonedStrokes(cutoff: Long): Int

    /** 刪除＝寫墓碑（§15.4）。回傳實際寫入的列數。 */
    suspend fun tombstoneStrokes(strokeIds: List<String>): Int

    /** 復活墓碑（§15.4 修改勝）。 */
    suspend fun reviveStrokes(strokeIds: List<String>): Int

    /**
     * 刪除「使用者看得見的」筆跡：寫墓碑而不是硬刪除（§15.4）。
     *
     * 與 [StrokeStore.deleteStrokesByIds] 的差別不是風格，是**同步語意**。硬刪讓對端無從得知
     * 「這裡曾經刪過東西」，於是「刪除 vs 對方修改」這條規則根本沒有狀態可比；墓碑
     * 留下可比較的 (version, nonce, deletedAt)，仲裁才能給出「修改勝」。
     *
     * **不要把所有 deleteStrokesByIds 換成這個。** 那個方法的三種用途語意不同：
     *   - undo/redo 一筆 AddStroke：這筆從來沒被同步看過，硬刪才對，寫墓碑只會留下
     *     永遠沒人回收的幽靈列（redo 又會 insertStroke 把它蓋回來，deletedAt 歸零，
     *     等於刪除紀錄被抹掉，行為跟硬刪一樣但多走一圈）。
     *   - AddSelectionCopies / ExtractToNewPage 的復原：同上，這些物件本機自生自滅。
     *   - 頁面／文件層級的結構操作：見 clearPage／deleteStrokesForDocument，兩者都是
     *     整頁或整份文件消失，不該逐筆留墓碑。
     * 只有「刪掉別人可能也看得見、也可能同時在改的東西」才用這個。
     */
    suspend fun tombstoneStrokesByIds(strokeIds: List<String>): Int
}
