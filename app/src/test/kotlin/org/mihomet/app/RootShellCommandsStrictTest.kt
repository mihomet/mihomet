package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.firewall.FirewallEngine
import org.mihomet.app.core.firewall.IptablesEngine
import org.mihomet.app.core.firewall.NftablesEngine
import org.mihomet.app.core.root.RootShellManager
import org.mihomet.app.data.preferences.PerAppMode

/**
 * Strict tests for the OEM (OnePlus / Realme / ColorOS) firewall-chain
 * save/restore shell builders in [RootShellManager] and the F10 teardown
 * builders of the firewall engines.
 *
 * Contract (external audit F09/F10):
 *
 *  F09 — startDaemon flushes the OEM custom DNS reject chain
 *  [CoreConstants.OPLUS_DNS_CHAIN] (`fw_OUTPUT_oplus_dns`, filter table) for the
 *  daemon lifetime. Without a snapshot the OEM rules would be lost forever, so:
 *   - the snapshot must be taken BEFORE the flush, gated on the chain's real
 *     existence (an `-nL` exit-code check), and written in the kernel's own
 *     `iptables-save` format — a line-by-line shell replay of `iptables -S`
 *     output mis-tokenizes rules on word-splitting (quotes / spaces inside
 *     `--comment`), while `iptables-restore` parses its native quoting safely;
 *   - only the chain's OWN `-A` rule lines are captured — jump rules from other
 *     chains would be duplicated by the append-mode restore on every session;
 *   - the restore must run on stop (appended after the Private DNS restore
 *     block) and from the crash-recovery path, recreate the chain when it
 *     disappeared, replay the saved lines through
 *     `iptables-restore --noflush` (APPEND to the existing table), and delete
 *     the save files ONLY after a successful restore — a failed restore keeps
 *     the files for the next attempt / manual recovery and logs the failure;
 *   - if the snapshot fails while the chain exists and has rules, the flush
 *     still runs (the OEM chain breaks DNS otherwise) and logs a warning —
 *     a documented "rules may be lost" trade-off.
 *
 *  F10 — teardown must not harm foreign state:
 *   - the pref-100 rule purge loops filter by OUR fwmark
 *     ([CoreConstants.CORE_ROUTING_MARK]); foreign pref-100 rules with other
 *     fwmarks (verified in a netns with fwmark 123 vs 666) must survive;
 *   - stopDaemon kills leftover core processes by the FULL binary path only —
 *     name-based `killall mihomo` can murder a foreign same-named process —
 *     with the pid-file kill (SIGTERM then SIGKILL) as the primary mechanism;
 *   - the sysctls the engines change (ip_forward, rp_filter all/default) are
 *     snapshotted before the first change and restored on teardown; with no
 *     snapshot present they are left untouched.
 *
 * The iptables-save / iptables-restore --noflush cycle and the fwmark-filtered
 * purge were verified live (user+network namespace: quoted `--comment` rules
 * survive save→flush→restore byte-exact; foreign fwmark-123 pref-100 rules
 * survive the filtered purge in both address families).
 */
class RootShellCommandsStrictTest {

    // ---------- helpers ----------

    private fun allInts(text: String): Set<Int> =
        Regex("\\d+").findAll(text).map { it.value.toInt() }.toSet()

    /** (iptables binary, save binary, save file) triples — one per address family. */
    private val families = listOf(
        Triple("iptables", "iptables-save", CoreConstants.ROOT_OPLUS_RULES_V4_FILE),
        Triple("ip6tables", "ip6tables-save", CoreConstants.ROOT_OPLUS_RULES_V6_FILE)
    )

    private val chain = CoreConstants.OPLUS_DNS_CHAIN
    private val bak = CoreConstants.ROOT_SYSCTL_BACKUP_FILE

    private val allowedNumbers = setOf(
        0, 1, 2, // shell redirects: 2>/dev/null, 2>&1; head/tail line indexes
        4, 6     // per-family save-file suffixes (.oplus_v4.rules) / "ip -6"
    )

