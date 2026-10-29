package com.pfa.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pfa.data.ProfileStore
import com.pfa.data.SettingsStore
import com.pfa.domain.CoreType
import com.pfa.domain.Profile
import com.pfa.domain.ProxyPaths
import com.pfa.domain.ProxySettings
import com.pfa.root.CoreStatus
import com.pfa.root.FileManager
import com.pfa.root.ProxyManager
import com.pfa.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 导入文件的目标类型 */
enum class FileType { CORE, CONFIG }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)
    private val settingsStore = SettingsStore(app)
    private val proxy = ProxyManager(app)
    private val files = FileManager(app)

    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private val _settings = MutableStateFlow(ProxySettings())
    val settings: StateFlow<ProxySettings> = _settings.asStateFlow()

    private val _bins = MutableStateFlow<List<String>>(emptyList())
    val bins: StateFlow<List<String>> = _bins.asStateFlow()

    private val _configFiles = MutableStateFlow<List<String>>(emptyList())
    val configFiles: StateFlow<List<String>> = _configFiles.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow<CoreStatus?>(null)
    val status: StateFlow<CoreStatus?> = _status.asStateFlow()

    /** 持久化数据是否已加载完成（设置页文本框初始化依赖此标记） */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 请求 root：建立 su 会话并触发 Magisk/KSU/APatch 授权弹窗
            _rootAvailable.value = RootShell.requestRoot()
            appendLog(if (_rootAvailable.value) "已获得 root 权限" else "未获得 root 权限，请在授权弹窗中允许")
            if (!_rootAvailable.value) return@launch

            // 2. 初始化工作目录
            RootShell.runScript(
                "mkdir -p '${ProxyPaths.BIN_DIR}' '${ProxyPaths.PROFILES_DIR}' '${ProxyPaths.RUN_DIR}'"
            )

            // 3. 加载持久化数据与文件列表
            _profile.value = store.loadAll().firstOrNull() ?: Profile()
            _settings.value = settingsStore.load()
            refreshFiles().join()
            _loaded.value = true

            // 4. 若核心已在运行（上次会话残留），恢复状态显示
            pollOnce()
            if (_status.value?.running == true) {
                _running.value = true
                appendLog("检测到核心已在运行 (PID ${_status.value?.pid})")
                launch { pollLoop() }
            }
        }
    }

    // ---------- 首页选择 ----------
    /** 选择核心：下拉项来自 bin 目录已上传的二进制文件名，类型按名称智能识别 */
    fun selectCoreBinary(binaryName: String) {
        _profile.value = _profile.value.copy(
            core = CoreType.detect(binaryName),
            binaryName = binaryName
        )
        persistProfile()
    }

    fun selectConfig(name: String) {
        _profile.value = _profile.value.copy(configFile = name)
        persistProfile()
    }

    fun updateExtraArgs(args: String) {
        _profile.value = _profile.value.copy(extraArgs = args)
    }

    fun persistProfile() = viewModelScope.launch(Dispatchers.IO) {
        store.upsert(_profile.value)
    }

    // ---------- 设置页 ----------
    fun updateSettings(s: ProxySettings) {
        _settings.value = s
        viewModelScope.launch(Dispatchers.IO) { settingsStore.save(s) }
    }

    /** 包名列表文本（每行一个包名） */
    fun updatePackageListText(text: String) {
        val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        updateSettings(_settings.value.copy(packageList = list))
    }

    /** IPv4 内网排除段文本（每行一个 CIDR） */
    fun updateIntranetText(text: String) {
        val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        updateSettings(_settings.value.copy(intranet = list))
    }

    /** IPv6 内网排除段文本（每行一个 CIDR） */
    fun updateIntranet6Text(text: String) {
        val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        updateSettings(_settings.value.copy(intranet6 = list))
    }

    // ---------- 启停 ----------
    fun start() = viewModelScope.launch(Dispatchers.IO) {
        val p = _profile.value
        appendLog(">> 启动 ${p.bin}，配置 ${p.configFile.ifBlank { "（未选择）" }} ...")
        val r = proxy.start(p, _settings.value)
        appendLog(r.message)
        if (r.ok) {
            _running.value = true
            launch { pollLoop() }
        }
    }

    fun stop() = viewModelScope.launch(Dispatchers.IO) {
        appendLog(">> 停止 ...")
        val msg = proxy.stop(_profile.value, _settings.value)
        appendLog(msg)
        _running.value = false
        _status.value = null
    }

    fun reset() = viewModelScope.launch(Dispatchers.IO) {
        val msg = proxy.reset()
        appendLog(msg)
        _running.value = false
        _status.value = null
    }

    // ---------- 管理页 ----------
    fun refreshFiles() = viewModelScope.launch(Dispatchers.IO) {
        _bins.value = files.listDir(ProxyPaths.BIN_DIR)
        _configFiles.value = files.listDir(ProxyPaths.PROFILES_DIR)
    }

    fun importFile(uri: Uri, type: FileType) = viewModelScope.launch(Dispatchers.IO) {
        val (dir, label) = if (type == FileType.CORE) {
            ProxyPaths.BIN_DIR to "核心"
        } else {
            ProxyPaths.PROFILES_DIR to "配置"
        }
        appendLog(">> 导入$label ...")
        files.import(uri, dir, executable = type == FileType.CORE)
            .onSuccess { appendLog("$label 导入成功: $it"); refreshFiles() }
            .onFailure { appendLog("$label 导入失败: ${it.message}") }
    }

    fun loadConfig(name: String, onLoaded: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        files.readText("${ProxyPaths.PROFILES_DIR}/$name")
            .onSuccess(onLoaded)
            .onFailure { appendLog("读取配置失败: ${it.message}") }
    }

    fun saveConfig(name: String, content: String, onSaved: () -> Unit) =
        viewModelScope.launch(Dispatchers.IO) {
            files.writeText("${ProxyPaths.PROFILES_DIR}/$name", content)
                .onSuccess { appendLog("配置已保存: $name"); onSaved() }
                .onFailure { appendLog("保存失败: ${it.message}") }
        }

    fun deleteFile(dir: String, name: String) = viewModelScope.launch(Dispatchers.IO) {
        if (files.delete("$dir/$name")) {
            appendLog("已删除: $dir/$name")
            if (dir == ProxyPaths.PROFILES_DIR && _profile.value.configFile == name) {
                selectConfig("")
            }
            if (dir == ProxyPaths.BIN_DIR && _profile.value.bin == name) {
                // 删除的是当前选中的核心：清空选择，待用户重新选择
                _profile.value = _profile.value.copy(binaryName = "")
                persistProfile()
            }
            refreshFiles()
        } else {
            appendLog("删除失败: $name")
        }
    }

    // ---------- 日志 / 轮询 ----------
    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.value = (_log.value + "\n[$ts] " + msg).trimStart('\n')
    }

    private suspend fun pollLoop() {
        while (_running.value) {
            pollOnce()
            delay(2000)
        }
    }

    private suspend fun pollOnce() {
        val s = proxy.status(_profile.value.bin)
        _status.value = s
        if (!s.running) _running.value = false
    }
}
