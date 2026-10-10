package com.vic.inkflow.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

// This class is not an entity itself, but its fields will be embedded in PointEntity.
// Kept for compatibility with other parts of the code.
data class PointF(val x: Float, val y: Float)

/**
 * [REFACTORED] Represents the metadata for a single stroke, without the point data.
 * Added bounding box fields for efficient broad-phase collision detection.
 */
@Entity(
    tableName = "strokes",
    indices = [
        Index(value = ["documentUri", "pageIndex"]),
        Index(value = ["documentUri", "docY"])
    ]
)
data class StrokeEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val documentUri: String,   // scopes this stroke to a specific document
    val pageIndex: Int,
    /**
     * S1 單畫布：文件座標錨點 = pageIndex × stride + boundsTop（stride = 當時 modelH）。
     * null = 尚未回填（v24 前舊資料，開文件時懶回填）。S1 只寫不讀；讀切換在 S2。
     */
    val docY: Float? = null,
    val color: Int,
    val strokeWidth: Float,

    // Performance Optimization: Cached bounding box
    val boundsLeft: Float = 0f,
    val boundsTop: Float = 0f,
    val boundsRight: Float = 0f,
    val boundsBottom: Float = 0f,

    // Tool type — true = BlendMode.Multiply highlighter, false = normal pen
    val isHighlighter: Boolean = false,

    /**
     * null = freehand stroke.
     * "RECT" | "CIRCLE" | "LINE" | "ARROW" = geometric shape.
     * Points: RECT/CIRCLE use bounds; LINE/ARROW use first & last PointEntity.
     */
    val shapeType: String? = null,

    // ── v6 併發合併（SYNC_PROTOCOL.md §15.1，Excalidraw 制）──────────────

    /**
     * 本物件的改動計數器：每次被改寫就 +1。舊資料由 migration 補成 1。
     *
     * 為什麼是計數器而不是時間戳：兩台裝置的時鐘無從對齊（§6 刻意不用 lastOpenedAt
     * 就是這個原因），而併發合併需要的是「誰比較新」這種**兩端必然算出同一答案**的
     * 關係。計數器滿足：自己改自己一定 +1，兩端對同一份序列的 version 相同即代表
     * 內容相同。這正是 Excalidraw 的 `version` 做的事。
     */
    val version: Int = 1,

    /**
     * version 打平時的 tiebreak（Excalidraw 的 `versionNonce`）。
     *
     * 為什麼還需要它：兩端從同一份 v2 出發各改一次，兩邊都是 version 3。此時誰贏必須
     * 有一個雙端都能算出的規則；隨機 nonce 取小者贏，滿足交換律與結合律，所以兩端收斂到
     * 同一結果——這是 CRDT 決定性排序的最小可用形式。
     */
    val versionNonce: Int = 0,

    /**
     * 刪除墓碑（§15.4）：非 null = 已刪除，時間戳（毫秒）。**NULL = 活著。**
     *
     * 為什麼刪除不能物理消失：刪除本身也是一次編輯，兩端可能一邊刪一邊改。若刪除把行
     * 真的刪掉，對端事後就分不出「對方刪了它」與「它本來就不存在」，兩端會往不同方向
     * 收斂。墓碑讓刪除可以像其他變更一樣被比對、被覆蓋（改了就算復活）。
     */
    val deletedAt: Long? = null
)

/**
 * [NEW] Represents a single point within a stroke.
 * It is linked to a StrokeEntity via a foreign key.
 */
@Entity(
    tableName = "points",
    foreignKeys = [ForeignKey(
        entity = StrokeEntity::class,
        parentColumns = ["id"],
        childColumns = ["strokeId"],
        onDelete = ForeignKey.CASCADE // Ensures points are deleted when their parent stroke is.
    )],
    indices = [Index(value = ["strokeId"])] // Speeds up queries for points of a specific stroke.
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val strokeId: String,
    val x: Float,
    val y: Float,
    val width: Float = 0f  // Added for dynamic stroke thickness based on velocity
)

/**
 * [NEW] A data class for holding the result of a one-to-many query.
 * Room will automatically populate the 'stroke' and its related 'points'.
 */
data class StrokeWithPoints(
    @Embedded val stroke: StrokeEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "strokeId"
    )
    val points: List<PointEntity>
)
