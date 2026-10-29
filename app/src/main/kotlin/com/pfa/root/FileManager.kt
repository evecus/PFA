package com.pfa.root

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * /data/adb/proxy 下的文件管理：列出 / 导入 / 读写 / 删除。
 * 所有对 root 目录的写入都经由「app 缓存文件 + root cp」完成，
 * 避免 shell 转义问题，也无需应用持有存储权限。
 */
class FileManager(private val context: Context) {

    /** 列出目录下的文件名（忽略隐藏文件） */
    suspend fun listDir(dir: String): List<String> {
        val r = RootShell.runScript("ls -1p '$dir' 2>/dev/null | grep -v /")
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 从 content:// Uri 导入文件到目标 root 目录。
     * @param executable 是否赋予 755 执行权限（核心二进制为 true）
     * @return 目标绝对路径
     */
    suspend fun import(uri: Uri, destDir: String, executable: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryName(uri) ?: "import_${System.currentTimeMillis()}"
                // 剔除路径分隔符，防止目录穿越
                val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                if (safeName.isBlank()) error("无法确定文件名")

                val tmp = File(context.cacheDir, "upload_$safeName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")

                val dest = "$destDir/$safeName"
                val r = RootShell.runScript(
                    "mkdir -p '$destDir' && cp -f '${tmp.absolutePath}' '$dest' && " +
                        (if (executable) "chmod 755 '$dest' && " else "") +
                        "echo IMPORT_OK"
                )
                tmp.delete()
                if (!r.ok || "IMPORT_OK" !in r.stdout) {
                    error("写入 $dest 失败:\n${r.stderr}")
                }
                dest
            }
        }

    /** 读取 root 目录下的文本文件（配置文件编辑用） */
    suspend fun readText(path: String): Result<String> {
        val r = RootShell.runScript("cat '$path' 2>/dev/null")
        return if (r.ok && r.stdout.isNotEmpty()) {
            Result.success(r.stdout)
        } else {
            Result.failure(Exception("读取失败: $path\n${r.stderr.ifBlank { "文件不存在或为空" }}"))
        }
    }

    /** 保存文本到 root 目录下的文件（经 app 缓存 + root cp，避免转义问题） */
    suspend fun writeText(path: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(context.cacheDir, "edit_${System.currentTimeMillis()}")
                tmp.writeText(content)
                val r = RootShell.runScript("cp -f '${tmp.absolutePath}' '$path' && echo SAVE_OK")
                tmp.delete()
                if (!r.ok || "SAVE_OK" !in r.stdout) error("保存失败:\n${r.stderr}")
            }
        }

    /** 删除 root 目录下的文件 */
    suspend fun delete(path: String): Boolean {
        val r = RootShell.runScript("rm -f '$path' && echo DEL_OK")
        return r.ok && "DEL_OK" in r.stdout
    }

    /** 从 content:// Uri 查询原始文件名 */
    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}