    /** The exact fwmark-filtered purge loops (F10) — shared by both engines. */
    private val purgeV4 =
        "while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done"
    private val purgeV6 =
        "while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done"

    // ---------- external OEM contract ----------

    @Test
    fun `oplus chain contract is pinned to the coloros chain name and run dir save files`() {
        // The chain name is an OEM (ColorOS/realme) firewall artifact: renaming it
        // would silently stop touching the real OEM chain.
        assertEquals("fw_OUTPUT_oplus_dns", chain)
        for ((_, _, file) in families) {
            assertTrue(
                "save file $file must live inside the root run dir",
                file.startsWith("${CoreConstants.ROOT_RUN_DIR}/")
            )
        }
        // one file per family, distinct
        assertTrue(CoreConstants.ROOT_OPLUS_RULES_V4_FILE != CoreConstants.ROOT_OPLUS_RULES_V6_FILE)
    }

    // ---------- save (F09) ----------

    @Test
    fun `save commands dump kernel-quoted iptables-save lines per family only when the chain exists`() {
        val cmds = RootShellManager.buildSaveOplusCommands()
        assertEquals("run-dir mkdir plus one save command per family", 3, cmds.size)
        // the run dir must exist before any save file is written
        assertEquals("mkdir -p ${CoreConstants.ROOT_RUN_DIR}", cmds.first())

        for ((tool, saveTool, file) in families) {
            val cmd = cmds.first { it.startsWith("if $tool -t filter -nL") }
            // existence gate via the -nL exit code, evaluated BEFORE the dump
            assertTrue(
                "save must gate on chain existence via -nL exit code, got: $cmd",
                cmd.startsWith("if $tool -t filter -nL $chain >/dev/null 2>&1; then ")
            )
            // F09: the dump is `iptables-save -t filter` (kernel-quoted format),
            // filtered to the chain's OWN -A rule lines and written to the
            // family's save file
            assertTrue(
                "save must write the iptables-save dump of the chain into $file, got: $cmd",
                cmd.contains(
                    "if $saveTool -t filter 2>/dev/null | grep -E '^-A $chain ' > $file 2>/dev/null; then :; "
                )
            )
            // a failed dump warns (documented flush-anyway trade-off) and drops the file
            assertTrue(
                "a failed snapshot must log the rules-will-be-lost warning, got: $cmd",
                cmd.contains("else rm -f $file; echo 'mihomet: warning: OEM chain snapshot failed; rules will be lost' >&2; fi; ")
            )
            // a missing chain removes the stale save file instead of keeping old rules for restore
            assertTrue(
                "a missing chain must remove the stale save file, got: $cmd",
                cmd.endsWith("else rm -f $file; fi")
            )
            // F09 regression: the old `iptables -S` + word-splitting shell replay is gone
            assertFalse(
                "save must not use the `iptables -S` line dump (breaks on quoted comments), got: $cmd",
                cmd.contains(" -S ")
            )
        }

        // no cross-family leakage: each dump lands in exactly its own file
        val v4 = cmds.first { it.contains("iptables-save -t filter") }
        val v6 = cmds.first { it.contains("ip6tables-save -t filter") }
        assertFalse(v4.contains(CoreConstants.ROOT_OPLUS_RULES_V6_FILE))
        assertFalse(v6.contains(CoreConstants.ROOT_OPLUS_RULES_V4_FILE))
        // the save step must not mutate the chain in any way
        val joined = cmds.joinToString("\n")
        assertFalse("save must not flush the chain", joined.contains(" -F "))
        assertFalse("save must not create or delete the chain", joined.contains(" -N ") || joined.contains(" -X "))
    }

    // ---------- restore (F09) ----------

