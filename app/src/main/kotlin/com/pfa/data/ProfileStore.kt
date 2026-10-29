package com.pfa.data

import android.content.Context
import com.pfa.domain.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 多 Profile 持久化（首页的启动档案：核心 + 配置文件 + 运行参数）。
 * MVP 使用应用内部存储的 JSON 文件，后续可平滑替换为 Room。
 */
class ProfileStore(context: Context) {

    private val file = File(context.filesDir, "profiles.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun loadAll(): List<Profile> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext listOf(defaultProfile())
        runCatching {
            json.decodeFromString(ListSerializer(Profile.serializer()), file.readText())
        }.getOrDefault(listOf(defaultProfile()))
    }

    suspend fun saveAll(profiles: List<Profile>) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(ListSerializer(Profile.serializer()), profiles))
    }

    suspend fun upsert(profile: Profile) = withContext(Dispatchers.IO) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        saveAll(list)
    }

    private fun defaultProfile() = Profile(
        id = UUID.randomUUID().toString(),
        name = "默认"
    )
}
