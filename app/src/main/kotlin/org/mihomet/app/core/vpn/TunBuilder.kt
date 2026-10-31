package org.mihomet.app.core.vpn

import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.preferences.PerAppMode
import org.mihomet.app.data.preferences.TunOptions

/**
 * Configures the [VpnService.Builder] from [org.mihomet.app.data.preferences.AppSettings]:
 * TUN addresses / routes (with the CMFA bypass-LAN complement pattern), MTU from
 * settings, metered flag, opt-in allowBypass / HTTP proxy, and per-app split
 * tunneling (VpnService does not allow mixing allowed and disallowed apps).
 *
 * Extracted verbatim from [MihometVpnService] — pure move, no behavior change.
 * The caller owns builder creation and `establish()`.
 */
class TunBuilder(
    private val settings: org.mihomet.app.data.preferences.AppSettings,
    private val packageName: String,
    private val packageManager: PackageManager
) {

    fun configure(builder: VpnService.Builder, configureIntent: PendingIntent): VpnService.Builder {
        // Build TUN interface with non-blocking mode; MTU comes from settings
        // (clamped to 1280..65535, default 9000 — CMFA standard for gVisor) and
        // must match ConfigGenerator's tun.mtu.
        builder
            .setSession("Mihomet")
            .setMtu(TunOptions.sanitizeMtu(settings.tunMtu))
            .setBlocking(false)
            .setConfigureIntent(configureIntent)
            .addAddress(CoreConstants.TUN_IPV4_ADDR, CoreConstants.TUN_IPV4_PREFIX)
            .addDnsServer(CoreConstants.TUN_IPV4_DNS)

        if (settings.bypassLan) {
            // CMFA bypassPrivateNetwork pattern: Android VPN routes are additive,
            // so bypassing private networks means routing the COMPLEMENT (every
            // public unicast prefix) into the tunnel and leaving 10/8, 172.16/12,
            // 192.168/16, link-local and multicast to the underlying network.
            CoreConstants.TUN_BYPASS_LAN_ROUTES_V4.forEach { (net, prefix) ->
                builder.addRoute(net, prefix)
            }
            // Virtual DNS (inside the fake-ip range) is not covered by the
            // complement — explicit host route, exactly like CMFA.
            builder.addRoute(CoreConstants.TUN_IPV4_DNS, 32)
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Always trap IPv6 into TUN interface to prevent carrier leakage / Happy Eyeballs hangs in Telegram
        builder.addAddress(CoreConstants.TUN_IPV6_ADDR, CoreConstants.TUN_IPV6_PREFIX)
        builder.addDnsServer(CoreConstants.TUN_IPV6_DNS)
        if (settings.bypassLan) {
            // Same complement for IPv6: global unicast stays captured (no carrier
            // leak), while fe80::/10 (link-local) and fc00::/7 (ULA — the "private"
            // IPv6 ranges) bypass to the underlying network like their IPv4 peers.
            CoreConstants.TUN_BYPASS_LAN_ROUTES_V6.forEach { (net, prefix) ->
                builder.addRoute(net, prefix)
            }
            // The virtual IPv6 DNS is a ULA address (fdfe:…::2) — outside the
            // complement — so it needs its own host route (CMFA TUN_DNS6/128).
            builder.addRoute(CoreConstants.TUN_IPV6_DNS, 128)
        } else {
            builder.addRoute("::", 0)
        }

        // Do not mark VPN traffic as metered when underlying network is unmetered Wi-Fi (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // CMFA/FlClash allowBypass pattern (opt-in): apps that explicitly request
        // the bypass option may route around the VPN. Off by default — with the
        // default, Android blocks applications' bypass attempts, so nothing can
        // escape the tunnel without the user's consent.
        if (settings.vpnAllowBypass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.allowBypass()
        }

        // CMFA/FlClash setHttpProxy pattern (opt-in): publish a local HTTP proxy
        // to apps that honor the system proxy inside the VPN profile. Requires
        // ConfigGenerator to re-enable the mixed-port listener for this session
        // (see the documented security compromise there): the listener binds to
        // 127.0.0.1 only (bind-address), is UNAUTHENTICATED, and any on-device
        // app can reach it directly — the user consciously opted in via
        // Settings → Advanced, so this does not violate "no silent exposure".
        if (settings.vpnHttpProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy("127.0.0.1", settings.mixedPort)
                )
            }.onFailure {
                android.util.Log.w("MihometVpn", "setHttpProxy failed: ${it.message}")
            }
        }

        // Per-app split tunneling (VpnService does not allow mixing allowed and disallowed apps)
        val pm = packageManager
        when (settings.perAppMode) {
            PerAppMode.WHITELIST -> {
                // "Only selected" mode: route ONLY the picked apps through the VPN.
                //
                // Android quirk: a VpnService established without a single
                // addAllowedApplication() call routes EVERY app ("absent allow
                // list" means "allow all"), which contradicts the "only selected"
                // expectation. An empty selection therefore deliberately behaves
                // like ALL mode — everything except our own app is proxied; the
                // Routing screen shows a hint for this state
                // (R.string.perapp_empty_whitelist).
                if (settings.selectedAppUids.isEmpty()) {
                    // Same config as ALL mode: exclude only our own app so the
                    // core's traffic does not loop back into the TUN it feeds.
                    builder.addDisallowedApplication(packageName)
                } else {
                    // Count the packages ACTUALLY added (audit N02):
                    // getPackagesForUid can return nothing for a stale uid and
                    // addAllowedApplication can fail — with zero entries added
                    // the "absent allow list = allow all" quirk would silently
                    // route EVERY app, including ours (→ TUN loop).
                    var addedCount = 0
                    settings.selectedAppUids.forEach { uid ->
                        pm.getPackagesForUid(uid)?.forEach { pkg ->
                            // Never allow our own package, even if the user picked
                            // Mihomet itself: the core runs inside our uid, so its
                            // outbound traffic would loop into the TUN interface.
                            if (pkg != packageName) {
                                runCatching { builder.addAllowedApplication(pkg) }
                                    .onSuccess { addedCount++ }
                            }
                        }
                    }
                    if (addedCount == 0) {
                        // Degenerate selection (only Mihomet picked, stale uids,
                        // or every add failed): fall back to the ALL-mode shape —
                        // the explicit disallow keeps the "proxy everything"
                        // semantics AND excludes our own app, so the quirk can
                        // neither surprise the user nor loop our traffic. The
                        // Routing screen warns about the only-self state
                        // (R.string.perapp_only_self).
                        android.util.Log.w(
                            "MihometVpn",
                            "Per-app whitelist: 0 of ${settings.selectedAppUids.size} selected uids actually allowed — excluding only our own package"
                        )
                        builder.addDisallowedApplication(packageName)
                    }
                }
            }
            PerAppMode.BLACKLIST -> {
                // Exclude our own app plus selected blacklisted apps
                builder.addDisallowedApplication(packageName)
                settings.selectedAppUids.forEach { uid ->
                    pm.getPackagesForUid(uid)?.forEach { pkg ->
                        if (pkg != packageName) {
                            runCatching { builder.addDisallowedApplication(pkg) }
                        }
                    }
                }
            }
            PerAppMode.ALL -> {
                // Exclude only our own app so core traffic doesn't loop
                builder.addDisallowedApplication(packageName)
            }
        }

        return builder
    }
}
