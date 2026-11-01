package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.GitHubAsset
import org.mihomet.app.core.GitHubRelease
import org.mihomet.app.data.update.AppUpdateVerifier
import org.mihomet.app.data.update.AppUpdateVerifier.UpdateDecision
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64

/**
 * Strict tests for the in-app self-update verification chain (audit P1.4).
 *
 * The chain is pure JVM and fail-closed: version-info.json format sanity →
 * tag binding → monotonic versionCode → APK asset binding (exact name with
 * the verified version, pinned GitHub URL) → size gate → signing-certificate
 * equality with the installed app. The updater must offer NOTHING beyond the
 * manual release-page route unless every step passes.
 */
class AppUpdateVerifierStrictTest {

    // ------------------------------------------------------------------
    // Crafted certificate fixtures (self-signed, generated once with openssl;
    // DER SHA-256 digests asserted below so a typo cannot silently pass).
    // ------------------------------------------------------------------

    private val certAPem = """
        -----BEGIN CERTIFICATE-----
        MIIDNzCCAh+gAwIBAgIUP3qCALsVEYjjUlXmoeSbyIjLn+kwDQYJKoZIhvcNAQEL
        BQAwKzEXMBUGA1UEAwwOTWlob21ldCBUZXN0IEExEDAOBgNVBAoMB01paG9tZXQw
        HhcNMjYxMTAxMDg0NjI4WhcNMzYxMDI5MDg0NjI4WjArMRcwFQYDVQQDDA5NaWhv
        bWV0IFRlc3QgQTEQMA4GA1UECgwHTWlob21ldDCCASIwDQYJKoZIhvcNAQEBBQAD
        ggEPADCCAQoCggEBANyC1RymHE1GIkpOX4iidQBh0GcagjH5hBcaGR8ilZfzc+jK
        t298ZDR236z9VUoiqcQb6ba66z/NxOX96Q20DGOzu08g73t55XQz1fkps4qePh4R
        BZXjubbclIjxnDbZCf6BgEtKoOrmr7hMagl79HMY/Z+RCZtdiPzqNr0rgrMNv5Dk
        jL127WhF0c/V15C5RUBJ/kqlF6oysJsKMOoeQGyeJ/bb1+z/T8CPybMVNHh+B0eH
        EVyMuKNo/4pWCRodhqiP9bl1Vx/yKB2MixeZn5PMGtIDlX4AgIwO3AxpgQmfEgLP
        LuTG3Tj2meSbOaMs53gEza8+IaK+7ezXIi+fCasCAwEAAaNTMFEwHQYDVR0OBBYE
        FBS+fsmnwDVFRh6pHFUFefv33/ZxMB8GA1UdIwQYMBaAFBS+fsmnwDVFRh6pHFUF
        efv33/ZxMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAJ2NOFc9
        W9VuFmp4+Wq1edfG/7nxHObxc4krCo7pILsWD0AO57JvsLozXtCDxUbqpXYtyrCk
        nGOSePTkbIXxEQ3GU6+R6bwG8mgc5LCKNvbQin7WgjeLl9ILvHeg2UaAEBX4IXdB
        eAH4OA7KXXc4atf90su1+vHXwXj/2j1JSCnGV4YZWlLqkYz/hqr2T5n9hvMwJbMT
        HtlkuVVCceSDvgDOm3CxxOK85D5luPZg2bcS1swR7iOQcXsY6ESO6nrCmgql7vVo
        CzcrC/xPkdgYPhK/buExeQBdvUklIM3xjUicMC+rXa9sCVTP6QPwLyJzMcIK9S0v
        s82eukBFvKH2oMU=
        -----END CERTIFICATE-----
    """.trimIndent()

