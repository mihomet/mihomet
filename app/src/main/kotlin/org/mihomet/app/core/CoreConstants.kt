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