    @Test
    fun `restore replays the snapshot through iptables-restore noflush and keeps the file on failure`() {
        val cmds = RootShellManager.buildRestoreOplusCommands()
        assertEquals("one restore command per family", 2, cmds.size)

        for ((tool, _, file) in families) {
            val cmd = cmds.first { it.startsWith("if [ -f $file ]") }
            // restore only runs when a snapshot exists
            assertTrue(
                "restore must be gated on the save file, got: $cmd",
                cmd.startsWith("if [ -f $file ]; then $tool -t filter -N $chain 2>/dev/null || true; ")
            )
            // F09: the chain is ensured (-N || true), then the saved kernel-quoted
            // lines are fed as an *filter stream through iptables-restore --noflush
            // (append to the existing table — startup flushed the chain, the replay
            // refills it without touching any other rule)
            assertTrue(
                "restore must replay the saved rules via $tool-restore --noflush, got: $cmd",
                cmd.contains(
                    "if { echo '*filter'; grep -E '^-A $chain ' $file 2>/dev/null; echo COMMIT; } " +
                            "| $tool-restore --noflush 2>/dev/null; then rm -f $file; "
                )
            )
            // F09: the snapshot is consumed ONLY after a successful restore; a
            // failure keeps the file for the next attempt / manual recovery and logs
            assertTrue(
                "a failed restore must keep the snapshot and log it, got: $cmd",
                cmd.endsWith("else echo 'mihomet: OEM chain restore failed; snapshot kept in $file' >&2; fi; fi")
            )
            // F09 regression: no line-by-line shell replay (word-splitting breaks quoting)
            assertFalse(
                "restore must not replay rules via a read-loop with word-splitting, got: $cmd",
                cmd.contains("\$line") || cmd.contains("read -r line")
            )
        }

        // no cross-family leakage
        val v4 = cmds.first { it.contains("iptables-restore --noflush") }
        val v6 = cmds.first { it.contains("ip6tables-restore --noflush") }
        assertFalse(v4.contains(CoreConstants.ROOT_OPLUS_RULES_V6_FILE))
        assertFalse(v6.contains(CoreConstants.ROOT_OPLUS_RULES_V4_FILE))
    }

    // ---------- save-before-flush ordering (startDaemon step 1) ----------

    @Test
    fun `flush commands snapshot the chains before flushing them`() {
        val cmds = RootShellManager.buildOplusFlushCommands()

        // the flush of both families is present, best-effort style (unchanged contract)
        for (tool in listOf("iptables", "ip6tables")) {
            assertTrue(
                cmds.contains("$tool -t filter -F ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true")
            )
        }
        // the flush builder is exactly save ++ flush: the snapshot MUST precede the
        // flush, otherwise it would capture the already-emptied chains (audit F09)
        val save = RootShellManager.buildSaveOplusCommands()
        assertEquals(save, cmds.take(save.size))
        val lastSave = cmds.indexOfLast { it.contains("-save -t filter") }
        val firstFlush = cmds.indexOfFirst { it.contains(" -F ${CoreConstants.OPLUS_DNS_CHAIN} ") }
        assertTrue("the snapshot must be part of the flush step", lastSave >= 0)
        assertTrue("snapshot must precede the flush", lastSave < firstFlush)
    }

    // ---------- stop wiring ----------

    @Test
    fun `stop commands restore the oplus chains after the private dns block`() {
        val cmds = RootShellManager.buildStopDaemonCommands()

        val dnsIdx = cmds.indexOfFirst { "private_dns_mode" in it }
        val restoreIdx = cmds.indexOfFirst {
            it.startsWith("if [ -f ${CoreConstants.ROOT_OPLUS_RULES_V4_FILE} ]")
        }
        assertTrue("the private dns restore block must exist", dnsIdx >= 0)
        assertTrue("the oplus restore must be part of the stop commands", restoreIdx >= 0)
        assertTrue(
            "the oplus restore must run after the private dns restore block",
            dnsIdx < restoreIdx
        )

        // the full restore builder is appended verbatim as the trailing commands
        val restore = RootShellManager.buildRestoreOplusCommands()
        assertEquals(restore, cmds.takeLast(restore.size))
    }