    private val certBPem = """
        -----BEGIN CERTIFICATE-----
        MIIDNzCCAh+gAwIBAgIUKX3L+KzQynB9TZYfuOw6B1buzbwwDQYJKoZIhvcNAQEL
        BQAwKzEXMBUGA1UEAwwOTWlob21ldCBUZXN0IEIxEDAOBgNVBAoMB01paG9tZXQw
        HhcNMjYxMTAxMDg0NjI4WhcNMzYxMDI5MDg0NjI4WjArMRcwFQYDVQQDDA5NaWhv
        bWV0IFRlc3QgQjEQMA4GA1UECgwHTWlob21ldDCCASIwDQYJKoZIhvcNAQEBBQAD
        ggEPADCCAQoCggEBAJ6Fj2Qik90rBqqfwPBVIdw8/VdaYyS6fqhk9pT99R1P02Bo
        sIq1S+IPr/3PURiw/K6Benpx3cwGecfgA9W7Y/IkRfbJBeFgTNcYOmPr7KoeuLnS
        DJXKkPgmE/HP+K5PQIAMhSRX2kaMImldY3JmGZdEqR0FCwAXTziSqrfwHggBTK01
        ZsnEzatrzm/0700s15FutvVDV7Hcim2VPRk75NxOYseGqcsYqtFcp+TbPW0HmrdK
        +TsYDI8WY0Jp5p9W8E+q8UolhreXb7UxiwiLS39+VFv2BZ3f9e/pWO5tQ1Z8fGFj
        Su8qeI9bG40NHA+6krAY0Jwpf2anSgSHnfoGRVkCAwEAAaNTMFEwHQYDVR0OBBYE
        FDf/aeVFtnvoOHpUQaeq3quGtBS8MB8GA1UdIwQYMBaAFDf/aeVFtnvoOHpUQaeq
        3quGtBS8MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAHexsKrC
        Cq2bXIX2UoVxqi2nC2Q1oVeZnUz4+NyKryJXDMu1/53eRStnox6g7aySoYj2jLBU
        gGr1/GLNk8QD86JlPMsDGAkuzDx8K1pgLWldWnyVZ2amf2EV5UJrid7I3bxTS7Jx
        8MXyF/zco7R7UDcG3BQkIVLwqKAtPlKysnSyDybmVyfFgTtryOOXma31wIV1j4pB
        K+niVYFLAKHeOD0+nnMk6Nkhfx3jlVsN+pQyvnzR+JI2xSZA0CY2Y58fCPYKNL4Y
        kezY7xBCI4ZM63hwUEpcoKRzXppmZqZKmMRDLzU0hfo6yKND6Wp2S5JisXc/Xp1p
        ArPcN80mI0YiJbw=
        -----END CERTIFICATE-----
    """.trimIndent()

    private fun derFromPem(pem: String): ByteArray {
        val body = pem.lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        return Base64.getMimeDecoder().decode(body)
    }

    private fun certA(): ByteArray = CertificateFactory.getInstance("X.509")
        .generateCertificate(derFromPem(certAPem).inputStream()).encoded

    private fun certB(): ByteArray = CertificateFactory.getInstance("X.509")
        .generateCertificate(derFromPem(certBPem).inputStream()).encoded

    // ------------------------------------------------------------------
    // version-info.json parsing + format sanity
    // ------------------------------------------------------------------

    private fun versionInfoJson(name: String = "1.2.2", code: Long = 6): ByteArray =
        """{"versionName": "$name", "versionCode": $code}""".toByteArray()

    @Test
    fun `parseVersionInfo accepts a well-formed release metadata`() {
        val info = AppUpdateVerifier.parseVersionInfo(versionInfoJson()).getOrThrow()
        assertEquals("1.2.2", info.versionName)
        assertEquals(6L, info.versionCode)
    }

    @Test
    fun `parseVersionInfo tolerates unknown future fields`() {
        val bytes = """{"versionName":"1.2.2","versionCode":6,"minAppVersion":"1.0.0","extra":"x"}"""
            .toByteArray()
        val info = AppUpdateVerifier.parseVersionInfo(bytes).getOrThrow()
        assertEquals(6L, info.versionCode)
        assertEquals("1.0.0", info.minAppVersion)
    }

