package com.clockin.apptester

import android.app.KeyguardManager
import android.content.Context

/**
 * Packages the tester refuses to read, record or click inside.
 *
 * This is not a security boundary. It is a fixed list of known wallets and system
 * credential surfaces on this device, and an unlisted app is still fully visible to the
 * accessibility service. Treat it as a guard rail, not a guarantee.
 *
 * Strict version (see CLAUDE.md status): events from these packages are dropped before
 * any node is read, and replay skips any step whose window belongs to one. That
 * deliberately also makes MWA screen recognition (must-have #3) impossible for now, since
 * recognition requires reading inside the wallet - see the status entry for the deferred
 * transient read-only variant that would allow both.
 *
 * com.android.systemui is deliberately NOT listed: it owns the notification shade, quick
 * settings and the recents screen, and blocking all of it would make the tester go blind
 * (and stop recordings) every time one of those is pulled down. The lock screen is covered
 * separately through KeyguardManager, not by package name.
 */
object PackageGuard {
    private val PROTECTED = setOf(
        "com.solanamobile.wallet",
        "com.solanamobile.seedvaultimpl",
        "app.phantom",
        "ag.jup.jupiter.android",
        "com.debank.rabbymobile",
        "com.coinbase.android",
        "org.toshi",
        "com.solflare.mobile",
        "com.android.keyguard",
        "com.android.credentialmanager",
        "com.google.android.gms"
    )

    fun isProtected(packageName: String?): Boolean = packageName != null && packageName in PROTECTED

    /** True while the lock screen is up or the device is locked. Asked of the system
     *  directly rather than inferred from a package name, since the keyguard UI is drawn
     *  by systemui on most devices and systemui itself is not on the protected list. */
    fun isKeyguardShowing(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
}
