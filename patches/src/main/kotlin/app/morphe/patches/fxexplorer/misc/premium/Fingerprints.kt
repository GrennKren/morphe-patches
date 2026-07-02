/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.premium

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the license verification method (lh/n.l(Context)Z).
 *
 * Same target as LicenseCompatibilityPatch's fingerprint, but declared
 * separately in this package to keep the patches independent. Both
 * patches hook the same method but at different injection points:
 * - LicenseCompatibility: injects AFTER the second getPackageInfo (mid-method)
 * - UnlockPremium: injects at method entry (returns immediately)
 *
 * If both patches are enabled, UnlockPremium's early return takes
 * precedence — the method never reaches the signature comparison that
 * LicenseCompatibility patches.
 *
 * Key identifiers:
 * - Public static method returning boolean with Context parameter
 * - References "nextapp.fx.rk" string constant
 */
internal object LicenseCheckFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("nextapp.fx.rk"),
)
