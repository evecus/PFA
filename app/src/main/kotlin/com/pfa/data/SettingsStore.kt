package com.pfa.data

import android.content.Context
import com.pfa.domain.ProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 全局设置持久化（设置页，对应 box.config 的防火墙/路由/DNS 选项）。
 */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun load(): ProxySettings = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ProxySettings()
        runCatching {
            json.decodeFromString(ProxySettings.serializer(), file.readText())
        }.getOrDefault(ProxySettings())
    }

    suspend fun save(settings: ProxySettings) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(ProxySettings.serializer(), settings))
    }
}