    @Test
    fun `parseVersionInfo refuses missing name zero or negative code garbage and non-semver name`() {
        val noName = """{"versionCode": 6}""".toByteArray()
        val zeroCode = """{"versionName": "1.2.2", "versionCode": 0}""".toByteArray()
        val negativeCode = """{"versionName": "1.2.2", "versionCode": -1}""".toByteArray()
        val garbage = "not json at all".toByteArray()
        val badName = """{"versionName": "v1.2.2-oops ( Bundled )", "versionCode": 6}""".toByteArray()
        val decoratedName = """{"versionName": "1.2.2 (Bundled)", "versionCode": 6}""".toByteArray()

        assertTrue(AppUpdateVerifier.parseVersionInfo(noName).isFailure)
        assertTrue(AppUpdateVerifier.parseVersionInfo(zeroCode).isFailure)
        assertTrue(AppUpdateVerifier.parseVersionInfo(negativeCode).isFailure)
        assertTrue(AppUpdateVerifier.parseVersionInfo(garbage).isFailure)
        assertTrue(AppUpdateVerifier.parseVersionInfo(badName).isFailure)
        assertTrue(AppUpdateVerifier.parseVersionInfo(decoratedName).isFailure)
    }

    @Test
    fun `checkVersionInfoMatchesTag tolerates the v prefix and binds the tag`() {
        val info = AppUpdateVerifier.parseVersionInfo(versionInfoJson()).getOrThrow()
        assertTrue(AppUpdateVerifier.checkVersionInfoMatchesTag(info, "v1.2.2").isSuccess)
        assertTrue(AppUpdateVerifier.checkVersionInfoMatchesTag(info, "1.2.2").isSuccess)
        assertTrue(AppUpdateVerifier.checkVersionInfoMatchesTag(info, "v1.2.3").isFailure)
        assertTrue(AppUpdateVerifier.checkVersionInfoMatchesTag(info, "v1.2.2-rc1").isFailure)
    }

    @Test
    fun `checkMinAppVersion is a no-op unless version-info carries one`() {
        val info = AppUpdateVerifier.parseVersionInfo(versionInfoJson()).getOrThrow()
        assertTrue(AppUpdateVerifier.checkMinAppVersion(info, "1.0.0").isSuccess)

        val withMin = AppUpdateVerifier.parseVersionInfo(
            """{"versionName":"1.2.2","versionCode":6,"minAppVersion":"1.2.0"}""".toByteArray()
        ).getOrThrow()
        assertTrue(AppUpdateVerifier.checkMinAppVersion(withMin, "1.2.1").isSuccess)
        assertTrue(AppUpdateVerifier.checkMinAppVersion(withMin, "1.2.0").isSuccess)
        assertTrue(AppUpdateVerifier.checkMinAppVersion(withMin, "1.1.9").isFailure)
    }

    // ------------------------------------------------------------------
    // APK asset binding + download URL pinning
    // ------------------------------------------------------------------

    private val apkAssets = listOf(
        GitHubAsset("version-info.json", "https://github.com/mihomet/mihomet/releases/download/v1.2.2/version-info.json", 50L),
        GitHubAsset(
            "Mihomet-v1.2.2-arm64-v8a.apk",
            "https://github.com/mihomet/mihomet/releases/download/v1.2.2/Mihomet-v1.2.2-arm64-v8a.apk",
            25_000_000L
        ),
        GitHubAsset(
            "Mihomet-v1.2.2.apk",
            "https://github.com/mihomet/mihomet/releases/download/v1.2.2/Mihomet-v1.2.2.apk",
            75_000_000L
        )
    )

    @Test
    fun `selectApkAsset prefers the per-ABI split and binds the verified version`() {
        val apk = AppUpdateVerifier.selectApkAsset(apkAssets, "1.2.2", "arm64-v8a").getOrThrow()
        assertEquals("Mihomet-v1.2.2-arm64-v8a.apk", apk.name)
        assertEquals(25_000_000L, apk.size)
        assertTrue(apk.url.endsWith("/Mihomet-v1.2.2-arm64-v8a.apk"))
    }

    @Test
    fun `selectApkAsset falls back to the universal apk`() {
        val apk = AppUpdateVerifier.selectApkAsset(apkAssets, "1.2.2", "mips").getOrThrow()
        assertEquals("Mihomet-v1.2.2.apk", apk.name)
    }

