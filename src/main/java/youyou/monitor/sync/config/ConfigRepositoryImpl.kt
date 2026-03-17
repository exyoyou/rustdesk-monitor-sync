package youyou.monitor.sync.config

import android.content.Context
import kotlinx.coroutines.flow.Flow
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.config.model.WebDavServer
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.logger.Log
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
    private val remoteSyncManager = RemoteConfigSyncManager(isDebugBuild)

    private var webdavClient: WebDavClient? = null
    private var deviceIdProvider: (() -> String)? = null
    private var onWebDavServersChanged: ((List<WebDavServer>, WebDavServer?, WebDavClient?) -> Unit)? = null

    override fun getConfigFlow(): Flow<MonitorConfig> = delegate.getConfigFlow()

    override suspend fun getCurrentConfig(): MonitorConfig = delegate.getCurrentConfig()

    override suspend fun updateConfig(config: MonitorConfig) {
        delegate.updateConfig(config)
    }

    override suspend fun syncFromRemote(): Result<Unit> {
        val currentConfig = delegate.getCurrentConfig()
        remoteSyncManager.setDeviceIdProvider(deviceIdProvider)

        return remoteSyncManager.syncFromRemote(currentConfig)
            .mapCatching { result ->
                delegate.updateConfig(result.config)

                webdavClient?.takeIf { it !== result.fastestClient }?.close()
                webdavClient = result.fastestClient
                onWebDavServersChanged?.invoke(
                    result.config.webdavServers,
                    result.fastestServer,
                    result.fastestClient
                )
                Unit
            }
    }

    fun setWebDavClient(client: WebDavClient) {
        webdavClient?.takeIf { it !== client }?.close()
        webdavClient = client
        Log.d(TAG, "WebDAV客户端已配置")
    }

    fun setDeviceIdProvider(provider: (() -> String)?) {
        deviceIdProvider = provider
        remoteSyncManager.setDeviceIdProvider(provider)
        Log.d(TAG, "DeviceIdProvider已配置")
    }

    fun setOnWebDavServersChanged(callback: (List<WebDavServer>, WebDavServer?, WebDavClient?) -> Unit) {
        onWebDavServersChanged = callback
        Log.d(TAG, "WebDAV服务器变化回调已注册")
    }
}
