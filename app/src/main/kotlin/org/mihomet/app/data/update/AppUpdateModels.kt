package org.mihomet.app.data.update

/**
 * User-visible state machine of the in-app self-update flow (audit P1.4).
 *
 * Core path: idle → checking → upToDate | updateAvailable(version) →
 * downloading(progress) → readyToInstall → (system install dialog).
 * Additional fail-closed states: [AppUpdateState.UpdateAvailableManual] (a newer
 * release exists but the verification chain cannot anchor an in-app install —
 * only the manual release-page route is offered) and
 * [AppUpdateState.NeedInstallPermission] (Android 8–12 "install unknown apps"
 * gate — the user is redirected to the system settings, never bypassed).
 */
sealed interface AppUpdateState {
    /** Nothing has happened yet in this app run. */
    data object Idle : AppUpdateState

    /** GitHub releases/latest is being queried. */
    data object Checking : AppUpdateState

    /** Latest release ≤ installed version (or unreadable — treated as no update). */
    data class UpToDate(val currentVersion: String) : AppUpdateState

    /** Newer release with a fully verified chain (version-info + APK asset). */
    data class UpdateAvailable(val version: String) : AppUpdateState

    /** Newer release, but install requires the manual release-page route. */
    data class UpdateAvailableManual(val version: String) : AppUpdateState

    /** APK download in progress, [progressPercent] in 0..100. */
    data class Downloading(val version: String, val progressPercent: Int) : AppUpdateState

    /** APK downloaded, size + signing-certificate verified, ready for PackageInstaller. */
    data class ReadyToInstall(val version: String, val apkPath: String) : AppUpdateState

    /** PackageInstaller refused to start: the "install unknown apps" gate is closed. */
    data class NeedInstallPermission(val version: String) : AppUpdateState

    /** Session committed; the user must finish in the system install dialog. */
    data class InstallRequested(val version: String) : AppUpdateState

    /** Check/download/install refused (verification failure, network error, …). */
    data class Error(val message: String) : AppUpdateState
}

sealed interface AppUpdateEvent {
    /** User tapped "Check for updates". */
    data object CheckStarted : AppUpdateEvent

    /** Check finished: installed version is the latest. */
    data class CheckUpToDate(val currentVersion: String) : AppUpdateEvent

    /** Check finished: a newer release exists ([manualOnly] = verification chain incomplete). */
    data class CheckUpdateAvailable(val version: String, val manualOnly: Boolean) : AppUpdateEvent

    /** Check failed (network / API error). */
    data class CheckFailed(val reason: String) : AppUpdateEvent

    /** Verified APK download begins. */
    data class DownloadStarted(val version: String) : AppUpdateEvent

    /** Streamed byte counter crossed a new whole percent. */
    data class DownloadProgress(val percent: Int) : AppUpdateEvent

    /** Download finished and passed size + signing-certificate verification. */
    data class DownloadVerified(val version: String, val apkPath: String) : AppUpdateEvent

    /** Download failed verification — nothing is kept. */
    data class DownloadFailed(val reason: String) : AppUpdateEvent

    /** Install attempt blocked: the user must grant "install unknown apps" first. */
    data class InstallPermissionNeeded(val version: String) : AppUpdateEvent

    /** The user returned after granting "install unknown apps" — back to readyToInstall. */
    data class InstallPermissionGranted(val version: String, val apkPath: String) : AppUpdateEvent

    /** PackageInstaller session committed; waiting for the user/system dialog. */
    data object InstallCommitStarted : AppUpdateEvent

    /** Session failed before or during the system dialog. */
    data class InstallCommitFailed(val reason: String) : AppUpdateEvent

    /** System reported a finished installation. */
    data object InstallConfirmed : AppUpdateEvent

    /** Back to idle (used when a stale/foreign state must be cleared). */
    data object Reset : AppUpdateEvent
}

/**
 * Pure reducer of the update state machine. Stale or impossible events are
 * IGNORED (the current state is returned unchanged) so late callbacks after a
 * state change can never corrupt the flow — the manager is the only mutator
 * and feeds every callback through here.
 */
object AppUpdateStateMachine {

    fun reduce(state: AppUpdateState, event: AppUpdateEvent): AppUpdateState {
        return when (event) {
            AppUpdateEvent.CheckStarted -> when (state) {
                is AppUpdateState.Downloading -> state            // never interrupt an active download
                else -> AppUpdateState.Checking
            }

            is AppUpdateEvent.CheckUpToDate -> if (state is AppUpdateState.Checking) {
                AppUpdateState.UpToDate(event.currentVersion)
            } else state

            is AppUpdateEvent.CheckUpdateAvailable -> if (state is AppUpdateState.Checking) {
                if (event.manualOnly) {
                    AppUpdateState.UpdateAvailableManual(event.version)
                } else {
                    AppUpdateState.UpdateAvailable(event.version)
                }
            } else state

            is AppUpdateEvent.CheckFailed -> if (state is AppUpdateState.Checking) {
                AppUpdateState.Error(event.reason)
            } else state

            is AppUpdateEvent.DownloadStarted -> if (state is AppUpdateState.UpdateAvailable &&
                event.version == state.version
            ) {
                AppUpdateState.Downloading(event.version, 0)
            } else state

            is AppUpdateEvent.DownloadProgress -> if (state is AppUpdateState.Downloading) {
                state.copy(progressPercent = event.percent.coerceIn(0, 100))
            } else state

            is AppUpdateEvent.DownloadVerified -> if (state is AppUpdateState.Downloading || state is AppUpdateState.Checking) {
                // Checking: a previously downloaded and re-verified APK was found in
                // the cache during the check (resume path) — skip straight to install.
                AppUpdateState.ReadyToInstall(event.version, event.apkPath)
            } else state

            is AppUpdateEvent.DownloadFailed -> if (state is AppUpdateState.Downloading) {
                AppUpdateState.Error(event.reason)
            } else state

            is AppUpdateEvent.InstallPermissionNeeded -> if (state is AppUpdateState.ReadyToInstall) {
                AppUpdateState.NeedInstallPermission(event.version)
            } else state

            is AppUpdateEvent.InstallPermissionGranted -> if (state is AppUpdateState.NeedInstallPermission) {
                AppUpdateState.ReadyToInstall(event.version, event.apkPath)
            } else state

            AppUpdateEvent.InstallCommitStarted -> if (state is AppUpdateState.ReadyToInstall) {
                AppUpdateState.InstallRequested(state.version)
            } else state

            is AppUpdateEvent.InstallCommitFailed -> if (state is AppUpdateState.InstallRequested) {
                AppUpdateState.Error(event.reason)
            } else state

            AppUpdateEvent.InstallConfirmed -> if (state is AppUpdateState.InstallRequested) {
                AppUpdateState.UpToDate(currentVersion = state.version)
            } else state

            AppUpdateEvent.Reset -> AppUpdateState.Idle
        }
    }
}
