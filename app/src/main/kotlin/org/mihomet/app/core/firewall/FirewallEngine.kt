package org.mihomet.app.core.firewall

import org.mihomet.app.data.preferences.PerAppMode

interface FirewallEngine {
    val name: String

    fun setup(
        tproxyPort: Int,
        dnsPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit>

    fun teardown(ipv6Enabled: Boolean): Result<Unit>
}
