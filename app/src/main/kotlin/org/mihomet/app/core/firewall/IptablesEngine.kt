package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.data.preferences.PerAppMode

class IptablesEngine : FirewallEngine {
    override val name: String = "iptables"

    override fun setup(
        tproxyPort: Int,
        dnsPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit> = runCatching {
        teardown(ipv6Enabled)

        val cmds = mutableListOf<String>()

        // 1. IP rule and routing table 100
        cmds.add("ip rule add fwmark 0x1/0x1 table 100")
        cmds.add("ip route add local default dev lo table 100")
        if (ipv6Enabled) {
            cmds.add("ip -6 rule add fwmark 0x1/0x1 table 100")
            cmds.add("ip -6 route add local default dev lo table 100")
        }

        // 2. DNS Redirection in NAT OUTPUT
        cmds.add("iptables -t nat -N MIHOMET_DNS 2>/dev/null || iptables -t nat -F MIHOMET_DNS")
        cmds.add("iptables -t nat -A MIHOMET_DNS -m mark --mark 666 -j RETURN")
        cmds.add("iptables -t nat -A MIHOMET_DNS -p udp --dport 53 -j REDIRECT --to-ports $dnsPort")
        cmds.add("iptables -t nat -A MIHOMET_DNS -p tcp --dport 53 -j REDIRECT --to-ports $dnsPort")
        cmds.add("iptables -t nat -I OUTPUT 1 -j MIHOMET_DNS")

        // 3. Mangle OUTPUT Chain (Local packets marked with 0x1 -> lo -> PREROUTING)
        cmds.add("iptables -t mangle -N MIHOMET_OUTPUT 2>/dev/null || iptables -t mangle -F MIHOMET_OUTPUT")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -m mark --mark 666 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -p tcp --dport $dnsPort -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -p udp --dport $dnsPort -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -d 127.0.0.0/8 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -d 10.0.0.0/8 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -d 172.16.0.0/12 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -d 192.168.0.0/16 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -d 224.0.0.0/4 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -d 255.255.255.255/32 -j RETURN")

        // Per-App filter in OUTPUT
        when (perAppMode) {
            PerAppMode.ALL -> { /* All apps handled */ }
            PerAppMode.WHITELIST -> {
                if (appUids.isNotEmpty()) {
                    cmds.add("iptables -t mangle -N MIHOMET_UIDS 2>/dev/null || iptables -t mangle -F MIHOMET_UIDS")
                    for (uid in appUids) {
                        cmds.add("iptables -t mangle -A MIHOMET_UIDS -m owner --uid-owner $uid -j RETURN")
                    }
                    // Non-matching apps skip proxying
                    cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -j MIHOMET_UIDS")
                    cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -j RETURN")
                } else {
                    cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -j RETURN")
                }
            }
            PerAppMode.BLACKLIST -> {
                for (uid in appUids) {
                    cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -m owner --uid-owner $uid -j RETURN")
                }
            }
        }

        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -p tcp -j MARK --set-mark 0x1")
        cmds.add("iptables -t mangle -A MIHOMET_OUTPUT -p udp -j MARK --set-mark 0x1")
        cmds.add("iptables -t mangle -I OUTPUT 1 -j MIHOMET_OUTPUT")

        // 4. Mangle PREROUTING Chain (TProxy)
        cmds.add("iptables -t mangle -N MIHOMET_PREROUTING 2>/dev/null || iptables -t mangle -F MIHOMET_PREROUTING")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -d 127.0.0.0/8 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -d 10.0.0.0/8 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -d 172.16.0.0/12 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -d 192.168.0.0/16 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -d 224.0.0.0/4 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -d 255.255.255.255/32 -j RETURN")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -p tcp -j TPROXY --on-port $tproxyPort --tproxy-mark 0x1/0x1")
        cmds.add("iptables -t mangle -A MIHOMET_PREROUTING -p udp -j TPROXY --on-port $tproxyPort --tproxy-mark 0x1/0x1")
        cmds.add("iptables -t mangle -I PREROUTING 1 -j MIHOMET_PREROUTING")

        // 5. IPv6 Block when disabled
        if (!ipv6Enabled) {
            cmds.add("ip6tables -I OUTPUT 1 -m mark ! --mark 666 -j DROP 2>/dev/null || true")
        }

        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        if (!result.isSuccess) {
            throw RuntimeException("Failed to apply iptables: ${result.err.joinToString("\n")}")
        }
    }

    override fun teardown(ipv6Enabled: Boolean): Result<Unit> = runCatching {
        Shell.cmd(
            "iptables -t nat -D OUTPUT -j MIHOMET_DNS 2>/dev/null || true",
            "iptables -t nat -F MIHOMET_DNS 2>/dev/null || true",
            "iptables -t nat -X MIHOMET_DNS 2>/dev/null || true",
            "iptables -t mangle -D OUTPUT -j MIHOMET_OUTPUT 2>/dev/null || true",
            "iptables -t mangle -F MIHOMET_OUTPUT 2>/dev/null || true",
            "iptables -t mangle -X MIHOMET_OUTPUT 2>/dev/null || true",
            "iptables -t mangle -F MIHOMET_UIDS 2>/dev/null || true",
            "iptables -t mangle -X MIHOMET_UIDS 2>/dev/null || true",
            "iptables -t mangle -D PREROUTING -j MIHOMET_PREROUTING 2>/dev/null || true",
            "iptables -t mangle -F MIHOMET_PREROUTING 2>/dev/null || true",
            "iptables -t mangle -X MIHOMET_PREROUTING 2>/dev/null || true",
            "ip6tables -D OUTPUT -m mark ! --mark 666 -j DROP 2>/dev/null || true",
            "ip rule del fwmark 0x1/0x1 table 100 2>/dev/null || true",
            "ip route del local default dev lo table 100 2>/dev/null || true",
            "ip -6 rule del fwmark 0x1/0x1 table 100 2>/dev/null || true",
            "ip -6 route del local default dev lo table 100 2>/dev/null || true"
        ).exec()
    }
}
