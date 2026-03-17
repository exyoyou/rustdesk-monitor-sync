package youyou.monitor.sync.template

import youyou.monitor.logger.Log
import youyou.monitor.webdav.WebDavClient
import java.io.File

class RemoteTemplateSyncManager(
    private val getTemplateDir: () -> File,
    private val listLocalTemplateFiles: () -> List<File>,
    private val normalizeLocalToRemote: (String) -> String,
    private val remoteToLocalName: (String, Boolean) -> String,
    private val saveTemplate: suspend (String, ByteArray) -> Unit,
    private val notifyTemplatesUpdated: () -> Unit
) {
    companion object {
        private const val TAG = "RemoteTemplateSyncMgr"
    }

    private var webdavClient: WebDavClient? = null
    private var baseRemoteDir: String = "Templates"
    private var matcherType: String = "grayscale"

    fun isConfigured(): Boolean = webdavClient != null

    fun setWebDavClient(client: WebDavClient, baseRemoteDir: String, matcherType: String) {
        webdavClient = client
        this.baseRemoteDir = baseRemoteDir
        this.matcherType = matcherType
        Log.d(TAG, "WebDAV configured: baseRemoteDir=$baseRemoteDir, matcherType=$matcherType")
    }

    fun updateMatcherType(matcherType: String) {
        this.matcherType = matcherType
    }

    suspend fun syncFromRemote(preferExternalStorage: Boolean): Result<Int> {
        return try {
            val client = webdavClient
                ?: return Result.failure(Exception("WebDAV client not configured"))

            val remoteTemplateDir = "$baseRemoteDir/$matcherType"
            val remoteFilesWithSizes = client.listDirectoryWithSizes(remoteTemplateDir)
            if (remoteFilesWithSizes.isEmpty()) {
                return Result.success(0)
            }

            val localDir = getTemplateDir()
            if (localDir.exists() && localDir.isDirectory) {
                val localFiles = listLocalTemplateFiles()
                val remoteFileNames = remoteFilesWithSizes.map { it.first }.toSet()
                val filesToDelete = localFiles.filter { local ->
                    !remoteFileNames.contains(normalizeLocalToRemote(local.name))
                }

                filesToDelete.forEach { file ->
                    try {
                        file.delete()
                    } catch (_: Exception) {
                    }
                }
            }

            var syncCount = 0
            for ((fileName, remoteSize) in remoteFilesWithSizes) {
                try {
                    val localName = remoteToLocalName(fileName, preferExternalStorage)
                    val localFile = File(getTemplateDir(), localName)
                    if (localFile.exists() && localFile.length() == remoteSize) {
                        continue
                    }

                    val data = client.downloadFile(remoteTemplateDir, fileName)
                    if (data.isNotEmpty()) {
                        saveTemplate(localName, data)
                        syncCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "下载模板失败: $fileName - ${e.message}")
                }
            }

            if (syncCount > 0) {
                notifyTemplatesUpdated()
            }

            Result.success(syncCount)
        } catch (e: Exception) {
            Log.e(TAG, "模板同步失败: ${e.message}", e)
            Result.failure(e)
        }
    }
}
