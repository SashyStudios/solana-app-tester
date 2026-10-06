package com.clockin.apptester

/**
 * Packages the tester refuses to read, record or click inside.
 *
 * This is not a security boundary. It is a fixed list of the known-worst cases on this
 * device - wallets and system credential surfaces - and an unlisted app is still fully
 * visible to the accessibility service. Treat it as a guard rail, not a guarantee.
 *
 * Strict version (see CLAUDE.md status): events from these packages are dropped before
 * anything is read or logged, and replay skips any step whose window belongs to one. That
 * deliberately also makes MWA screen recognition (must-have #3) impossible for now, since
 * recognition requires reading inside the wallet - see the status entry for the deferred
 * transient read-only variant that would allow both.
 */
object PackageGuard {
    private val PROTECTED = setOf(
        "com.solanamobile.wallet",
        "com.solanamobile.seedvaultimpl",
        "app.phantom",
        "ag.jup.jupiter.android",
        "com.android.systemui",
        "com.android.keyguard",
        "com.android.credentialmanager",
        "com.google.android.gms"
    )

    fun isProtected(packageName: String?): Boolean = packageName != null && packageName in PROTECTED
}
