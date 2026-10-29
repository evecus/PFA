package com.pfa.root

import android.content.Context
import com.pfa.domain.CoreType
import com.pfa.domain.FirewallType
import com.pfa.domain.Profile
import com.pfa.domain.ProxyMethod
import com.pfa.domain.ProxyPaths
import com.pfa.domain.ProxySettings
import kotlinx.coroutines.delay
import java.io.File

/**
 * 代理核心管理：启动 / 停止 / 状态 / 还原。
 *
 * 工作目录体系：
 *  - 核心：/data/adb/proxy/bin/<binaryName>
 *  - 配置：/data/adb/proxy/profiles/<configFile>（上传后存放）
 *  - 运行：/data/adb/proxy/run/<configFile>（启动前复制进来），pid 文件也在 run/
 *
 * 命令逻辑严格对照参考模块 box4：
 *  - 启动流程：校验二进制 -> 复制配置到 run/ -> (sing-box check) -> setsid 起核心(写 pid)
 *              -> 等待进程 -> 配置策略路由 -> 加载 nftables
 *  - 停止流程：kill 进程 -> 清空路由表/规则 -> 删除 nft 表
 *  - 策略路由：fwmark + 自定义路由表（tproxy: local default dev lo / tun: default dev tun0）
 *  - 防火墙：独立 `table ip/ip6 box`，mangle 做 TPROXY、nat 做 REDIRECT/DNS 劫持、filter 放行 tun
 */
class ProxyManager(private val context: Context) {

    private val nftFilePath = File(context.filesDir, "box.nft").absolutePath

    fun isRootAvailable(): Boolean = RootShell.isRootAvailable()

    // ===================== 启动 =====================
    suspend fun start(profile: Profile, settings: ProxySettings): StartResult {
        // 1. 校验核心二进制
        val binPath = "${ProxyPaths.BIN_DIR}/${profile.core.binaryName}"
        val binCheck = RootShell.runScript(
            "[ -x '$binPath' ] && echo OK || { [ -f '$binPath' ] && echo NOEXEC || echo MISSING; }"
        )
        when {
            "MISSING" in binCheck.stdout ->
                return StartResult(false, "核心未找到：$binPath\n请先在「管理」页上传对应核心二进制")
            "NOEXEC" in binCheck.stdout -> {
                RootShell.runScript("chmod 755 '$binPath'")
                val re = RootShell.runScript("[ -x '$binPath' ] && echo OK || echo FAIL")
                if ("OK" !in re.stdout)
                    return StartResult(false, "核心无执行权限且自动 chmod 失败：$binPath")
            }
            "OK" !in binCheck.stdout ->
                return StartResult(false, "核心检测失败：$binPath\n${binCheck.stderr}")
        }

        // 2. 校验并复制配置文件到运行目录
        if (profile.configFile.isBlank())
            return StartResult(false, "未选择配置文件，请先在首页选择或到「管理」页上传")

        val srcConfig = "${ProxyPaths.PROFILES_DIR}/${profile.configFile}"
        val runConfig = "${ProxyPaths.RUN_DIR}/${profile.configFile}"
        val cp = RootShell.runScript(
            "mkdir -p '${ProxyPaths.RUN_DIR}' && cp -f '$srcConfig' '$runConfig' && echo CP_OK"
        )
        if (!cp.ok || "CP_OK" !in cp.stdout)
            return StartResult(
                false,
                "配置文件复制到运行目录失败:\n$srcConfig -> $runConfig\n${cp.stderr}"
            )

        // 3. 生成 nft 规则文件（app 私有目录，root 可读）
        if (settings.firewall == FirewallType.NFTABLES) {
            File(context.filesDir, "box.nft").writeText(buildNftScript(settings))
        }

        // 4. 启动前配置检查（仅 sing-box 且使用默认参数时；自定义参数场景不做预检）
        if (settings.startCheck && profile.core == CoreType.SING_BOX && profile.extraArgs.isBlank()) {
            val chk = RootShell.runScript("'$binPath' check -c '$runConfig' 2>&1")
            val out = (chk.stdout + "\n" + chk.stderr).trim()
            if (!chk.ok || out.contains("error", ignoreCase = true))
                return StartResult(false, "sing-box 配置检查未通过:\n$out")
        }

        // 5. 启动核心
        val launch = buildLaunchCommand(profile)
        RootShell.runScript(launch)

        // 6. 等待进程并取 PID
        val pid = waitForPid(profile.core.binaryName)
        if (pid == null) {
            return StartResult(false, "核心启动失败，请检查配置文件与运行参数（可尝试关闭启动前检查）")
        }

        // 7. 配置策略路由
        val route = applyRouting(settings)
        if (!route.ok) {
            return StartResult(false, "策略路由配置失败:\n${route.stderr}", pid)
        }

        // 8. 加载防火墙
        if (settings.firewall == FirewallType.NFTABLES) {
            val fw = RootShell.runScript("nft -f '$nftFilePath'")
            if (!fw.ok) {
                return StartResult(
                    false,
                    "nftables 加载失败（内核可能缺 nf_tables / nft_tproxy）:\n${fw.stderr}\n\n可改用 iptables 后端（后续版本支持）。",
                    pid
                )
            }
        } else {
            // TODO(MVP): iptables + ipset 回退路径，后续实现
        }

        return StartResult(
            true,
            "已启动 ${profile.core.binaryName} (PID $pid)\n配置: $runConfig\n防火墙与策略路由已配置",
            pid
        )
    }

