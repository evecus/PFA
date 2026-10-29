package com.pfa.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * libsu 封装：所有特权操作都通过 root shell 执行。
 *
 * root 检测说明：libsu 的 Shell.isAppGrantedRoot() 在 shell 尚未创建时返回 null，
 * 必须先调用 Shell.getShell() 建立会话（会触发 Magisk/KernelSU/APatch 的授权弹窗），
 * 之后再查询才有效。应用启动时调用 [requestRoot] 完成此流程。
 */
object RootShell {

    /**
     * 请求 root：阻塞建立 su 会话并触发授权弹窗，返回是否已授权。
     * 必须在 IO 线程调用（getShell 为阻塞操作）。
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shell.getShell()
            Shell.isAppGrantedRoot() == true
        }.getOrDefault(false)
    }

    /** 是否已获得 root（需先调用过 [requestRoot]） */
    fun isRootAvailable(): Boolean = Shell.isAppGrantedRoot() == true

    /**
     * 执行一段 shell 脚本（多行，支持分号与换行）。
     * 以 `sh -c` 运行可保留重定向/管道等 shell 语义。
     */
    suspend fun runScript(script: String): ShellResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd("sh", "-c", script).exec()
        ShellResult(
            ok = result.isSuccess,
            stdout = result.out.joinToString("\n"),
            stderr = result.err.joinToString("\n")
        )
    }
}

data class ShellResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String
)
