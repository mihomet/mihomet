package org.mihomet.app.data.update

/**
 * Pure semantic-version comparison for the in-app self-update check (audit P1.4).
 *
 * Tolerates decorated inputs: a leading `v`/`V` prefix (GitHub release tags are
 * `vX.Y.Z` while `versionName` is `X.Y.Z`), pre-release suffixes
 * (`1.3.0-rc.1`) and build metadata (`1.3.0+build.5`). Precedence follows
 * semver 2.0.0: numeric major/minor/patch, then a version WITHOUT a pre-release
 * suffix is newer than one with it, with build metadata ignored.
 *
 * Fail-closed: anything that does not start with a full `major.minor.patch`
 * triple parses to `null` and can never trigger an update.
 */
object AppVersion {

    /** Parsed semantic version: numeric triple + optional pre-release part. */
    data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String? = null
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            val triple = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
            if (triple != 0) return triple

            val a = preRelease
            val b = other.preRelease
            // No pre-release > pre-release (semver 2.0.0 §11.4)
            if (a == null && b == null) return 0
            if (a == null) return 1
            if (b == null) return -1
            return comparePreReleaseIdentifiers(a, b)
        }
    }

    /** Anchored: the whole string must be `[v]X.Y.Z[-pre][+build]` (fail-closed). */
    private val SEMVER = Regex(
        """^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z][0-9A-Za-z.\-]*))?(?:\+([0-9A-Za-z][0-9A-Za-z.\-]*))?$"""
    )

    /**
     * Parses `[v]X.Y.Z[-pre][+build]`; `null` when the string is not a semantic
     * version (empty, decorated like "1.2.3 (Bundled)", four-part, …).
     */
    fun parse(raw: String?): SemVer? {
        if (raw.isNullOrBlank()) return null
        val match = SEMVER.find(raw.trim()) ?: return null
        return SemVer(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            preRelease = match.groupValues[4].takeIf { it.isNotEmpty() }
        )
    }

    /**
     * True only when [releaseTag] parses to a version STRICTLY greater than
     * [currentVersionName]. Unparseable input on either side → false (never
     * offer an update based on string inequality — audit-proof fail-closed).
     */
    fun isUpdateAvailable(currentVersionName: String?, releaseTag: String?): Boolean {
        val current = parse(currentVersionName) ?: return false
        val candidate = parse(releaseTag) ?: return false
        return candidate > current
    }

    /** semver 2.0.0 §11.4 identifier comparison for pre-release parts. */
    private fun comparePreReleaseIdentifiers(a: String, b: String): Int {
        val aParts = a.split('.')
        val bParts = b.split('.')
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            val ap = aParts[i]
            val bp = bParts[i]
            val aNum = ap.toLongOrNull()
            val bNum = bp.toLongOrNull()
            val cmp = when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1   // numeric identifiers always have LOWER precedence
                bNum != null -> 1
                else -> ap.compareTo(bp)
            }
            if (cmp != 0) return cmp
        }
        return aParts.size.compareTo(bParts.size)  // larger set of fields wins when all equal
    }
}
