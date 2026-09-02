package com.rencon.biwaswim.log

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 遊泳記録の永続化管理クラス。
 * アプリの内部ストレージにJSONとして記録を保存し、再起動後も保持します。
 */
class SwimLogManager(private val context: Context) {

    companion object {
        private const val TAG = "SwimLogManager"
        private const val FILE_NAME = "swim_logs.json"

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        @Volatile
        private var instance: SwimLogManager? = null

        fun getInstance(context: Context): SwimLogManager {
            return instance ?: synchronized(this) {
                instance ?: SwimLogManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val logFile: File
        get() = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun getAllLogs(): List<SwimLog> {
        return try {
            if (!logFile.exists()) {
                return emptyList()
            }
            val content = logFile.readText()
            if (content.isBlank()) return emptyList()
            val list = json.decodeFromString<List<SwimLog>>(content)
            list.sortedByDescending { it.startTimeMs }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load swim logs", e)
            emptyList()
        }
    }

    @Synchronized
    fun saveLog(log: SwimLog) {
        try {
            val currentLogs = getAllLogs().toMutableList()
            // 既存の同一IDがあれば置き換え、なければ先頭に追加
            val index = currentLogs.indexOfFirst { it.id == log.id }
            if (index >= 0) {
                currentLogs[index] = log
            } else {
                currentLogs.add(0, log)
            }
            val content = json.encodeToString(currentLogs)
            logFile.writeText(content)
            Log.d(TAG, "Saved swim log: ${log.id}, total records: ${currentLogs.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save swim log", e)
        }
    }

    @Synchronized
    fun deleteLog(id: String) {
        try {
            val currentLogs = getAllLogs().toMutableList()
            val target = currentLogs.find { it.id == id }
            if (target != null) {
                // 画像ファイルが存在する場合は削除
                target.imageFileName?.let { fileName ->
                    val imgFile = File(context.filesDir, "swim_logs/$fileName")
                    if (imgFile.exists()) {
                        imgFile.delete()
                    }
                }
                currentLogs.removeAll { it.id == id }
                val content = json.encodeToString(currentLogs)
                logFile.writeText(content)
                Log.d(TAG, "Deleted swim log: $id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete swim log", e)
        }
    }

    @Synchronized
    fun clearAllLogs() {
        try {
            val dir = File(context.filesDir, "swim_logs")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all logs", e)
        }
    }
}
