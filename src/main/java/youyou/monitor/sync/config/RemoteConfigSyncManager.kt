package youyou.monitor.sync.config

import org.json.JSONObject
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
    }

    data class SyncResult(
        val config: MonitorConfig,
        val fastestServer: WebDavServer,
        val fastestClient: WebDavClient
    )

    private var deviceIdProvider: (() -> String)? = null

    fun setDeviceIdProvider(provider: (() -> String)?) {
        deviceIdProvider = provider
    }

    suspend fun syncFromRemote(currentConfig: MonitorConfig): Result<SyncResult> {
        return try {
            Log.d(TAG, "正在从远程同步配置...")

            if (currentConfig.webdavServers.isEmpty()) {
                Log.w(TAG, "未配置WebDAV服务器")
                return Result.failure(Exception("No WebDAV servers configured"))
            }

            val serverResults = mutableListOf<Pair<WebDavServer, Long>>()
            val tempClients = mutableListOf<WebDavClient>()

            for (server in currentConfig.webdavServers) {
                if (server.url.isEmpty()) continue

                try {
                    val client = WebDavClient.fromServer(server, deviceIdProvider)
                    tempClients.add(client)

                    val startTime = System.currentTimeMillis()
                    val connected = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime

                    if (connected) {
                        serverResults.add(server to responseTime)
                        Log.d(TAG, "服务器 ${server.url} 在 ${responseTime}ms 内响应")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试 ${server.url} 失败: ${e.message}")
                }
            }

            if (serverResults.isEmpty()) {
                tempClients.forEach { it.close() }
                return Result.failure(Exception("All WebDAV servers failed to connect"))
            }

            serverResults.sortBy { it.second }
            val fastestServer = serverResults.first().first
            val fastestClient = WebDavClient.fromServer(fastestServer, deviceIdProvider)

            tempClients.forEach { client ->
                if (client.webdavUrl != fastestClient.webdavUrl) {
                    client.close()
                }
            }

            val remoteConfigFileName = if (isDebugBuild) DEBUG_CONFIG_FILE_NAME else CONFIG_FILE_NAME
            val remotePath = "/" + fastestServer.monitorDir.trim('/')
            val data = fastestClient.downloadFile(remotePath, remoteConfigFileName)

            if (data.isEmpty()) {
                fastestClient.close()
                return Result.failure(Exception("Config file not found: $remoteConfigFileName"))
            }

            val config = parseConfig(String(data, Charsets.UTF_8))
            Log.i(TAG, "配置已从最快服务器同步成功: ${fastestServer.url}")
            Result.success(SyncResult(config, fastestServer, fastestClient))
        } catch (e: Exception) {
            Log.e(TAG, "从远程同步配置失败: ${e.message}", e)
            Result.failure(e)
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
