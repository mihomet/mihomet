package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mihomet.app.data.update.AppUpdateEvent
import org.mihomet.app.data.update.AppUpdateState
import org.mihomet.app.data.update.AppUpdateStateMachine

/**
 * Transition tests for the in-app update state machine (audit P1.4).
 * The happy path must be idle → checking → updateAvailable → downloading →
 * readyToInstall → installRequested; every stale/impossible event is ignored
 * (reducer returns the current state unchanged).
 */
class AppUpdateStateMachineTest {

    private fun reduce(state: AppUpdateState, event: AppUpdateEvent): AppUpdateState =
        AppUpdateStateMachine.reduce(state, event)

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    fun `happy path idle to readyToInstall`() {
        var s: AppUpdateState = AppUpdateState.Idle

        s = reduce(s, AppUpdateEvent.CheckStarted)
        assertEquals(AppUpdateState.Checking, s)

        s = reduce(s, AppUpdateEvent.CheckUpdateAvailable("1.2.2", manualOnly = false))
        assertEquals(AppUpdateState.UpdateAvailable("1.2.2"), s)

        s = reduce(s, AppUpdateEvent.DownloadStarted("1.2.2"))
        assertEquals(AppUpdateState.Downloading("1.2.2", 0), s)

        s = reduce(s, AppUpdateEvent.DownloadProgress(45))
        assertEquals(AppUpdateState.Downloading("1.2.2", 45), s)

        s = reduce(s, AppUpdateEvent.DownloadVerified("1.2.2", "/cache/apk"))
        assertEquals(AppUpdateState.ReadyToInstall("1.2.2", "/cache/apk"), s)

        s = reduce(s, AppUpdateEvent.InstallCommitStarted)
        assertEquals(AppUpdateState.InstallRequested("1.2.2"), s)
    }

    @Test
    fun `up to date and manual-only check outcomes`() {
        var s: AppUpdateState = reduce(AppUpdateState.Idle, AppUpdateEvent.CheckStarted)
        s = reduce(s, AppUpdateEvent.CheckUpToDate("1.2.1"))
        assertEquals(AppUpdateState.UpToDate("1.2.1"), s)

        var m: AppUpdateState = reduce(AppUpdateState.Idle, AppUpdateEvent.CheckStarted)
        m = reduce(m, AppUpdateEvent.CheckUpdateAvailable("1.2.2", manualOnly = true))
        assertEquals(AppUpdateState.UpdateAvailableManual("1.2.2"), m)
    }

    // ------------------------------------------------------------------
    // Failure branches
    // ------------------------------------------------------------------

    @Test
    fun `check failure lands in error and can recover via a new check`() {
        var s: AppUpdateState = reduce(AppUpdateState.Idle, AppUpdateEvent.CheckStarted)
        s = reduce(s, AppUpdateEvent.CheckFailed("network unreachable"))
        assertEquals(AppUpdateState.Error("network unreachable"), s)

        s = reduce(s, AppUpdateEvent.CheckStarted)
        assertEquals(AppUpdateState.Checking, s)
        s = reduce(s, AppUpdateEvent.CheckUpToDate("1.2.1"))
        assertEquals(AppUpdateState.UpToDate("1.2.1"), s)
    }

    @Test
    fun `download failure lands in error and a new check recovers`() {
        var s: AppUpdateState = reduce(AppUpdateState.UpdateAvailable("1.2.2"), AppUpdateEvent.DownloadStarted("1.2.2"))
        s = reduce(s, AppUpdateEvent.DownloadFailed("sha256 mismatch"))
        assertEquals(AppUpdateState.Error("sha256 mismatch"), s)
        assertEquals(AppUpdateState.Checking, reduce(s, AppUpdateEvent.CheckStarted))
    }

    @Test
    fun `install commit failure lands in error`() {
        var s: AppUpdateState = reduce(AppUpdateState.ReadyToInstall("1.2.2", "/p"), AppUpdateEvent.InstallCommitStarted)
        s = reduce(s, AppUpdateEvent.InstallCommitFailed("session abandoned"))
        assertEquals(AppUpdateState.Error("session abandoned"), s)
    }

    @Test
    fun `install confirmation returns to upToDate with the new version`() {
        val s = reduce(AppUpdateState.InstallRequested("1.2.2"), AppUpdateEvent.InstallConfirmed)
        assertEquals(AppUpdateState.UpToDate("1.2.2"), s)
    }

    // ------------------------------------------------------------------
    // Permission gate
    // ------------------------------------------------------------------

