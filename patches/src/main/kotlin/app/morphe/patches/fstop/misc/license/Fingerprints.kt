package app.morphe.patches.fstop.misc.license

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the ACTUAL premium check method s3.a.d().
 *
 * From APK decompilation:
 * - Method: Ls3/a;->d()Z
 * - PUBLIC STATIC, returns boolean, no parameters
 * - This is THE method that gates ALL premium features (40+ call sites)
 * - Returns true if ANY of:
 *   1. checkSignatures("com.fstop.photo", "com.fstop.photo.key") == 0 (same signing key)
 *   2. b0.f8749z is true (isIAPPurchased from SharedPreferences)
 *   3. f41680a.size() > 0 (purchasedInAppPurchasesAndSubscriptions set)
 *
 * IMPORTANT: N2() and x2() in class p are NOT premium gates — they only
 * control settings UI preferences (show/hide key app icon). The actual
 * premium feature gating is done exclusively through this method.
 *
 * The patch will replace this method body to always return true.
 */
internal object PremiumCheckFingerprint : Fingerprint(
    definingClass = "Ls3/a;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    strings = listOf("com.fstop.photo.key"),
)

/**
 * Fingerprint for the premium check method without signature verification s3.a.e().
 *
 * From APK decompilation:
 * - Method: Ls3/a;->e()Z
 * - PUBLIC STATIC, returns boolean, no parameters
 * - Same as d() but without the signature check
 * - Used by the billing system flow
 * - Returns true if:
 *   1. b0.f8749z is true (isIAPPurchased)
 *   2. f41680a.size() > 0 (purchase set)
 *
 * The patch will replace this method body to always return true.
 */
internal object PremiumCheckNoSigFingerprint : Fingerprint(
    definingClass = "Ls3/a;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
)
