package youyou.monitor.sync.repository

interface TemplateSyncRepository {
    suspend fun syncFromRemote(): Result<Int>
}