    @Test
    fun `selectApkAsset refuses wrong version other assets zero size or url mismatch`() {
        // no asset for a different version
        assertTrue(AppUpdateVerifier.selectApkAsset(apkAssets, "1.2.3", "arm64-v8a").isFailure)
        // empty asset list
        assertTrue(AppUpdateVerifier.selectApkAsset(emptyList(), "1.2.2", "arm64-v8a").isFailure)
        // zero size
        val zeroSize = listOf(
            GitHubAsset(
                "Mihomet-v1.2.2-arm64-v8a.apk",
                "https://github.com/mihomet/mihomet/releases/download/v1.2.2/Mihomet-v1.2.2-arm64-v8a.apk",
                0L
            )
        )
        assertTrue(AppUpdateVerifier.selectApkAsset(zeroSize, "1.2.2", "arm64-v8a").isFailure)
        // URL does not end with the asset name
        val urlMismatch = listOf(
            GitHubAsset("Mihomet-v1.2.2-arm64-v8a.apk", "https://evil.example/other.apk", 1L)
        )
        assertTrue(AppUpdateVerifier.selectApkAsset(urlMismatch, "1.2.2", "arm64-v8a").isFailure)
    }

    @Test
    fun `only mihomet release URLs are trusted for downloads`() {
        assertTrue(
            AppUpdateVerifier.isTrustedReleaseUrl(
                "https://github.com/mihomet/mihomet/releases/download/v1.2.2/Mihomet-v1.2.2-arm64-v8a.apk"
            )
        )
        assertTrue(AppUpdateVerifier.isTrustedReleaseUrl(AppUpdateVerifier.RELEASES_API_LATEST))
        assertFalse(AppUpdateVerifier.isTrustedReleaseUrl("https://evil.example/Mihomet-v1.2.2.apk"))
        assertFalse(AppUpdateVerifier.isTrustedReleaseUrl("http://github.com/mihomet/mihomet/releases/download/v1.2.2/x.apk"))
        assertFalse(
            AppUpdateVerifier.isTrustedReleaseUrl(
                "https://github.com/attacker/mihomet/releases/download/v1.2.2/Mihomet-v1.2.2.apk"
            )
        )
        assertFalse(AppUpdateVerifier.isTrustedReleaseUrl(""))
    }

    @Test
    fun `verifyApkSize enforces the exact GitHub asset size`() {
        assertTrue(AppUpdateVerifier.verifyApkSize(25_000_000L, 25_000_000L).isSuccess)
        assertTrue(AppUpdateVerifier.verifyApkSize(25_000_000L, 25_000_000L - 1).isFailure)  // truncated
        assertTrue(AppUpdateVerifier.verifyApkSize(25_000_000L, 25_000_000L + 1).isFailure)  // padded
    }

    // ------------------------------------------------------------------
    // Signing-certificate anchor
    // ------------------------------------------------------------------

