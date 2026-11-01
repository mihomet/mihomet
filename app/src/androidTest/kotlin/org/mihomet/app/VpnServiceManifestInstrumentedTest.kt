package org.mihomet.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VpnService / root-service manifest contract (audit P1.3).
 *
 * The automated VPN CONNECT flow is deliberately NOT exercised here: on a real
 * device the first start shows the system VPN consent dialog, which makes CI
 * flaky by design. Instead this pins everything that CAN be asserted
 * deterministically: the service class exists, extends VpnService, and its
 * manifest entry keeps the exact exported/permission/FGS-type contract the
 * security model relies on. The live path is covered by the manual emulator
 * smoke test (start → foreground notification → stop) documented in the
 * build-stack report.
 */
@RunWith(AndroidJUnit4::class)
class VpnServiceManifestInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm: PackageManager = context.packageManager

    @Test
    fun vpnServiceClassExistsAndExtendsVpnService() {
        val clazz = Class.forName("org.mihomet.app.core.vpn.MihometVpnService")
        assertTrue(android.net.VpnService::class.java.isAssignableFrom(clazz))
    }

    @Test
    fun vpnServiceManifestContractHolds() {
        val info = pm.getServiceInfo(
            ComponentName(context, "org.mihomet.app.core.vpn.MihometVpnService"),
            PackageManager.GET_META_DATA
        )
        assertNotNull(info)
        assertEquals("VpnService must not be exported", false, info.exported)
        assertEquals(
            "VpnService must require the system BIND_VPN_SERVICE permission",
            "android.permission.BIND_VPN_SERVICE",
            info.permission
        )
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(
                "VpnService foregroundServiceType must be systemExempted",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                info.foregroundServiceType
            )
        }
    }

    @Test
    fun rootTProxyServiceManifestContractHolds() {
        val info = pm.getServiceInfo(
            ComponentName(context, "org.mihomet.app.core.root.RootTProxyService"),
            PackageManager.GET_META_DATA
        )
        assertNotNull(info)
        assertEquals("RootTProxyService must not be exported", false, info.exported)
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(
                "RootTProxyService foregroundServiceType must be specialUse",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                info.foregroundServiceType
            )
        }
    }

    @Test
    fun vpnServicePermissionIsNotSelfSignable() {
        // Sanity: the permission gate is the platform's, not a custom one an
        // attacker could hold.
        val perm = pm.getPermissionInfo("android.permission.BIND_VPN_SERVICE", 0)
        assertEquals("android", perm.packageName)
    }
}