    // ---------- F10: targeted leftover-core kill ----------

    @Test
    fun `stopDaemon kills leftover cores by full path only and never by name`() {
        val cmds = RootShellManager.buildStopDaemonCommands()
        val joined = cmds.joinToString("\n")

        // F10 regression: name-based killall would murder a foreign same-named process
        assertFalse(
            "stopDaemon must never use name-based killall (foreign mihomo would die)",
            joined.contains("killall")
        )
        // the only process kill besides the pid-file kill matches OUR full binary path
        assertTrue(
            "stopDaemon must kill leftover cores by our full path",
            joined.contains("pkill -f '${CoreConstants.ROOT_BIN_PATH}'")
        )
        // never a broad pattern kill
        assertFalse("stopDaemon must never use 'pkill -9 -f'", joined.contains("pkill -9 -f"))

        // shutdown pairing: the pid-file kill (SIGTERM -> SIGKILL) stays primary
        val pidIdx = cmds.indexOfFirst { "kill -15" in it }
        val pkillIdx = cmds.indexOfFirst { it.startsWith("pkill -f") }
        assertTrue("the pid-file kill block must exist", pidIdx >= 0)
        assertTrue(
            "pid-file kill must precede the path-based pkill cleanup",
            pidIdx < pkillIdx
        )
    }

    // ---------- F10: fwmark-filtered purge loops (both engines) ----------

    @Test
    fun `purge loops in both engines filter by our fwmark so foreign pref-100 rules survive`() {
        val iptSetup = IptablesEngine.buildSetupCommands(
            tproxyPort = CoreConstants.DEFAULT_TPROXY_PORT,
            perAppMode = PerAppMode.ALL,
            appUids = emptySet(),
            ipv6Enabled = true
        )
        val iptTeardown = IptablesEngine.buildTeardownCommands().joinToString("\n")
        val nftRouting = NftablesEngine.buildRoutingCommands(ipv6Enabled = true)
        val nftTeardown = NftablesEngine.buildTeardownCommands().joinToString("\n")

        for ((name, joined) in listOf(
            "iptables setup" to iptSetup.joinToString("\n"),
            "iptables teardown" to iptTeardown,
            "nftables routing" to nftRouting,
            "nftables teardown" to nftTeardown
        )) {
            assertTrue(
                "$name must purge stale rules with the fwmark-filtered v4 loop",
                joined.contains(purgeV4)
            )
            assertTrue(
                "$name must purge stale rules with the fwmark-filtered v6 loop",
                joined.contains(purgeV6)
            )
            // F10 regression: a bare pref-only loop also deletes foreign pref-100 rules
            assertFalse(
                "$name must not purge by pref alone (foreign pref-100 rules would die)",
                joined.contains("del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null")
            )
        }

        // setup ordering: the purge still precedes re-adding the fwmark-666 rule
        val iptPurgeIdx = iptSetup.indexOfFirst { it.contains("while ip rule del") }
        val iptAddIdx = iptSetup.indexOfFirst { it.contains("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup") }
        assertTrue("iptables purge must precede the fwmark rule re-add", iptPurgeIdx in 0 until iptAddIdx)
        assertTrue(
            "nftables purge must precede the fwmark rule re-add",
            nftRouting.indexOf(purgeV4) < nftRouting.indexOf("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup")
        )
    }

    // ---------- F10: sysctl backup / restore ----------

