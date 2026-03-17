package youyou.monitor.sync.config

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.config.model.WebDavServer
import youyou.monitor.logger.Log
import youyou.monitor.webdav.WebDavClient

class RemoteConfigSyncManager(
    private val isDebugBuild: Boolean
) {
    companion object {
        private const val TAG = "RemoteConfigSyncManager"
        private const val CONFIG_FILE_NAME = "config.json"
        private const val DEBUG_CONFIG_FILE_NAME = "debug_config.json"
        private const val FASTEST_RECHECK_INTERVAL_MS = 5 * 60 * 1000L
    }

    data class SyncResult(
        val config: MonitorConfig,
        val fastestServer: WebDavServer,
        val fastestClient: WebDavClient
    )

    private var deviceIdProvider: (() -> String)? = null
    private val clientPool = ConcurrentHashMap<String, WebDavClient>()

    @Volatile
    private var lastFastestKey: String? = null

    @Volatile
    private var lastFastestCheckedAtMs: Long = 0L

    fun setDeviceIdProvider(provider: (() -> String)?) {
        deviceIdProvider = provider
    }

    suspend fun syncFromRemote(currentConfig: MonitorConfig): Result<SyncResult> {
        return try {
            Log.d(TAG, "正在从远程同步配置...")

            val servers = currentConfig.webdavServers.filter { it.url.isNotEmpty() }

            if (servers.isEmpty()) {
                Log.w(TAG, "未配置WebDAV服务器")
                return Result.failure(Exception("No WebDAV servers configured"))
            }

            pruneClientPool(servers)

            val now = System.currentTimeMillis()
            val cachedKey = lastFastestKey
            if (
                cachedKey != null &&
                now - lastFastestCheckedAtMs < FASTEST_RECHECK_INTERVAL_MS
            ) {
                val cachedServer = servers.firstOrNull { serverKey(it) == cachedKey }
                if (cachedServer != null) {
                    val cachedClient = getOrCreateClient(cachedServer)
                    val cachedConfig = tryDownloadConfig(cachedClient, cachedServer)
                    if (cachedConfig != null) {
                        Log.i(TAG, "使用缓存最快服务器同步成功: ${cachedServer.url}")
                        return Result.success(SyncResult(cachedConfig, cachedServer, cachedClient))
                    }
                    Log.w(TAG, "缓存最快服务器不可用，回退到全量测速")
                }
            }

            val serverResults = mutableListOf<Triple<WebDavServer, WebDavClient, Long>>()

            for (server in servers) {
                try {
                    val client = getOrCreateClient(server)

                    val startTime = System.currentTimeMillis()
                    val connected = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime

                    if (connected) {
                        serverResults.add(Triple(server, client, responseTime))
                        Log.d(TAG, "服务器 ${server.url} 在 ${responseTime}ms 内响应")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试 ${server.url} 失败: ${e.message}")
                }
            }

            if (serverResults.isEmpty()) {
                return Result.failure(Exception("All WebDAV servers failed to connect"))
            }

            serverResults.sortBy { it.third }
            val (fastestServer, fastestClient, _) = serverResults.first()

            lastFastestKey = serverKey(fastestServer)
            lastFastestCheckedAtMs = now

            val config = tryDownloadConfig(fastestClient, fastestServer)
                ?: run {
                    Log.w(TAG, "最快服务器下载配置失败，尝试按速度依次回退")

                    val fallback = serverResults.drop(1).firstNotNullOfOrNull { (server, client, _) ->
                        tryDownloadConfig(client, server)?.let { server to Pair(client, it) }
                    }

                    if (fallback == null) {
                        return Result.failure(Exception("Config file not found: ${if (isDebugBuild) DEBUG_CONFIG_FILE_NAME else CONFIG_FILE_NAME}"))
                    }

                    val (server, pair) = fallback
                    val (client, cfg) = pair
                    lastFastestKey = serverKey(server)
                    Log.i(TAG, "已切换回退服务器同步成功: ${server.url}")
                    return Result.success(SyncResult(cfg, server, client))
                }

            Log.i(TAG, "配置已从最快服务器同步成功: ${fastestServer.url}")
            Result.success(SyncResult(config, fastestServer, fastestClient))
        } catch (e: Exception) {
            Log.e(TAG, "从远程同步配置失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun tryDownloadConfig(client: WebDavClient, server: WebDavServer): MonitorConfig? {
        val remoteConfigFileName = if (isDebugBuild) DEBUG_CONFIG_FILE_NAME else CONFIG_FILE_NAME
        val remotePath = "/" + server.monitorDir.trim('/')
        val data = client.downloadFile(remotePath, remoteConfigFileName)

        if (data.isEmpty()) {
            Log.w(TAG, "配置文件不存在或为空: ${server.url}/$remoteConfigFileName")
            return null
        }

        return try {
            parseConfig(String(data, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "解析配置失败 ${server.url}: ${e.message}")
            null
        }
    }

    private fun serverKey(server: WebDavServer): String {
        return "${server.url}|${server.username}|${server.monitorDir}|${server.remoteUploadDir}|${server.templateDir}"
    }

    private fun getOrCreateClient(server: WebDavServer): WebDavClient {
        val key = serverKey(server)
        return clientPool[key] ?: synchronized(clientPool) {
            clientPool[key] ?: WebDavClient.fromServer(server, deviceIdProvider).also {
                clientPool[key] = it
            }
        }
    }

    private fun pruneClientPool(servers: List<WebDavServer>) {
        val valid = servers.map { serverKey(it) }.toSet()
        clientPool.entries.removeIf { entry ->
            val shouldRemove = entry.key !in valid
            if (shouldRemove) {
                try {
                    entry.value.close()
                } catch (_: Exception) {
                }
            }
            shouldRemove
        }

        val fastest = lastFastestKey
        if (fastest != null && fastest !in valid) {
            lastFastestKey = null
        }
    }

    private fun parseConfig(json: String): MonitorConfig {
        val obj = JSONObject(json)

        val webdavServers = mutableListOf<WebDavServer>()
        val serversArray = obj.optJSONArray("webdavServers")
        if (serversArray != null) {
            for (i in 0 until serversArray.length()) {
                val serverObj = serversArray.getJSONObject(i)
                webdavServers.add(
                    WebDavServer(
                        url = serverObj.optString("url", ""),
                        username = serverObj.optString("username", ""),
                        password = serverObj.optString("password", ""),
                        monitorDir = serverObj.optString("monitorDir", "Monitor"),
                        remoteUploadDir = serverObj.optString("remoteUploadDir", "Monitor/upload"),
                        templateDir = serverObj.optString("templateDir", "Templates")
                    )
                )
            }
        }

        return MonitorConfig(
            matchThreshold = obj.optDouble("matchThreshold", 0.92),
            matchCooldownMs = obj.optLong("matchCooldownMs", 3000L),
            detectPerSecond = obj.optLong("detectPerSecond", 1L),
            maxStorageSizeMB = obj.optInt("maxStorageSizeMB", 1024),
            screenshotDir = obj.optString("screenshotDir", "ScreenCaptures"),
            videoDir = obj.optString("videoDir", "ScreenRecord"),
            templateDir = obj.optString("templateDir", "Templates"),
            matcherType = obj.optString("matcherType", "grayscale"),
            preferExternalStorage = obj.optBoolean("preferExternalStorage", false),
            rootDir = obj.optString("rootDir", "PingerLove"),
            webdavServers = webdavServers
        )
    }
}