    @Test
    fun `test certificates parse and carry the expected digests`() {
        val sha = { bytes: ByteArray ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        assertEquals("c2e481239738fbd3b8a3ae9f6ca6eff3e0a5a9a603687b9b7e406acf0ef1140c", sha(certA()))
        assertEquals("909a0994ea1d00ae61385f3345a60b70f2c026d408511a1af5d5f59b03c8f1d3", sha(certB()))
    }

    @Test
    fun `signingCertsMatch accepts only the installed certificate`() {
        assertTrue(AppUpdateVerifier.signingCertsMatch(listOf(certA()), listOf(certA())))
        // same certificate encoded twice (der list order) still matches
        assertTrue(AppUpdateVerifier.signingCertsMatch(listOf(certA(), certA()), listOf(certA())))

        assertFalse("a different certificate must be refused", AppUpdateVerifier.signingCertsMatch(listOf(certA()), listOf(certB())))
        assertFalse("an unknown certificate must be refused", AppUpdateVerifier.signingCertsMatch(listOf(certA()), listOf("garbage".toByteArray())))
    }

    @Test
    fun `signingCertsMatch tolerates certificate history overlap and refuses empty input`() {
        // rotation: installed history contains certA + certB, APK is signed by certB
        assertTrue(AppUpdateVerifier.signingCertsMatch(listOf(certA(), certB()), listOf(certB())))
        // no overlap → refused
        assertFalse(AppUpdateVerifier.signingCertsMatch(listOf(certA()), listOf(certB(), "x".toByteArray())))
        // empty inputs never match (fail-closed)
        assertFalse(AppUpdateVerifier.signingCertsMatch(emptyList(), listOf(certA())))
        assertFalse(AppUpdateVerifier.signingCertsMatch(listOf(certA()), emptyList()))
    }

    // ------------------------------------------------------------------
    // Full decision matrix (check phase)
    // ------------------------------------------------------------------

    private fun release(
        tag: String,
        assets: List<GitHubAsset> = apkAssets
    ): GitHubRelease = GitHubRelease(tag_name = tag, name = tag, assets = assets)

    private fun installableDecision(): UpdateDecision.Installable =
        AppUpdateVerifier.decideUpdate(
            release("v1.2.2"),
            installedVersionName = "1.2.1",
            installedVersionCode = 5L,
            abi = "arm64-v8a",
            versionInfoBytes = versionInfoJson()
        ) as UpdateDecision.Installable

    @Test
    fun `happy chain yields an installable decision with the abi asset`() {
        val decision = installableDecision()
        assertEquals("1.2.2", decision.version)
        assertEquals(6L, decision.versionCode)
        assertEquals("Mihomet-v1.2.2-arm64-v8a.apk", decision.apk.name)
    }

    @Test
    fun `up-to-date or older tags and downgrades yield NoUpdate`() {
        // same version
        val same = AppUpdateVerifier.decideUpdate(release("v1.2.1"), "1.2.1", 5L, "arm64-v8a", versionInfoJson("1.2.1", 5))
        assertTrue(same is UpdateDecision.NoUpdate)
        // older tag
        val older = AppUpdateVerifier.decideUpdate(release("v1.2.0"), "1.2.1", 5L, "arm64-v8a", versionInfoJson("1.2.0", 4))
        assertTrue(older is UpdateDecision.NoUpdate)
        // newer tag but NON-monotonic versionCode → replay/rollback refusal (fail-closed)
        val replayed = AppUpdateVerifier.decideUpdate(release("v1.2.2"), "1.2.1", 6L, "arm64-v8a", versionInfoJson("1.2.2", 6))
        assertTrue("versionCode replay must be refused", replayed is UpdateDecision.NoUpdate)
    }

    @Test
    fun `missing or broken version-info forces the manual route`() {
        // release without the version-info asset (e.g. v1.2.1-era releases)
        val noInfo = AppUpdateVerifier.decideUpdate(release("v1.2.2"), "1.2.1", 5L, "arm64-v8a", null)
        assertTrue(noInfo is UpdateDecision.ManualOnly)

        val garbageInfo = AppUpdateVerifier.decideUpdate(
            release("v1.2.2"), "1.2.1", 5L, "arm64-v8a", "garbage".toByteArray()
        )
        assertTrue(garbageInfo is UpdateDecision.ManualOnly)

        val tagMismatch = AppUpdateVerifier.decideUpdate(
            release("v1.2.2"), "1.2.1", 5L, "arm64-v8a", versionInfoJson("1.2.3", 7)
        )
        assertTrue(tagMismatch is UpdateDecision.ManualOnly)
    }

    @Test
    fun `missing abi asset forces the manual route`() {
        val armOnly = listOf(apkAssets[1])  // only the arm64 split
        val decision = AppUpdateVerifier.decideUpdate(
            release("v1.2.2", armOnly), "1.2.1", 5L, "x86_64", versionInfoJson()
        )
        assertTrue(decision is UpdateDecision.ManualOnly)
    }

    @Test
    fun `min version gate forces the manual route when the installed build is too old`() {
        val decision = AppUpdateVerifier.decideUpdate(
            release("v1.2.2"), "1.2.1", 5L, "arm64-v8a",
            """{"versionName":"1.2.2","versionCode":6,"minAppVersion":"1.3.0"}""".toByteArray()
        )
        assertTrue(decision is UpdateDecision.ManualOnly)
    }

    @Test
    fun `untrusted apk url forces the manual route`() {
        val evil = listOf(
            GitHubAsset(
                "Mihomet-v1.2.2-arm64-v8a.apk",
                "https://evil.example/releases/Mihomet-v1.2.2-arm64-v8a.apk",
                25_000_000L
            ),
            GitHubAsset("version-info.json", "https://evil.example/version-info.json", 50L)
        )
        val decision = AppUpdateVerifier.decideUpdate(release("v1.2.2", evil), "1.2.1", 5L, "arm64-v8a", versionInfoJson())
        assertTrue(decision is UpdateDecision.ManualOnly)
    }
}
