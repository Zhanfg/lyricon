/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.translate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.proify.android.extensions.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * 传统机器翻译缓存：内存 LRU + SQLite。
 *
 * 与 AI 翻译缓存分开，避免不同引擎/语义互相污染；cache key 会包含引擎和目标语言。
 */
internal class ClassicTranslationCache(
    private val maxCacheSize: Int,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "ClassicTranslationCache"
        const val DATABASE_NAME = "lyricon_classic_translation.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NAME = "translation_cache"
        const val COLUMN_ID = "cache_key"
        const val COLUMN_DATA = "translation_json"
        const val COLUMN_TIMESTAMP = "created_at"
    }

    private val dbMutex = Mutex()
    private var dbHelper: DatabaseHelper? = null

    private val memory: MutableMap<String, List<ClassicTranslationItem>> =
        Collections.synchronizedMap(
            object :
                LinkedHashMap<String, List<ClassicTranslationItem>>(maxCacheSize, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<ClassicTranslationItem>>?
                ): Boolean = size > maxCacheSize
            }
        )

    fun init(context: Context) {
        if (dbHelper != null) return
        synchronized(this) {
            if (dbHelper == null) {
                dbHelper = DatabaseHelper(context.applicationContext)
            }
        }
    }

    fun getMemory(key: String): List<ClassicTranslationItem>? = memory[key]

    fun putMemory(key: String, items: List<ClassicTranslationItem>) {
        memory[key] = items
    }

    suspend fun getDb(key: String): List<ClassicTranslationItem>? = dbMutex.withLock {
        val db = dbHelper?.readableDatabase ?: return null
        runCatching {
            db.query(
                TABLE_NAME,
                arrayOf(COLUMN_DATA),
                "$COLUMN_ID = ?",
                arrayOf(key),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val data = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DATA))
                json.decodeFromString<List<ClassicTranslationItem>>(data)
            }
        }.onFailure {
            Log.e(TAG, "Cache read failed", it)
        }.getOrNull()
    }

    suspend fun putDb(key: String, items: List<ClassicTranslationItem>) {
        val data = json.encodeToString(items)
        dbMutex.withLock {
            val db = dbHelper?.writableDatabase ?: return@withLock
            runCatching {
                db.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    ContentValues().apply {
                        put(COLUMN_ID, key)
                        put(COLUMN_DATA, data)
                        put(COLUMN_TIMESTAMP, System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }.onFailure {
                Log.e(TAG, "Cache write failed", it)
            }
        }
    }

    fun clear(callback: () -> Unit) {
        memory.clear()
        scope.launch(Dispatchers.IO) {
            dbMutex.withLock {
                runCatching {
                    dbHelper?.writableDatabase?.delete(TABLE_NAME, null, null)
                }.onFailure {
                    Log.e(TAG, "Cache clear failed", it)
                }
            }
            withContext(Dispatchers.Main) { callback() }
        }
    }

    fun close() {
        memory.clear()
        synchronized(this) {
            dbHelper?.close()
            dbHelper = null
        }
    }

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_NAME (
                    $COLUMN_ID TEXT PRIMARY KEY,
                    $COLUMN_DATA TEXT NOT NULL,
                    $COLUMN_TIMESTAMP INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_classic_translation_timestamp " +
                        "ON $TABLE_NAME($COLUMN_TIMESTAMP)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }
}