    @Test
    fun `install permission gate round-trips back to readyToInstall`() {
        var s: AppUpdateState = reduce(AppUpdateState.ReadyToInstall("1.2.2", "/p"), AppUpdateEvent.InstallPermissionNeeded("1.2.2"))
        assertEquals(AppUpdateState.NeedInstallPermission("1.2.2"), s)
        // after the user grants the permission the manager re-dispatches the grant
        s = reduce(s, AppUpdateEvent.InstallPermissionGranted("1.2.2", "/p"))
        assertEquals(AppUpdateState.ReadyToInstall("1.2.2", "/p"), s)
    }

    // ------------------------------------------------------------------
    // Guards: stale and impossible events are ignored
    // ------------------------------------------------------------------

    @Test
    fun `check cannot interrupt an active download`() {
        val downloading = AppUpdateState.Downloading("1.2.2", 30)
        assertEquals(downloading, reduce(downloading, AppUpdateEvent.CheckStarted))
        assertEquals(downloading, reduce(downloading, AppUpdateEvent.DownloadStarted("1.2.2")))
    }

    @Test
    fun `check results are only accepted while checking`() {
        val idle = AppUpdateState.Idle
        assertEquals(idle, reduce(idle, AppUpdateEvent.CheckUpToDate("1.2.1")))
        assertEquals(idle, reduce(idle, AppUpdateEvent.CheckUpdateAvailable("1.2.2", manualOnly = false)))
        assertEquals(idle, reduce(idle, AppUpdateEvent.CheckFailed("late failure")))
    }

    @Test
    fun `download progress only applies while downloading and is coerced`() {
        val available = AppUpdateState.UpdateAvailable("1.2.2")
        assertEquals(available, reduce(available, AppUpdateEvent.DownloadProgress(50)))

        val downloading = AppUpdateState.Downloading("1.2.2", 50)
        assertEquals(AppUpdateState.Downloading("1.2.2", 100), reduce(downloading, AppUpdateEvent.DownloadProgress(150)))
        assertEquals(AppUpdateState.Downloading("1.2.2", 0), reduce(downloading, AppUpdateEvent.DownloadProgress(-5)))
    }

    @Test
    fun `download events only apply while downloading`() {
        val checking = AppUpdateState.Checking
        assertEquals(checking, reduce(checking, AppUpdateEvent.DownloadFailed("late")))
    }

    @Test
    fun `a cached verified apk found during the check resumes to readyToInstall`() {
        val s = reduce(AppUpdateState.Checking, AppUpdateEvent.DownloadVerified("1.2.2", "/cache/apk"))
        assertEquals(AppUpdateState.ReadyToInstall("1.2.2", "/cache/apk"), s)
    }

    @Test
    fun `download only starts from the matching updateAvailable version`() {
        val available = AppUpdateState.UpdateAvailable("1.2.2")
        // wrong version guard
        assertEquals(available, reduce(available, AppUpdateEvent.DownloadStarted("9.9.9")))
        // manual-only route never downloads
        val manual = AppUpdateState.UpdateAvailableManual("1.2.2")
        assertEquals(manual, reduce(manual, AppUpdateEvent.DownloadStarted("1.2.2")))
        // install permission events do not fire outside readyToInstall
        assertEquals(available, reduce(available, AppUpdateEvent.InstallPermissionNeeded("1.2.2")))
    }

    @Test
    fun `install events only apply from readyToInstall or installRequested`() {
        val ready = AppUpdateState.ReadyToInstall("1.2.2", "/p")
        assertEquals(ready, reduce(ready, AppUpdateEvent.InstallConfirmed))
        assertEquals(ready, reduce(ready, AppUpdateEvent.InstallCommitFailed("late")))

        val requested = AppUpdateState.InstallRequested("1.2.2")
        assertEquals(requested, reduce(requested, AppUpdateEvent.InstallCommitStarted))
    }

    @Test
    fun `reset returns to idle from any state`() {
        listOf(
            AppUpdateState.Idle,
            AppUpdateState.Checking,
            AppUpdateState.UpToDate("1.2.1"),
            AppUpdateState.UpdateAvailable("1.2.2"),
            AppUpdateState.UpdateAvailableManual("1.2.2"),
            AppUpdateState.Downloading("1.2.2", 10),
            AppUpdateState.ReadyToInstall("1.2.2", "/p"),
            AppUpdateState.NeedInstallPermission("1.2.2"),
            AppUpdateState.InstallRequested("1.2.2"),
            AppUpdateState.Error("boom")
        ).forEach { state ->
            assertEquals(AppUpdateState.Idle, reduce(state, AppUpdateEvent.Reset))
        }
    }
}
