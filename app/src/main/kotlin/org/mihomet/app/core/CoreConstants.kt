package org.mihomet.app.core

import java.security.SecureRandom

/**
 * Single source of truth for Mihomo core and system integration constants.
 */
object CoreConstants {

    // External controller / API
    const val DEFAULT_CONTROLLER_HOST = "127.0.0.1"
    const val DEFAULT_MIXED_PORT = 7890
    const val DEFAULT_TPROXY_PORT = 7893
    const val DEFAULT_REDIR_PORT = 7892
    const val DEFAULT_DNS_PORT = 1053
    const val DEFAULT_CONTROLLER_PORT = 9090

    // Core SO_MARK shared by routing-mark config and firewall fwmark rules
    const val CORE_ROUTING_MARK = 666

    // Policy routing (TProxy)
    const val TPROXY_MARK_IPV4 = 20
    const val TPROXY_MARK_IPV6 = 25
    const val POLICY_ROUTING_TABLE = 2025
    const val POLICY_RULE_PREF_MAIN = 100
    const val POLICY_RULE_PREF_LOCAL = 2025

    // TUN interface (non-root VpnService mode)
    const val TUN_MTU = 9000
    const val TUN_IPV4_ADDR = "198.18.0.1"
    const val TUN_IPV4_PREFIX = 30
    const val TUN_IPV4_DNS = "198.18.0.2"
    const val TUN_IPV6_ADDR = "fdfe:dcba:9876::1"
    const val TUN_IPV6_PREFIX = 126
    const val TUN_IPV6_DNS = "fdfe:dcba:9876::2"
    const val TUN_FILE_DESCRIPTOR = 3
    const val TUN_FD_SHELL_REDIRECT = "3<&0"

    /**
     * Bypass-LAN routing (CMFA `bypassPrivateNetwork` pattern, ported verbatim from
     * MetaCubeX/ClashMetaForAndroid `R.array.bypass_private_route[_6]`).
     *
     * Android VpnService routes are additive-only ("traffic to these prefixes goes
     * INTO the tunnel"), so bypassing private networks means adding the COMPLEMENT:
     * every public unicast prefix — everything EXCEPT 10/8, 172.16/12, 192.168/16,
     * link-local and multicast. Adding the private ranges themselves would instead
     * capture ONLY LAN traffic and leave all internet traffic unproxied.
     *
     * The virtual DNS servers (198.18.0.2 — inside the fake-ip range, fdfe:…::2 —
     * ULA) are NOT covered by the complement and get explicit /32 (resp. /128)
     * routes in MihometVpnService, exactly like CMFA's "Route of virtual DNS".
     */
    val TUN_BYPASS_LAN_ROUTES_V4: List<Pair<String, Int>> = listOf(
        "1.0.0.0" to 8, "2.0.0.0" to 7, "4.0.0.0" to 6, "8.0.0.0" to 7,
        "11.0.0.0" to 8, "12.0.0.0" to 6, "16.0.0.0" to 4, "32.0.0.0" to 3,
        "64.0.0.0" to 3, "96.0.0.0" to 4, "112.0.0.0" to 5, "120.0.0.0" to 6,
        "124.0.0.0" to 7, "126.0.0.0" to 8, "128.0.0.0" to 3, "160.0.0.0" to 5,
        "168.0.0.0" to 8, "169.0.0.0" to 9, "169.128.0.0" to 10, "169.192.0.0" to 11,
        "169.224.0.0" to 12, "169.240.0.0" to 13, "169.248.0.0" to 14, "169.252.0.0" to 15,
        "169.255.0.0" to 16, "170.0.0.0" to 7, "172.0.0.0" to 12, "172.32.0.0" to 11,
        "172.64.0.0" to 10, "172.128.0.0" to 9, "173.0.0.0" to 8, "174.0.0.0" to 7,
        "176.0.0.0" to 4, "192.0.0.0" to 9, "192.128.0.0" to 11, "192.160.0.0" to 13,
        "192.169.0.0" to 16, "192.170.0.0" to 15, "192.172.0.0" to 14, "192.176.0.0" to 12,
        "192.192.0.0" to 10, "193.0.0.0" to 8, "194.0.0.0" to 7, "196.0.0.0" to 6,
        "200.0.0.0" to 5, "208.0.0.0" to 4, "240.0.0.0" to 5, "248.0.0.0" to 6,
        "252.0.0.0" to 7, "254.0.0.0" to 8, "255.0.0.0" to 9, "255.128.0.0" to 10,
        "255.192.0.0" to 11, "255.224.0.0" to 12, "255.240.0.0" to 13, "255.248.0.0" to 14,
        "255.252.0.0" to 15, "255.254.0.0" to 16, "255.255.0.0" to 17, "255.255.128.0" to 18,
        "255.255.192.0" to 19, "255.255.224.0" to 20, "255.255.240.0" to 21, "255.255.248.0" to 22,
        "255.255.252.0" to 23, "255.255.254.0" to 24, "255.255.255.0" to 25, "255.255.255.128" to 26,
        "255.255.255.192" to 27, "255.255.255.224" to 28, "255.255.255.240" to 29, "255.255.255.248" to 30,
        "255.255.255.252" to 31, "255.255.255.254" to 32
    )

