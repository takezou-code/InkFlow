package com.vic.inkflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
// [Refactored] Added DocumentEntity; bumped version to 3.
// v4: Added isHighlighter field to StrokeEntity.
// v5: Added lastPageIndex to DocumentEntity.
// v6: Added documentUri to StrokeEntity to scope strokes per document.
// v7: Added shapeType to StrokeEntity; added text_annotations & image_annotations tables.
// v8: Added document_preferences table for per-document editor setting overrides.
// v10: Added highlighterColorArgb column to document_preferences.
// v12: Added pageBackground column to document_preferences.
// v13: Added paperWidthPt / paperHeightPt columns to document_preferences.
// v14: Added per-tool pen/highlighter stroke width columns to document_preferences.
// v15: Added bookmarks table.
@Database(
    entities = [StrokeEntity::class, PointEntity::class, DocumentEntity::class,
                TextAnnotationEntity::class, ImageAnnotationEntity::class,
                DocumentPreferenceEntity::class, BookmarkEntity::class],
    version = 16
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun strokeDao(): StrokeDao
    abstract fun documentDao(): DocumentDao
    abstract fun textAnnotationDao(): TextAnnotationDao
    abstract fun imageAnnotationDao(): ImageAnnotationDao
    abstract fun documentPreferenceDao(): DocumentPreferenceDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v4: Added isHighlighter field to StrokeEntity.
                db.execSQL("ALTER TABLE strokes ADD COLUMN isHighlighter INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN lastPageIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add documentUri column; existing rows get empty string (legacy data, no owner).
                db.execSQL("ALTER TABLE strokes ADD COLUMN documentUri TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE strokes ADD COLUMN shapeType TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS text_annotations (
                        id TEXT NOT NULL PRIMARY KEY,
                        documentUri TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        modelX REAL NOT NULL,
                        modelY REAL NOT NULL,
                        fontSize REAL NOT NULL DEFAULT 16.0,
                        colorArgb INTEGER NOT NULL,
                        isStamp INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS image_annotations (
                        id TEXT NOT NULL PRIMARY KEY,
                        documentUri TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        uri TEXT NOT NULL,
                        modelX REAL NOT NULL,
                        modelY REAL NOT NULL,
                        modelWidth REAL NOT NULL,
                        modelHeight REAL NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_preferences (
                        documentUri TEXT NOT NULL PRIMARY KEY,
                        tool TEXT,
                        colorArgb INTEGER,
                        strokeWidth REAL,
                        shapeSubType TEXT,
                        stylusOnlyMode INTEGER,
                        recentColorsCsv TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_preferences ADD COLUMN inputMode TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_preferences ADD COLUMN highlighterColorArgb INTEGER")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE points ADD COLUMN width REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_preferences ADD COLUMN pageBackground TEXT")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_preferences ADD COLUMN paperWidthPt REAL")
                db.execSQL("ALTER TABLE document_preferences ADD COLUMN paperHeightPt REAL")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_preferences ADD COLUMN penStrokeWidth REAL")
                db.execSQL("ALTER TABLE document_preferences ADD COLUMN highlighterStrokeWidth REAL")
                db.execSQL(
                    "UPDATE document_preferences SET penStrokeWidth = COALESCE(strokeWidth, 4.0) WHERE penStrokeWidth IS NULL"
                )
                db.execSQL(
                    "UPDATE document_preferences SET highlighterStrokeWidth = CASE WHEN strokeWidth IS NULL OR strokeWidth < 8.0 THEN 8.0 ELSE strokeWidth END WHERE highlighterStrokeWidth IS NULL"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        documentUri TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        PRIMARY KEY(documentUri, pageIndex)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN folderName TEXT DEFAULT NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ink_layer_database"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                // Only allow destructive migration on downgrade (e.g. user reverts to an
                // older APK). Unknown *upgrade* paths surface as a hard crash rather than
                // silently wiping all user data.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}