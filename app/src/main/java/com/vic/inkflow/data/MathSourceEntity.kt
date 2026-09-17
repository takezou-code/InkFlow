package com.vic.inkflow.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * M7 公式源 sidecar：公式圖（image_annotations）的 TeX 源。
 * 用 imageUri（file:// 唯一檔名）關聯，不動 image_annotations 表結構。
 * 舊圖無源（查不到即視為純圖，不報錯）。
 */
@Entity(
    tableName = "math_sources",
    indices = [
        Index(value = ["imageUri"], unique = true),
        Index(value = ["documentUri", "pageIndex"])
    ]
)
data class MathSourceEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val documentUri: String,
    val pageIndex: Int,
    /** image_annotations.uri（file://），唯一。 */
    val imageUri: String,
    /** TeX 源（含分隔符原樣，如 $$...$$ 或整段混合原文）。 */
    val tex: String,
    /** 1 = display 獨立塊，0 = 混合段落。 */
    val display: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)
