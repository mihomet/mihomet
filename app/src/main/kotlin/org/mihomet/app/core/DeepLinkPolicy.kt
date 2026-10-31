package org.mihomet.app.core

/**
 * Pure accept/reject decisions for external deep links (audit P0 §1.1).
 *
 * Two link families reach [org.mihomet.app.ui.MainActivity]:
 *
 *  - **Import links** (`happ://add/…`, `clash://install-config?url=…`,
 *    `incy://add|import|onadd…`, share-target text, …): NEVER imported
 *    silently. [requiresImportConfirmation] is `true` for EVERY origin — the
 *    payload goes into a pending state and the UI shows a confirmation dialog
 *    (URL + source + target profile name) before anything is fetched or stored.
 *
 *  - **Control links** (`incy://connect|disconnect|toggle`,
 *    `mihomet://start|stop|toggle`): may only be executed when the intent
 *    provably originates from this app's own widget/tile/notification
 *    PendingIntents. A browser link (CATEGORY_BROWSABLE) or a third-party app
 *    intent is always rejected — the tunnel must not be togglable by a web
 *    page. The manifest keeps import schemes BROWSABLE and moves the
 *    `mihomet://` scheme to a separate non-BROWSABLE activity-alias; this
 *    runtime check is the second layer of that split.
 *
 * Origin detection is best-effort (Android does not authenticate startActivity
 * callers): `callingPackage` + `referrer` (`android-app://<self>`) identify our
 * own PendingIntents, the BROWSABLE category identifies browser dispatch. When
 * in doubt the origin is treated as external — fail-closed.
 */
object DeepLinkPolicy {

    /** Best-effort classification of where an incoming intent came from. */
    enum class IntentOrigin {
        /** Dispatched through a browser link (CATEGORY_BROWSABLE present). */
        BROWSABLE,

        /** Another app started the activity (no BROWSABLE category, not us). */
        EXTERNAL_APP,

        /** This app itself: widget/tile/notification PendingIntents, internal links. */
        SELF
    }

    enum class ControlDecision {
        /** Execute the control action (start/stop/toggle). */
        ALLOW,

        /** Refuse — log only, never execute. */
        REJECT
    }

    /** Control verbs handled on the incy/mihomet URI space (CMFA ExternalControl parity). */
    val CONTROL_VERBS = setOf(
        "connect", "start", "open",
        "disconnect", "stop", "close",
        "toggle",
        "status"
    )

    fun isControlVerb(action: String): Boolean = action.trim().lowercase() in CONTROL_VERBS

    /**
     * Resolves the origin from already-extracted intent attributes (kept pure for
     * JVM tests). [referrer] is `Activity.getReferrer()` (`android-app://<pkg>`
     * for cross-app launches, the page URL for browsers); [callingPackage] is
     * non-null only for startActivityForResult-style launches.
     */
    fun resolveOrigin(
        browsable: Boolean,
        selfPackage: String,
        referrer: String?,
        callingPackage: String?
    ): IntentOrigin {
        if (selfPackage.isNotEmpty()) {
            if (callingPackage == selfPackage) return IntentOrigin.SELF
            if (referrer == "android-app://$selfPackage") return IntentOrigin.SELF
        }
        return if (browsable) IntentOrigin.BROWSABLE else IntentOrigin.EXTERNAL_APP
    }

    /**
     * Control-link gate: only SELF-originated intents may start/stop/toggle the
     * tunnel. Browser links and third-party intents are always rejected.
     */
    fun decideControl(origin: IntentOrigin): ControlDecision =
        if (origin == IntentOrigin.SELF) ControlDecision.ALLOW else ControlDecision.REJECT

    /**
     * Import gate: EVERY import — browser, third-party app, share-target, even
     * self-originated links — requires the explicit confirmation dialog state.
     * There is no silent-import path.
     */
    fun requiresImportConfirmation(origin: IntentOrigin): Boolean = true
}
