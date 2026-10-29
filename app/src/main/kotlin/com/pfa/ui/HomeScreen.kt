package com.pfa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pfa.domain.CoreType
import com.pfa.domain.ProxyPaths
import com.pfa.viewmodel.MainViewModel

/**
 * 首页：启动/停止开关、运行状态、选择核心、选择配置文件、运行参数、日志。
 */
@Composable
fun HomeScreen(vm: MainViewModel) {
    val profile by vm.profile.collectAsState()
    val running by vm.running.collectAsState()
    val status by vm.status.collectAsState()
    val log by vm.log.collectAsState()
    val rootAvailable by vm.rootAvailable.collectAsState()
    val bins by vm.bins.collectAsState()
    val configFiles by vm.configFiles.collectAsState()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PFA", style = MaterialTheme.typography.headlineSmall)

        if (!rootAvailable) {
            Card {
                Text(
                    "未获得 root 权限。请在授权弹窗中允许，或确认已安装 Magisk/KernelSU/APatch。",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ---------- 启停 + 运行状态 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "运行中" else "已停止",
                            style = MaterialTheme.typography.titleMedium
                        )
                        status?.let {
                            if (it.running) {
                                Text(
                                    "PID: ${it.pid}    内存: ${it.memKb ?: "?"} KB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Switch(
                        checked = running,
                        onCheckedChange = { on -> if (on) vm.start() else vm.stop() },
                        enabled = rootAvailable
                    )
                }
            }
        }

        // ---------- 启动配置 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("启动配置", style = MaterialTheme.typography.titleMedium)

                EnumDropdown(
                    label = "代理核心",
                    value = profile.core,
                    values = CoreType.entries.toList(),
                    display = { core ->
                        if (bins.contains(core.binaryName)) core.binaryName
                        else "${core.binaryName}（未上传）"
                    }
                ) { vm.selectCore(it) }

                Text(
                    if (bins.contains(profile.core.binaryName))
                        "核心路径: ${ProxyPaths.BIN_DIR}/${profile.core.binaryName}"
                    else
                        "提示: 请先到「管理」页上传 ${profile.core.binaryName} 到 ${ProxyPaths.BIN_DIR}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bins.contains(profile.core.binaryName))
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )

                StringDropdown(
                    label = "配置文件",
                    value = profile.configFile,
                    values = configFiles
                ) { vm.selectConfig(it) }

                LabeledTextField(
                    label = "运行参数（完整核心启动参数，可留空）",
                    value = profile.extraArgs,
                    singleLine = false,
                    onValueChange = { vm.updateExtraArgs(it) }
                )

                Text(
                    "填写的参数将原样作为核心启动参数：/data/adb/proxy/bin/" +
                        "${profile.core.binaryName} ${profile.extraArgs.trim()}\n" +
                        "例：-d /data/adb/proxy/run　留空则自动附加选中配置文件的默认参数",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 日志 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium)
                Text(
                    log.ifBlank { "（暂无日志）" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}
