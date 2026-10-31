package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.root.RootShellManager

/**
 * Strict tests for the OEM (OnePlus / Realme / ColorOS) firewall-chain
 * save/restore shell builders in [RootShellManager].
 *
 * Contract (external audit §7.4/§9.2): startDaemon flushes the OEM custom DNS
 * reject chain [CoreConstants.OPLUS_DNS_CHAIN] (`fw_OUTPUT_oplus_dns`, filter
 * table) for the daemon lifetime. Without a snapshot the OEM rules would be
 * lost forever, so:
 *  - the snapshot must be taken BEFORE the flush, gated on the chain's real
 *    existence (an `-nL` exit-code check — never "grep with empty output as
 *    success"), into per-family save files under the root run dir; a missing
 *    chain removes stale save files instead of leaving them for restore;
 *  - the restore must run on stop (appended after the Private DNS restore
 *    block) and from the crash-recovery path, recreate the chain when it
 *    disappeared, replay every saved `-A` rule line through the matching
 *    family binary in the saved order, then delete the save files.
 *
 * Crash semantics: a crashed session leaves flushed (empty) chains plus the
 * save files on disk; the next start re-saves the chains only after
 * stopDaemon() replayed the snapshot, so the OEM rules survive (the explicit
 * safe path is [RootShellManager.maybeRestoreOplusChains]).
 */
class RootShellCommandsStrictTest {

    // ---------- helpers ----------

    private fun allInts(text: String): Set<Int> =
        Regex("\\d+").findAll(text).map { it.value.toInt() }.toSet()

    /** (iptables binary, save file) pairs — one per address family, in builder order. */
    private val families = listOf(
        "iptables" to CoreConstants.ROOT_OPLUS_RULES_V4_FILE,
        "ip6tables" to CoreConstants.ROOT_OPLUS_RULES_V6_FILE
    )

    private val allowedNumbers = setOf(
        0, 1, 2, // shell redirects: 2>/dev/null, 2>&1
        4, 6     // per-family save-file suffixes (.oplus_v4.rules / .oplus_v6.rules)
    )

    // ---------- external OEM contract ----------

    @Test
    fun `oplus chain contract is pinned to the coloros chain name and run dir save files`() {
        // The chain name is an OEM (ColorOS/realme) firewall artifact: renaming it
        // would silently stop touching the real OEM chain.
        assertEquals("fw_OUTPUT_oplus_dns", CoreConstants.OPLUS_DNS_CHAIN)
        for ((_, file) in families) {
            assertTrue(
                "save file $file must live inside the root run dir",
                file.startsWith("${CoreConstants.ROOT_RUN_DIR}/")
            )
        }
        // one file per family, distinct
        assertTrue(CoreConstants.ROOT_OPLUS_RULES_V4_FILE != CoreConstants.ROOT_OPLUS_RULES_V6_FILE)
    }

    // ---------- save ----------

    @Test
    fun `save commands dump both families into their own files only when the chain exists`() {
        val cmds = RootShellManager.buildSaveOplusCommands()
        assertEquals("run-dir mkdir plus one save command per family", 3, cmds.size)
        // the run dir must exist before any save file is written
        assertEquals("mkdir -p ${CoreConstants.ROOT_RUN_DIR}", cmds.first())

        for ((tool, file) in families) {
            val cmd = cmds.first { it.startsWith("if $tool -t filter -nL") }
            // existence gate via the -nL exit code, evaluated BEFORE the dump
            assertTrue(
                "save must gate on chain existence via -nL exit code, got: $cmd",
                cmd.startsWith("if $tool -t filter -nL ${CoreConstants.OPLUS_DNS_CHAIN} >/dev/null 2>&1; then ")
            )
            // dump (-S) filtered to `-A` rule lines, written to the family's save file
            assertTrue(
                "save must write the -S dump of the chain into $file, got: $cmd",
                cmd.contains("$tool -t filter -S ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null | grep '^-A ' > $file")
            )
            // a missing chain removes the stale save file instead of keeping old rules for restore
            assertTrue(
                "a missing chain must remove the stale save file, got: $cmd",
                cmd.endsWith("else rm -f $file; fi")
            )
        }

        // no cross-family leakage: each dump lands in exactly its own file
        val v4 = cmds.first { it.contains("iptables -t filter -S") }
        val v6 = cmds.first { it.contains("ip6tables -t filter -S") }
        assertFalse(v4.contains(CoreConstants.ROOT_OPLUS_RULES_V6_FILE))
        assertFalse(v6.contains(CoreConstants.ROOT_OPLUS_RULES_V4_FILE))
        // the save step must not mutate the chain in any way
        val joined = cmds.joinToString("\n")
        assertFalse("save must not flush the chain", joined.contains(" -F "))
        assertFalse("save must not create or delete the chain", joined.contains(" -N ") || joined.contains(" -X "))
    }

    // ---------- restore ----------

    @Test
    fun `restore recreates a missing chain and replays saved rules line by line`() {
        val cmds = RootShellManager.buildRestoreOplusCommands()
        assertEquals("one restore command per family", 2, cmds.size)

        for ((tool, file) in families) {
            val cmd = cmds.first { it.startsWith("if [ -f $file ]") }
            // restore only runs when a snapshot exists
            assertTrue(
                "restore must be gated on the save file, got: $cmd",
                cmd.startsWith("if [ -f $file ]; then ")
            )
            // recreate the chain when it disappeared, before any rule replay
            assertTrue(
                "restore must create the missing chain via -N, got: $cmd",
                cmd.contains(
                    "if ! $tool -t filter -nL ${CoreConstants.OPLUS_DNS_CHAIN} >/dev/null 2>&1; then " +
                            "$tool -t filter -N ${CoreConstants.OPLUS_DNS_CHAIN} 2>/dev/null || true; fi; "
                )
            )
            // replay every saved -A line, in the saved order, through the family binary
            assertTrue(
                "restore must replay the saved rules line by line, got: $cmd",
                cmd.contains(
                    "grep '^-A ' $file 2>/dev/null | while IFS= read -r line; do " +
                            "$tool -t filter \$line 2>/dev/null || true; done; "
                )
            )
            // the snapshot is consumed after the replay
            assertTrue(
                "restore must delete the save file after the replay, got: $cmd",
                cmd.endsWith("rm -f $file; fi")
            )
        }

        // no cross-family leakage
        val v4 = cmds.first { it.contains("iptables -t filter -N") }
        val v6 = cmds.first { it.contains("ip6tables -t filter -N") }
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
        // flush, otherwise it would capture the already-emptied chains (audit §7.4/§9.2)
        val save = RootShellManager.buildSaveOplusCommands()
        assertEquals(save, cmds.take(save.size))
        val lastSave = cmds.indexOfLast { it.contains(" -S ${CoreConstants.OPLUS_DNS_CHAIN} ") }
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

    // ---------- shell hygiene ----------

    @Test
    fun `oplus builders carry no numeric magic and no destructive surprises`() {
        val all = (
                RootShellManager.buildSaveOplusCommands() +
                        RootShellManager.buildRestoreOplusCommands() +
                        RootShellManager.buildOplusFlushCommands()
                ).joinToString("\n")

        val foreign = allInts(all).filter { it !in allowedNumbers }
        assertTrue("oplus commands must not contain numeric magic, found: $foreign", foreign.isEmpty())

        // best-effort teardown style only: no broad pattern kills, no rm -rf
        assertFalse(all.contains("pkill"))
        assertFalse(all.contains("rm -rf"))
    }
}