    @Test
    fun `sysctl values are snapshotted before the engines change them and restored on teardown`() {
        val backup = FirewallEngine.buildSysctlBackupCommands()
        val restore = FirewallEngine.buildSysctlRestoreCommands()
        assertEquals("mkdir plus one gated snapshot command", 2, backup.size)
        assertEquals("one gated restore command", 1, restore.size)

        assertEquals("mkdir -p ${CoreConstants.ROOT_RUN_DIR}", backup[0])
        val backupCmd = backup[1]
        // the backup is gated on the file's absence: a stale snapshot from a crashed
        // session is never overwritten with the already-modified values
        assertTrue(
            "backup must be gated on the file's absence, got: $backupCmd",
            backupCmd.startsWith("if [ ! -f $bak ]; then ")
        )
        assertTrue(backupCmd.contains("cat /proc/sys/net/ipv4/ip_forward > $bak 2>/dev/null; "))
        assertTrue(backupCmd.contains("cat /proc/sys/net/ipv4/conf/all/rp_filter >> $bak 2>/dev/null; "))
        assertTrue(backupCmd.contains("cat /proc/sys/net/ipv4/conf/default/rp_filter >> $bak 2>/dev/null; fi"))
        assertFalse("backup must not clobber an existing snapshot", backupCmd.contains("> $bak; fi"))

        val restoreCmd = restore.single()
        // restore only when a backup exists — "if no backup, do not touch"
        assertTrue(
            "restore must be gated on the backup file, got: $restoreCmd",
            restoreCmd.startsWith("if [ -f $bak ]; then ")
        )
        assertTrue(restoreCmd.contains("echo \"\$IPFWD\" > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true; "))
        assertTrue(restoreCmd.contains("echo \"\$RPALL\" > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true; "))
        assertTrue(restoreCmd.contains("echo \"\$RPDEF\" > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true; "))
        assertTrue("restore must consume the backup file", restoreCmd.endsWith("rm -f $bak; fi"))

        // wiring: backup precedes the sysctl writes in both engines' setup batches
        val iptSetup = IptablesEngine.buildSetupCommands(
            tproxyPort = CoreConstants.DEFAULT_TPROXY_PORT,
            perAppMode = PerAppMode.ALL,
            appUids = emptySet(),
            ipv6Enabled = true
        )
        val backupIdx = iptSetup.indexOfFirst { it.contains(bak) }
        val writeIdx = iptSetup.indexOfFirst { it.contains("echo 1 > /proc/sys/net/ipv4/ip_forward") }
        assertTrue("iptables setup must snapshot the sysctls before changing them", backupIdx in 0 until writeIdx)

        val nftRouting = NftablesEngine.buildRoutingCommands(ipv6Enabled = true)
        val backupStr = backup.drop(1).joinToString("; ")
        assertTrue("nftables routing must carry the sysctl snapshot", nftRouting.contains(backupStr))
        assertTrue(
            "nftables routing must snapshot the sysctls before changing them",
            nftRouting.indexOf(backupStr) < nftRouting.indexOf("echo 1 > /proc/sys/net/ipv4/ip_forward")
        )

        // wiring: restore is part of both engines' teardown batches
        assertTrue(
            "iptables teardown must restore the snapshotted sysctls",
            IptablesEngine.buildTeardownCommands().contains(restoreCmd)
        )
        assertTrue(
            "nftables teardown must restore the snapshotted sysctls",
            NftablesEngine.buildTeardownCommands().contains(restoreCmd)
        )
    }

    // ---------- shell hygiene ----------

    @Test
    fun `root teardown builders carry no numeric magic and no destructive surprises`() {
        val all = (
                RootShellManager.buildSaveOplusCommands() +
                        RootShellManager.buildRestoreOplusCommands() +
                        RootShellManager.buildOplusFlushCommands() +
                        FirewallEngine.buildSysctlBackupCommands() +
                        FirewallEngine.buildSysctlRestoreCommands()
                ).joinToString("\n")

        val foreign = allInts(all).filter { it !in allowedNumbers }
        assertTrue("root teardown commands must not contain numeric magic, found: $foreign", foreign.isEmpty())

        // best-effort teardown style only: no broad pattern kills, no recursive rm
        assertFalse(all.contains("pkill -9 -f"))
        assertFalse(all.contains("rm -rf"))
        // F09 regression: no word-splitting shell replay anywhere
        assertFalse(all.contains("\$line"))
        assertFalse(all.contains("read -r line"))
    }
}
