package youyou.monitor.sync.config

import android.content.Context
import kotlinx.coroutines.flow.Flow
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.config.model.WebDavServer
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.logger.Log
import youyou.monitor.webdav.AndroidNetworkStateProvider
import youyou.monitor.webdav.WebDavClient

/**
 * 配置仓储兼容实现。
 *
 * 复用 `monitor-config` 的本地配置存储能力，
 * 并在 `monitor-sync` 中承接远程同步与最快 WebDAV 服务器选择。
 */
class ConfigRepositoryImpl(
    context: Context,
    isDebugBuild: Boolean
) : ConfigRepository {

    companion object {
        const val TAG = "ConfigRepository"
    }

    private val delegate = youyou.monitor.config.repository.ConfigRepositoryImpl(context)
    private val networkStateProvider = AndroidNetworkStateProvider(context.applicationContext)
    private val remoteSyncManager = RemoteConfigSyncManager(isDebugBuild, networkStateProvider)

    private var webdavClient: WebDavClient? = null
    private var deviceIdProvider: (() -> String)? = null
    private var onWebDavServersChanged: ((List<WebDavServer>, WebDavServer?, WebDavClient?) -> Unit)? = null

    /** 最近一次远程拉取成功的时间（毫秒），所有拉取路径都会更新，供看门狗判断拉取是否正常 */
    @Volatile
    var lastSyncSuccessAt: Long = 0L
        private set

    override fun getConfigFlow(): Flow<MonitorConfig> = delegate.getConfigFlow()

    override suspend fun getCurrentConfig(): MonitorConfig = delegate.getCurrentConfig()

    override suspend fun updateConfig(config: MonitorConfig) {
        delegate.updateConfig(config)
    }

    override suspend fun syncFromRemote(): Result<Unit> {
        val currentConfig = delegate.getCurrentConfig()
        remoteSyncManager.setDeviceIdProvider(deviceIdProvider)

        return remoteSyncManager.syncFromRemote(currentConfig)
            .mapCatching { synced ->
                // 远程配置漏写或写错 webdavServers 时沿用本地列表，否则清空后再也连不上服务器拉配置
                val result = if (synced.config.webdavServers.isEmpty()) {
                    Log.w(TAG, "远程配置没有 webdavServers，沿用本地服务器列表")
                    synced.copy(config = synced.config.copy(webdavServers = currentConfig.webdavServers))
                } else {
                    synced
                }
                delegate.updateConfig(result.config)

                // 不立即关闭旧客户端，避免正在传输时被中断。
                webdavClient = result.fastestClient
                onWebDavServersChanged?.invoke(
                    result.config.webdavServers,
                    result.fastestServer,
                    result.fastestClient
                )
                lastSyncSuccessAt = System.currentTimeMillis()
                Unit
            }
    }

    fun setWebDavClient(client: WebDavClient) {
        // 不立即关闭旧客户端，避免正在传输时被中断。
        webdavClient = client
        Log.d(TAG, "WebDAV客户端已配置")
    }

    fun setDeviceIdProvider(provider: (() -> String)?) {
        deviceIdProvider = provider
        remoteSyncManager.setDeviceIdProvider(provider)
        Log.d(TAG, "DeviceIdProvider已配置")
    }

    fun createWebDavClient(server: WebDavServer): WebDavClient {
        return WebDavClient.fromServer(server, deviceIdProvider, networkStateProvider)
    }

    fun setOnWebDavServersChanged(callback: (List<WebDavServer>, WebDavServer?, WebDavClient?) -> Unit) {
        onWebDavServersChanged = callback
        Log.d(TAG, "WebDAV服务器变化回调已注册")
    }
}
