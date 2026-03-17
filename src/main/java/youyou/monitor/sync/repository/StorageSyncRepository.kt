package youyou.monitor.sync.repository

import java.io.File

interface StorageSyncRepository {
    suspend fun listScreenshots(): Result<List<File>>
    suspend fun listVideos(): Result<List<File>>
    fun getScreenshotDirectory(): File
    fun getVideoDirectory(): File
    suspend fun getTotalSize(): Result<Long>
    suspend fun deleteOldestFiles(bytes: Long): Result<Int>
}