    /**
     * 拼接启动命令行。
     * 运行参数（profile.extraArgs）语义：完整的核心启动参数。
     *  - 非空：原样使用，最终命令 = /data/adb/proxy/bin/<核心名> <运行参数>
     *    例：填 "-d /data/adb/proxy/run" → /data/adb/proxy/bin/mihomo -d /data/adb/proxy/run
     *  - 留空：按核心类型生成默认参数（配置文件取首页选中的，已复制到 run/）
     */
    private fun buildLaunchCommand(profile: Profile): String {
        val bin = "${ProxyPaths.BIN_DIR}/${profile.core.binaryName}"
        val conf = "${ProxyPaths.RUN_DIR}/${profile.configFile}"
        val args = profile.extraArgs.trim()
        val coreArgs = args.ifBlank {
            when (profile.core) {
                CoreType.SING_BOX -> "run -c '$conf'"
                CoreType.MIHOMO, CoreType.CLASH -> "-d '${ProxyPaths.RUN_DIR}' -f '$conf'"
                CoreType.HYSTERIA, CoreType.ANT -> "-c '$conf'"
            }
        }
        val pidFile = "${ProxyPaths.RUN_DIR}/${profile.core.binaryName}.pid"
        // setsid 让核心脱离 shell 会话常驻；输出丢弃（MVP 不采集核心日志）
        return "setsid '$bin' $coreArgs >/dev/null 2>&1 </dev/null & echo \$! > '$pidFile'"
    }

