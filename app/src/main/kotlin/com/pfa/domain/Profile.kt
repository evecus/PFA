package com.pfa.domain

import kotlinx.serialization.Serializable

/**
 * 支持的代理核心。
 * 与 box4 box.config 的 bin_name_list 对齐。
 */
@Serializable
enum class CoreType {
    SING_BOX,
    MIHOMO,
    CLASH,
    HYSTERIA,
    ANT;

    /** 命令行可执行名（用于 pidof / bin 目录匹配） */
    val binaryName: String
        get() = when (this) {
            SING_BOX -> "sing-box"
            MIHOMO -> "mihomo"
            CLASH -> "clash"
            HYSTERIA -> "hysteria"
            ANT -> "ant"
        }

    companion object {
        fun fromBinaryName(name: String): CoreType? =
            entries.firstOrNull { it.binaryName == name }

        /**
         * 按上传的二进制文件名推断核心类型：
         * 精确匹配 binaryName，否则按名称包含关键字识别（支持 mihomo-alpha 等改名核心）。
         */
        fun detect(name: String): CoreType =
            fromBinaryName(name) ?: when {
                name.contains("sing-box") || name.contains("singbox") -> SING_BOX
                name.contains("mihomo") -> MIHOMO
                name.contains("clash") -> CLASH
                name.contains("hysteria") -> HYSTERIA
                name.contains("ant") -> ANT
                else -> SING_BOX
            }
    }
}

/** TCP / UDP 流量导入方式，对齐 box.config 的 tcp_proxy_method / udp_proxy_method */
@Serializable
enum class ProxyMethod {
    TPROXY,   // mangle TPROXY（需内核 TPROXY）
    REDIR,    // nat REDIRECT（仅 TCP）
    TUN,      // 打 fwmark 经策略路由进 tun 网卡（核心需启用 tun 且关 auto-route）
    NULL;     // 不代理

    val value: String
        get() = name.lowercase()
}

/** 防火墙后端，对齐 box.config 的 firewall_type */
@Serializable
enum class FirewallType {
    NFTABLES,  // 独立 table ip/ip6 box，需 nf_tables（TPROXY 另需 nft_tproxy）
    IPTABLES;  // iptables/ip6tables + ipset，nft 不可用时回退（TODO）

    val value: String
        get() = when (this) {
            NFTABLES -> "nftables"
            IPTABLES -> "iptables"
        }
}

/** 代理模式，对齐 box.config 的 proxy_mode */
@Serializable
enum class ProxyMode {
    BLACKLIST,  // 代理除列表外所有包
    WHITELIST,  // 仅代理列表中的包
    CORE;       // 由核心自行决定（仍打标记）

    val value: String
        get() = name.lowercase()
}

/**
 * APK 在设备上的工作目录体系。
 *  bin/      代理核心二进制
 *  profiles/ 配置文件（上传后存放于此）
 *  run/      运行目录（启动前把选中的配置复制进来，pid 文件也在此）
 */
object ProxyPaths {
    const val ROOT_DIR = "/data/adb/proxy"
    const val BIN_DIR = "$ROOT_DIR/bin"
    const val PROFILES_DIR = "$ROOT_DIR/profiles"
    const val RUN_DIR = "$ROOT_DIR/run"
}

/**
 * 首页选择的启动档案：核心 + 配置文件 + 运行参数。
 * 防火墙/路由等全局选项在 ProxySettings（设置页）。
 */
@Serializable
data class Profile(
    val id: String = "",
    val name: String = "默认",
    val core: CoreType = CoreType.SING_BOX,

    /** 实际使用的核心二进制文件名（bin 目录下），为空时回退 core.binaryName */
    val binaryName: String = "",

    /** profiles 目录下的配置文件名，例如 config.json */
    val configFile: String = "",

    /** 完整核心启动参数；留空时按核心类型生成默认参数 */
    val extraArgs: String = ""
) {
    /** 实际执行的二进制文件名 */
    val bin: String
        get() = binaryName.ifBlank { core.binaryName }
}

/**
 * 全局代理设置，对应 box.config 中的防火墙/路由/DNS 等选项（设置页编辑）。
 */
@Serializable
data class ProxySettings(
    // ---------- 防火墙 / 代理方式 ----------
    val firewall: FirewallType = FirewallType.NFTABLES,
    val tcpMethod: ProxyMethod = ProxyMethod.TPROXY,
    val udpMethod: ProxyMethod = ProxyMethod.TPROXY,

    // ---------- 模式 ----------
    val proxyMode: ProxyMode = ProxyMode.BLACKLIST,
    /** 包名列表（黑白名单用），每行一个包名 */
    val packageList: List<String> = emptyList(),

    // ---------- 端口 ----------
    val redirPort: Int = 7891,
    val tproxyPort: Int = 7892,
    val dnsPort: Int = 1053,

    // ---------- 网络 ----------
    val ipv6Enabled: Boolean = true,
    val dnsHijackEnabled: Boolean = true,
    /** IPv6 DNS 劫持独立开关（对齐 box.config dns_hijack_v6） */
    val dnsHijackV6Enabled: Boolean = true,

    // ---------- Fake-IP ----------
    val fakeIpRangeV4: String = "198.18.0.0/15",
    val fakeIpRangeV6: String = "fc00::/18",
    /** fake-ip6 专用 dummy 网卡（为 fake_ip_range_v6 提供本地路由，名称 <=15 字符） */
    val dummyDevice: String = "box-dummy",
    /** 劫持发往 fake-ip 段的 ICMP echo，由本机应答（参考 nexa） */
    val fakeIpPingHijack: Boolean = true,

    // ---------- 内网网段排除（每行一个 CIDR，可编辑；fake-ip 段运行时自动剔除） ----------
    val intranet: List<String> = DEFAULT_INTRANET4,
    val intranet6: List<String> = DEFAULT_INTRANET6,

    // ---------- 内核标记 / 路由表 ----------
    val markId: String = "16777216/16777216",
    val tableId: String = "2024",
    val tunMarkId: String = "33554432/33554432",
    val tunTableId: String = "2025",
    val tunDevice: String = "tun0",

    /** 启动前对核心配置做 check（仅 sing-box） */
    val startCheck: Boolean = true
) {
    companion object {
        /** 默认 IPv4 内网排除段（对齐 box.config intranet） */
        val DEFAULT_INTRANET4 = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
            "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
            "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4",
            "240.0.0.0/4", "255.255.255.255/32"
        )

        /** 默认 IPv6 内网排除段（对齐 box.config intranet6） */
        val DEFAULT_INTRANET6 = listOf(
            "::/128", "::1/128", "::ffff:0:0/96", "64:ff9b::/96", "100::/64",
            "2001::/32", "2001:10::/28", "2001:20::/28", "2001:db8::/32",
            "2002::/16", "fe80::/10", "ff00::/8"
        )
    }
}