    /**
     * IPv6 complement of the bypass list: everything EXCEPT fe80::/10 (link-local),
     * fc00::/7 (ULA — "private" IPv6) and ff00::/8 (multicast). Global unicast v6
     * stays captured, so IPv6 cannot leak around the tunnel.
     */
    val TUN_BYPASS_LAN_ROUTES_V6: List<Pair<String, Int>> = listOf(
        "::" to 1, "8000::" to 2, "c000::" to 3, "e000::" to 4,
        "f000::" to 5, "f800::" to 6, "fe00::" to 9, "fec0::" to 10
    )

    // Temporary core probe (offline node delay testing)
    const val PROBE_PORT = 9095
    const val PROBE_SECRET = "mihomet_probe_token"

    // Root-mode filesystem layout
    const val ROOT_BASE_DIR = "/data/adb/mihomet"
    const val ROOT_BIN_DIR = "$ROOT_BASE_DIR/bin"
    const val ROOT_BIN_PATH = "$ROOT_BIN_DIR/mihomo"
    const val ROOT_RUN_DIR = "$ROOT_BASE_DIR/run"
    const val ROOT_PID_FILE = "$ROOT_BASE_DIR/mihomo.pid"
    const val ROOT_LOG_FILE = "$ROOT_RUN_DIR/mihomo.log"
    const val ROOT_CONFIG_PATH = "$ROOT_RUN_DIR/config.yaml"

    // Version marker written by downloadAndUpdateCore (the release tag of the
    // core it installed). bootstrapRootBinary uses it to avoid rolling back a
    // user-updated core to the bundled binary on the next start.
    const val ROOT_CORE_VERSION_FILE = "$ROOT_RUN_DIR/installed_core_version"

    // OEM (OnePlus / Realme / ColorOS) custom DNS reject chain in the filter
    // table. startDaemon snapshots its rules to the save files below BEFORE
    // flushing the chain for the daemon lifetime; stopDaemon (and the crash
    // recovery path) replays the saved rules and removes the files, so the
    // OEM DNS logic survives a mihomet root session.
    const val OPLUS_DNS_CHAIN = "fw_OUTPUT_oplus_dns"
    const val ROOT_OPLUS_RULES_V4_FILE = "$ROOT_RUN_DIR/.oplus_v4.rules"
    const val ROOT_OPLUS_RULES_V6_FILE = "$ROOT_RUN_DIR/.oplus_v6.rules"

    // H2: staging files for the atomic OEM chain snapshot. The iptables-save
    // dump lands here first and is renamed over the matching save file above
    // only after content verification, so a partial/failed dump can never
    // truncate a good snapshot (same directory => rename(2) => atomic).
    const val ROOT_OPLUS_RULES_V4_TMP_FILE = "$ROOT_RUN_DIR/.oplus_v4.rules.tmp"
    const val ROOT_OPLUS_RULES_V6_TMP_FILE = "$ROOT_RUN_DIR/.oplus_v6.rules.tmp"

    // F10: snapshot of the sysctls the firewall engines mutate (net/ipv4/ip_forward,
    // conf/all/rp_filter, conf/default/rp_filter — one value per line, in that order).
    // Written before the FIRST sysctl change of a root session; the engines' teardown
    // restores the values and removes the file. A leftover file from a crashed session
    // is therefore never overwritten with already-modified values (the backup is gated
    // on the file's absence) and the restore is a no-op when nothing was changed.
    const val ROOT_SYSCTL_BACKUP_FILE = "$ROOT_RUN_DIR/.sysctl.bak"

    private val secureRandom = SecureRandom()

    /**
     * Generates a random hex secret. With the default [charCount] of 32 this
     * consumes 16 random bytes and renders them as 32 hexadecimal characters.
     */
    fun generateRandomHexSecret(charCount: Int = 32): String {
        val byteCount = charCount / 2
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