    /** 轮询 pidof 取 PID（对齐 box.service wait_bin_listen 思路） */
    private suspend fun waitForPid(binaryName: String, timeoutMs: Long = 5000): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = RootShell.runScript("pidof '$binaryName' | head -n1")
            val pid = r.stdout.trim().toIntOrNull()
            if (pid != null && pid > 0) return pid
            delay(300)
        }
        // 退路：读 pid 文件
        val pf = RootShell.runScript("cat '${ProxyPaths.RUN_DIR}/$binaryName.pid' 2>/dev/null").stdout.trim()
        return pf.toIntOrNull()
    }

    // ===================== 策略路由 =====================
    private suspend fun applyRouting(s: ProxySettings): ShellResult {
        val sb = StringBuilder()
        val mark = s.markId
        val table = s.tableId

        // tproxy 路由：标记流量经 local default dev lo 再被 TPROXY 截获
        if (s.tcpMethod == ProxyMethod.TPROXY || s.udpMethod == ProxyMethod.TPROXY) {
            sb.appendLine("ip rule add fwmark $mark table $table pref 100 2>/dev/null")
            sb.appendLine("ip route add local default dev lo table $table 2>/dev/null")
            if (s.ipv6Enabled) {
                sb.appendLine("ip -6 rule add fwmark $mark table $table pref 100 2>/dev/null")
                sb.appendLine("ip -6 route add local default dev lo table $table 2>/dev/null")
            }
        }

        // tun 路由（tcp/udp 任一为 tun）
        if (s.tcpMethod == ProxyMethod.TUN || s.udpMethod == ProxyMethod.TUN) {
            val tmark = s.tunMarkId
            val ttable = s.tunTableId
            val tun = s.tunDevice
            sb.appendLine("ip route replace default dev $tun table $ttable 2>/dev/null")
            sb.appendLine("ip rule add fwmark $tmark table $ttable pref 101 2>/dev/null")
            sb.appendLine("ip rule add iif $tun lookup main suppress_prefixlength 0 pref 102 2>/dev/null")
            if (s.ipv6Enabled) {
                sb.appendLine("ip -6 route replace default dev $tun table $ttable 2>/dev/null")
                sb.appendLine("ip -6 rule add fwmark $tmark table $ttable pref 101 2>/dev/null")
                sb.appendLine("ip -6 rule add iif $tun lookup main suppress_prefixlength 0 pref 102 2>/dev/null")
            }
            sb.appendLine("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null")
        }

        // fake-ip6 dummy 网卡与本地路由（对齐 box4 setup_dummy_dev：ip link add dummy + ip -6 route replace）
        if (s.ipv6Enabled && s.fakeIpRangeV6.isNotBlank()) {
            sb.appendLine("ip link add '${s.dummyDevice}' type dummy 2>/dev/null")
            sb.appendLine("ip link set '${s.dummyDevice}' up 2>/dev/null")
            sb.appendLine("ip -6 route replace '${s.fakeIpRangeV6}' dev '${s.dummyDevice}' 2>/dev/null")
        }
        return RootShell.runScript(sb.toString())
    }

    // ===================== 停止 =====================
    suspend fun stop(profile: Profile, settings: ProxySettings): String {
        val bin = profile.core.binaryName
        val sb = StringBuilder()
        // 杀进程
        sb.appendLine("pkill -x '$bin' 2>/dev/null; sleep 1; pkill -9 -x '$bin' 2>/dev/null")
        // 清 tproxy 路由
        sb.appendLine("ip rule del fwmark ${settings.markId} table ${settings.tableId} 2>/dev/null")
        sb.appendLine("ip route flush table ${settings.tableId} 2>/dev/null")
        sb.appendLine("ip -6 rule del fwmark ${settings.markId} table ${settings.tableId} 2>/dev/null")
        sb.appendLine("ip -6 route flush table ${settings.tableId} 2>/dev/null")
        // 清 tun 路由
        if (settings.tcpMethod == ProxyMethod.TUN || settings.udpMethod == ProxyMethod.TUN) {
            sb.appendLine("while ip rule del pref 101 2>/dev/null; do :; done")
            sb.appendLine("while ip rule del pref 102 2>/dev/null; do :; done")
            sb.appendLine("ip route flush table ${settings.tunTableId} 2>/dev/null")
            sb.appendLine("ip -6 route flush table ${settings.tunTableId} 2>/dev/null")
        }
        // 清 fake-ip6 dummy 路由与网卡
        if (settings.ipv6Enabled && settings.fakeIpRangeV6.isNotBlank()) {
            sb.appendLine("ip -6 route del '${settings.fakeIpRangeV6}' dev '${settings.dummyDevice}' 2>/dev/null")
            sb.appendLine("ip link del '${settings.dummyDevice}' 2>/dev/null")
        }
        // 清 nft
        if (settings.firewall == FirewallType.NFTABLES) {
            sb.appendLine("nft delete table ip box 2>/dev/null")
            sb.appendLine("nft delete table ip6 box 2>/dev/null")
        }
        val r = RootShell.runScript(sb.toString())
        return if (r.ok) "已停止并清理防火墙与路由" else "停止异常:\n${r.stderr}"
    }

    // ===================== 状态 =====================
    suspend fun status(binaryName: String): CoreStatus {
        val pidR = RootShell.runScript("pidof '$binaryName' | head -n1")
        val pid = pidR.stdout.trim().toIntOrNull()
        if (pid == null) return CoreStatus(false)
        val memR = RootShell.runScript(
            "awk '/VmRSS/{print \$2}' /proc/$pid/status 2>/dev/null"
        )
        val mem = memR.stdout.trim().toLongOrNull()
        return CoreStatus(true, pid, mem)
    }

    // ===================== 一键还原 =====================
    suspend fun reset(): String {
        val r = RootShell.runScript(
            """
            for t in 2024 2025; do
                ip route flush table ${'$'}t 2>/dev/null
                ip -6 route flush table ${'$'}t 2>/dev/null
            done
            while ip rule del pref 100 2>/dev/null; do :; done
            while ip rule del pref 101 2>/dev/null; do :; done
            while ip rule del pref 102 2>/dev/null; do :; done
            nft delete table ip box 2>/dev/null
            nft delete table ip6 box 2>/dev/null
            ip link del box-dummy 2>/dev/null
            pkill -x sing-box 2>/dev/null; pkill -x mihomo 2>/dev/null
            pkill -x clash 2>/dev/null; pkill -x hysteria 2>/dev/null; pkill -x ant 2>/dev/null
            echo RESET_DONE
            """.trimIndent()
        )
        return if ("RESET_DONE" in r.stdout) "已还原：清空路由/规则/进程" else "还原异常:\n${r.stderr}"
    }

    // ===================== nftables 规则生成 =====================
    /**
     * 生成 nft 脚本，写入 app 私有文件后由 `nft -f` 加载。
     * 与 box4 box.nft 对应的独立 `table ip box` / `table ip6 box`。
     * 按 ProxySettings 中的 tcp/udp 代理方式生成对应规则：
     *  - TPROXY -> mangle tproxy 规则
     *  - REDIR  -> nat redirect 规则（仅 TCP）
     *  - TUN    -> 不出 netfilter 规则（由策略路由进入 tun）
     *  - DNS 劫持：v4 由 dnsHijackEnabled、v6 由 dnsHijackEnabled && dnsHijackV6Enabled 分别控制
     *  - Fake-IP ping 劫持：nat PREROUTING/OUTPUT 对 fake-ip 段 ICMP echo 做 REDIRECT（本机应答）
     *  - intranet/intranet6 排除集合来自设置页（可编辑），构建时自动剔除 fake-ip 段
     */
    private fun buildNftScript(s: ProxySettings): String {
        val mark = parseMark(s.markId)
        val tproxy = s.tproxyPort
        val redir = s.redirPort
        val dns = s.dnsPort
        val tun = s.tunDevice

        val tproxyProtos = buildList {
            if (s.tcpMethod == ProxyMethod.TPROXY) add("tcp")
            if (s.udpMethod == ProxyMethod.TPROXY) add("udp")
        }
        // REDIRECT 仅支持 TCP
        val redirEnabled = s.tcpMethod == ProxyMethod.REDIR
        val pingHijackV4 = s.fakeIpPingHijack && s.fakeIpRangeV4.isNotBlank()
        val pingHijackV6 = s.fakeIpPingHijack && s.ipv6Enabled && s.fakeIpRangeV6.isNotBlank()

        // 内网排除集合：剔除 fake-ip 段（fake-ip 流量必须进核心），对齐 box4 box.tproxy
        val intranet4 = s.intranet.map { it.trim() }
            .filter { it.isNotEmpty() && it != s.fakeIpRangeV4.trim() }
        val intranet6 = s.intranet6.map { it.trim() }
            .filter { it.isNotEmpty() && it != s.fakeIpRangeV6.trim() }

        val sb = StringBuilder()

        // ================= table ip box =================
        sb.appendLine("table ip box {")
        sb.appendLine("    set intranet {")
        sb.appendLine("        type ipv4_addr")
        sb.appendLine("        flags interval")
        if (intranet4.isNotEmpty()) {
            sb.appendLine("        elements = { ${intranet4.joinToString(", ")} }")
        }
        sb.appendLine("    }")
        sb.appendLine("    chain mangle_prerouting {")
        sb.appendLine("        type filter hook prerouting priority mangle; policy accept;")
        sb.appendLine("        ip daddr @intranet return")
        sb.appendLine("        ip protocol != tcp ip protocol != udp return")
        tproxyProtos.forEach { proto ->
            sb.appendLine("        meta l4proto $proto tproxy ip to :$tproxy meta mark set $mark")
        }
        sb.appendLine("    }")
        sb.appendLine("    chain nat_prerouting {")
        sb.appendLine("        type nat hook prerouting priority dstnat; policy accept;")
        if (s.dnsHijackEnabled) {
            sb.appendLine("        udp dport 53 redirect to :$dns")
            sb.appendLine("        tcp dport 53 redirect to :$dns")
        }
        if (redirEnabled) {
            sb.appendLine("        tcp dport 1-65535 redirect to :$redir")
        }
        if (pingHijackV4) {
            sb.appendLine("        ip daddr ${s.fakeIpRangeV4} icmp type echo-request redirect")
        }
        sb.appendLine("    }")
        if (pingHijackV4) {
            sb.appendLine("    chain nat_output {")
            sb.appendLine("        type nat hook output priority -100; policy accept;")
            sb.appendLine("        ip daddr ${s.fakeIpRangeV4} icmp type echo-request redirect")
            sb.appendLine("    }")
        }
        sb.appendLine("    chain filter_input {")
        sb.appendLine("        type filter hook input priority filter; policy accept;")
        sb.appendLine("        iif \"$tun\" accept")
        sb.appendLine("    }")
        sb.appendLine("    chain filter_forward {")
        sb.appendLine("        type filter hook forward priority filter; policy accept;")
        sb.appendLine("        iif \"$tun\" accept")
        sb.appendLine("        oif \"$tun\" accept")
        sb.appendLine("    }")
        sb.appendLine("}")

        // ================= table ip6 box =================
        if (s.ipv6Enabled) {
            sb.appendLine()
            sb.appendLine("table ip6 box {")
            sb.appendLine("    set intranet6 {")
            sb.appendLine("        type ipv6_addr")
            sb.appendLine("        flags interval")
            if (intranet6.isNotEmpty()) {
                sb.appendLine("        elements = { ${intranet6.joinToString(", ")} }")
            }
            sb.appendLine("    }")
            sb.appendLine("    chain mangle_prerouting {")
            sb.appendLine("        type filter hook prerouting priority mangle; policy accept;")
            sb.appendLine("        ip6 daddr @intranet6 return")
            sb.appendLine("        meta l4proto { tcp, udp } return")
            tproxyProtos.forEach { proto ->
                sb.appendLine("        meta l4proto $proto tproxy ip6 to :$tproxy meta mark set $mark")
            }
            sb.appendLine("    }")
            sb.appendLine("    chain nat_prerouting {")
            sb.appendLine("        type nat hook prerouting priority dstnat; policy accept;")
            if (s.dnsHijackEnabled && s.dnsHijackV6Enabled) {
                sb.appendLine("        udp dport 53 redirect to :$dns")
                sb.appendLine("        tcp dport 53 redirect to :$dns")
            }
            if (pingHijackV6) {
                sb.appendLine("        ip6 daddr ${s.fakeIpRangeV6} icmpv6 type echo-request redirect")
            }
            sb.appendLine("    }")
            if (pingHijackV6) {
                sb.appendLine("    chain nat_output {")
                sb.appendLine("        type nat hook output priority -100; policy accept;")
                sb.appendLine("        ip6 daddr ${s.fakeIpRangeV6} icmpv6 type echo-request redirect")
                sb.appendLine("    }")
            }
            sb.appendLine("}")
        }
        return sb.toString()
    }

    /** markId 形如 "16777216/16777216"，取前半作为 nft mark 值 */
    private fun parseMark(markId: String): String = markId.split("/").firstOrNull() ?: markId
}

data class StartResult(
    val ok: Boolean,
    val message: String,
    val pid: Int? = null
)

data class CoreStatus(
    val running: Boolean,
    val pid: Int? = null,
    val memKb: Long? = null
)
