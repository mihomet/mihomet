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
 *   - H2 (audit N04): the dump is staged into a per-family `.tmp` file,
 *     verified (non-empty AND containing the chain's `-A` lines) and only then
 *     atomically `mv`ed over the save file — a partial/failed iptables-save
 *     output can never truncate a good snapshot (the pre-H2 `> saveFile`
 *     redirect destroyed it);
 *   - H2: the flush of each family is gated on its own verified snapshot file —
 *     a family whose snapshot failed is NOT flushed (the OEM rules are
 *     preserved; root mode may be partially broken for the session, which is
 *     the documented trade-off, reversing the pre-H2 "flush anyway" behaviour);
 *   - H2: the flush block replays any snapshot left over from a crashed
 *     session BEFORE taking the fresh one, so a new snapshot can never
 *     overwrite the only surviving copy of the OEM rules;
 *   - the restore must run on stop (appended after the Private DNS restore
 *     block) and from the crash-recovery path, recreate the chain when it
 *     disappeared, replay the saved lines through
 *     `iptables-restore --noflush` (APPEND to the existing table), and delete
 *     the save files ONLY after a successful restore — a failed restore keeps
 *     the files for the next attempt / manual recovery and logs the failure;
 *     H2: the restore only runs for a snapshot that exists, is non-empty and
 *     contains the chain's `-A` lines — anything else is garbage and is
 *     removed.
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

    /** H2: the staging file matching a family's save file. */
    private fun tmpFor(file: String): String = when (file) {
        CoreConstants.ROOT_OPLUS_RULES_V4_FILE -> CoreConstants.ROOT_OPLUS_RULES_V4_TMP_FILE
        else -> CoreConstants.ROOT_OPLUS_RULES_V6_TMP_FILE
    }

    private val chain = CoreConstants.OPLUS_DNS_CHAIN
    private val bak = CoreConstants.ROOT_SYSCTL_BACKUP_FILE

    private val allowedNumbers = setOf(
        0, 1, 2, // shell redirects: 2>/dev/null, 2>&1; head/tail line indexes
        4, 6     // per-family save/tmp-file suffixes (.oplus_v4.rules[.tmp]) / "ip -6"
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
        // H2: one staging file per family, inside the run dir (same filesystem so
        // the publish is an atomic rename) and distinct from every save file
        for (tmp in listOf(CoreConstants.ROOT_OPLUS_RULES_V4_TMP_FILE, CoreConstants.ROOT_OPLUS_RULES_V6_TMP_FILE)) {
            assertTrue(
                "staging file $tmp must live inside the root run dir",
                tmp.startsWith("${CoreConstants.ROOT_RUN_DIR}/")
            )
            assertFalse(
                "staging file $tmp must not double as a save file",
                tmp == CoreConstants.ROOT_OPLUS_RULES_V4_FILE || tmp == CoreConstants.ROOT_OPLUS_RULES_V6_FILE
            )
        }
        assertTrue(CoreConstants.ROOT_OPLUS_RULES_V4_TMP_FILE != CoreConstants.ROOT_OPLUS_RULES_V6_TMP_FILE)
        assertEquals(".oplus_v4.rules.tmp", CoreConstants.ROOT_OPLUS_RULES_V4_TMP_FILE.substringAfterLast('/'))
        assertEquals(".oplus_v6.rules.tmp", CoreConstants.ROOT_OPLUS_RULES_V6_TMP_FILE.substringAfterLast('/'))
    }

    // ---------- save (F09 + H2) ----------

    @Test
    fun `save commands stage the dump and only atomically publish a verified snapshot`() {
        val cmds = RootShellManager.buildSaveOplusCommands()
        assertEquals("run-dir mkdir plus one save command per family", 3, cmds.size)
        // the run dir must exist before any save file is written
        assertEquals("mkdir -p ${CoreConstants.ROOT_RUN_DIR}", cmds.first())

        for ((tool, saveTool, file) in families) {
            val tmp = tmpFor(file)
            val cmd = cmds.first { it.startsWith("if $tool -t filter -nL") }
            // existence gate via the -nL exit code, evaluated BEFORE the dump
            assertTrue(
                "save must gate on chain existence via -nL exit code, got: $cmd",
                cmd.startsWith("if $tool -t filter -nL $chain >/dev/null 2>&1; then ")
            )
            // F09: the dump is `iptables-save -t filter` (kernel-quoted format),
            // filtered to the chain's OWN -A rule lines; H2: it is staged into
            // the family's tmp file — NEVER redirected into the save file (the
            // pre-H2 `> saveFile` truncation destroyed a good snapshot whenever
            // the dump failed or the chain had no rules)
            assertTrue(
                "save must stage the iptables-save dump of the chain in $tmp, got: $cmd",
                cmd.contains("$saveTool -t filter 2>/dev/null | grep -E '^-A $chain ' > $tmp 2>/dev/null; ")
            )
            assertFalse(
                "the dump must never redirect into the save file $file, got: $cmd",
                // space-anchored: the staged redirect `> $tmp` (tmp ends in
                // `.rules.tmp`) must not produce a false substring hit
                cmd.contains("> $file ") || cmd.endsWith("> $file")
            )
            // H2: the staged dump is verified — non-empty AND holding the
            // chain's own -A rule lines — before it is published
            assertTrue(
                "save must verify the staged snapshot before publishing, got: $cmd",
                cmd.contains("if [ -s $tmp ] && grep -qE '^-A $chain ' $tmp 2>/dev/null; then ")
            )
            // ... and published atomically (rename within the same directory)
            // — H2: the flush is the LAST statement of the VERIFIED branch, the
            // shell realization of the "snapshotOk" flag: the chain is emptied
            // if and only if THIS run verified a fresh snapshot
            assertTrue(
                "save must publish with mv -f $tmp $file and flush only inside the verified branch, got: $cmd",
                cmd.contains("then mv -f $tmp $file; $tool -t filter -F $chain 2>/dev/null || true; else ")
            )
            // a failed/empty snapshot drops only the staging file and warns that
            // the rules are PRESERVED (the gated flush will skip the chain)
            assertTrue(
                "a failed snapshot must log the rules-preserved warning, got: $cmd",
                cmd.contains("else rm -f $tmp; echo 'mihomet: warning: OEM chain snapshot failed; rules preserved' >&2; fi; ")
            )
            // H2: a failed snapshot must never delete a previous save file — it
            // may be the only surviving copy when an earlier restore failed.
            // (Semicolon-anchored: `rm -f $tmp;` where tmp ends in `.rules.tmp`
            // must not produce a false substring hit on the shorter save path.)
            assertFalse(
                "a failed snapshot must not delete the previous save file $file, got: $cmd",
                cmd.contains("rm -f $file;") || cmd.endsWith("rm -f $file")
            )
            // a missing chain only clears the staging file; the save files are
            // owned by the restore step (consumed on success, kept on failure)
            assertTrue(
                "a missing chain must remove only the staging file, got: $cmd",
                cmd.endsWith("else rm -f $tmp; fi")
            )
            // F09 regression: the old `iptables -S` + word-splitting shell replay is gone
            assertFalse(
                "save must not use the `iptables -S` line dump (breaks on quoted comments), got: $cmd",
                cmd.contains(" -S ")
            )
        }

        // no cross-family leakage: each dump lands in exactly its own tmp/save pair
        val v4 = cmds.first { it.contains("iptables-save -t filter") }
        val v6 = cmds.first { it.contains("ip6tables-save -t filter") }
        assertFalse(v4.contains(CoreConstants.ROOT_OPLUS_RULES_V6_FILE))
        assertFalse(v4.contains(CoreConstants.ROOT_OPLUS_RULES_V6_TMP_FILE))
        assertFalse(v6.contains(CoreConstants.ROOT_OPLUS_RULES_V4_FILE))
        assertFalse(v6.contains(CoreConstants.ROOT_OPLUS_RULES_V4_TMP_FILE))
        // the save step's only chain mutation is the gated flush of the verified
        // branch (asserted above): exactly one flush per family, never in the
        // failure / missing-chain branches
        val joined = cmds.joinToString("\n")
        assertEquals(
            "exactly one gated flush per family",
            2,
            Regex(" -F ").findAll(joined).count()
        )
        assertFalse("save must not create or delete the chain", joined.contains(" -N ") || joined.contains(" -X "))
    }

    // ---------- restore (F09 + H2) ----------

    @Test
    fun `restore replays the snapshot through iptables-restore noflush and keeps the file on failure`() {
        val cmds = RootShellManager.buildRestoreOplusCommands()
        assertEquals("one restore command per family", 2, cmds.size)

        for ((tool, _, file) in families) {
            val cmd = cmds.first { it.startsWith("if [ -s $file ]") }
            // H2: restore only runs for a snapshot that exists, is non-empty AND
            // contains the chain's own -A rule lines — an empty or corrupted
            // snapshot is garbage and is removed instead of being fed to
            // iptables-restore
            assertTrue(
                "restore must gate on a non-empty save file with -A lines, got: $cmd",
                cmd.startsWith("if [ -s $file ] && grep -qE '^-A $chain ' $file 2>/dev/null; then $tool -t filter -N $chain 2>/dev/null || true; ")
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
            // F09/N04: the snapshot is consumed ONLY after a successful restore; a
            // failure keeps the file for the next attempt / manual recovery and logs
            assertTrue(
                "a failed restore must keep the snapshot and log it, got: $cmd",
                cmd.endsWith("else echo 'mihomet: OEM chain restore failed; snapshot kept in $file' >&2; fi; else rm -f $file; fi")
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

    // ---------- save-before-flush ordering (startDaemon step 1, H2) ----------

    @Test
    fun `flush commands restore stale snapshots first, then save with the flush gated on verification`() {
        val cmds = RootShellManager.buildOplusFlushCommands()
        val restore = RootShellManager.buildRestoreOplusCommands()
        val save = RootShellManager.buildSaveOplusCommands()

        // H2: the snapshot left over from a crashed session is replayed BEFORE
        // the fresh save — a new snapshot must never overwrite the only
        // surviving copy of the OEM rules (audit N04 defect 3)
        assertEquals("the flush block must start with the restore commands", restore, cmds.take(restore.size))
        assertEquals("the fresh save must follow the restore", save, cmds.drop(restore.size).take(save.size))

        // H2: the flush of each family happens ONLY inside the verified branch
        // of that family's own save command (the shell "snapshotOk" flag) — a
        // family whose snapshot failed or is stale is never flushed, so its OEM
        // rules are preserved
        for ((tool, _, file) in families) {
            val cmd = cmds.first { it.contains(" $tool -t filter -F ") }
            assertTrue(
                "flush of $tool must be gated on the verified snapshot published to $file",
                cmd.contains("if [ -s ${tmpFor(file)} ] && grep -qE '^-A $chain ' ${tmpFor(file)} 2>/dev/null; then " +
                        "mv -f ${tmpFor(file)} $file; $tool -t filter -F $chain 2>/dev/null || true; else ")
            )
        }

        // ordering: restore -> save (with embedded gated flush). The F09
        // "snapshot before flush" guarantee now lives INSIDE each save command:
        // the flush is preceded, in the same verified branch, by the staged
        // dump and the atomic publish
        val firstSave = cmds.indexOfFirst { it.contains("-save -t filter") }
        val lastRestore = cmds.indexOfLast { it.contains("-restore --noflush") }
        assertTrue("the snapshot must be part of the flush step", firstSave >= 0)
        assertTrue("restore must precede the fresh save", lastRestore < firstSave)
        for (cmd in cmds) {
            val flushIdx = cmd.indexOf(" -F $chain ")
            if (flushIdx >= 0) {
                val dumpIdx = cmd.indexOf("-save -t filter")
                val mvIdx = cmd.indexOf("mv -f")
                assertTrue(
                    "flush must run only after its own dump and atomic publish, got: $cmd",
                    dumpIdx in 0 until mvIdx && mvIdx in 0 until flushIdx
                )
            }
        }

        // no cross-family leakage in the gated flushes: a family flushes only
        // when its own file was verified
        val v4Cmd = cmds.first { it.contains("iptables-save -t filter") }
        val v6Cmd = cmds.first { it.contains("ip6tables-save -t filter") }
        assertFalse(v4Cmd.contains(CoreConstants.ROOT_OPLUS_RULES_V6_FILE))
        assertFalse(v4Cmd.contains(CoreConstants.ROOT_OPLUS_RULES_V6_TMP_FILE))
        assertFalse(v6Cmd.contains(CoreConstants.ROOT_OPLUS_RULES_V4_FILE))
        assertFalse(v6Cmd.contains(CoreConstants.ROOT_OPLUS_RULES_V4_TMP_FILE))
    }

    // ---------- stop wiring ----------

    @Test
    fun `stop commands restore the oplus chains after the private dns block`() {
        val cmds = RootShellManager.buildStopDaemonCommands()

        val dnsIdx = cmds.indexOfFirst { "private_dns_mode" in it }
        val restoreIdx = cmds.indexOfFirst {
            it.startsWith("if [ -s ${CoreConstants.ROOT_OPLUS_RULES_V4_FILE} ]")
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
