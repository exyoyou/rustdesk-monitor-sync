package youyou.monitor.sync.storage

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.sync.repository.StorageSyncRepository
import java.io.File

/**
 * 同步与屏幕模块共用的存储仓储接口。
 */
interface StorageRepository : StorageSyncRepository {
    suspend fun saveScreenshot(bitmap: Bitmap, filename: String): Result<String>
    suspend fun getPendingUploadFiles(): List<String>
    fun getRootDirPath(): String
    fun getRootDir(): File
    fun updateConfig(config: MonitorConfig)
}
