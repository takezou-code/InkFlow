package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.DocumentPreferenceDao
import com.vic.inkflow.data.DocumentPreferenceEntity

/** 每份文件的編輯偏好（工具、粗細、紙張、輸入模式…）。見 [StrokeRepository] 的說明。 */
interface DocumentPreferenceRepository {

    suspend fun getByDocumentUri(documentUri: String): DocumentPreferenceEntity?
    suspend fun upsert(entity: DocumentPreferenceEntity)
    suspend fun deleteByDocumentUri(documentUri: String)
    suspend fun clearAll()
}

class RoomDocumentPreferenceRepository(
    private val db: AppDatabase,
    private val dao: DocumentPreferenceDao
) : DocumentPreferenceRepository {

    override suspend fun getByDocumentUri(documentUri: String): DocumentPreferenceEntity? =
        dao.getByDocumentUri(documentUri)
    override suspend fun upsert(entity: DocumentPreferenceEntity) = dao.upsert(entity)
    override suspend fun deleteByDocumentUri(documentUri: String) = dao.deleteByDocumentUri(documentUri)
    override suspend fun clearAll() = dao.clearAll()
}
