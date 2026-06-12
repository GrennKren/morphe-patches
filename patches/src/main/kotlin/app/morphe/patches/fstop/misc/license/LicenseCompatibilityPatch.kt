/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.license

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP

/**
 * Patch to fix premium detection for legitimate F-Stop license holders.
 *
 * PROBLEM:
 * F-Stop's premium check method s3.a.d() uses three OR conditions:
 *   1. checkSignatures("com.fstop.photo", "com.fstop.photo.key") == 0
 *   2. b0.f8749z (isIAPPurchased flag from SharedPreferences)
 *   3. f41680a.size() > 0 (purchasedInAppPurchasesAndSubscriptions set)
 *
 * The first condition (checkSignatures) ALWAYS fails for patched APKs because
 * they are re-signed with a different certificate than the original. This means
 * even users who have legitimately purchased the premium key app will not have
 * their premium status detected.
 *
 * The second and third conditions also fail for fresh installs under a new
 * package name (side-by-side) because SharedPreferences are per-package.
 *
 * SOLUTION:
 * Inject a key app presence check at the beginning of s3.a.d(). Before the
 * original check logic runs, we call LicenseHelper.isKeyAppInstalled() which
 * uses getPackageInfo("com.fstop.photo.key") to verify the key app exists.
 * If found, d() returns true immediately, allowing legitimate license holders
 * to access premium features without needing the "Unlock premium" patch.
 *
 * This patch is enabled by default because it is a compatibility fix — not a
 * bypass. Users who have purchased the premium key app should have their
 * license detected regardless of whether the APK has been patched.
 *
 * ARCHITECTURE:
 * - License compatibility (this patch, always on): Fixes detection for
 *   legitimate key app holders. Key app must be installed.
 * - Unlock premium (separate patch, disabled by default): Bypasses ALL
 *   license checks by making d() and e() always return true. For users
 *   who have NOT purchased the premium key app.
 */
@Suppress("unused")
val licenseCompatibilityPatch = bytecodePatch(
    name = "License compatibility",
    description = "Fixes premium detection for users who have purchased the " +
        "F-Stop key app. The patched APK has a different signing certificate, " +
        "causing the original checkSignatures() verification to fail. This " +
        "patch adds an alternative check using getPackageInfo() so that " +
        "legitimate license holders are correctly detected as premium without " +
        "needing the 'Unlock premium' bypass patch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    extendWith("extensions/fstop.mpe")

    execute {
        val EXTENSION_CLASS = "Lapp/morphe/extension/fstop/LicenseHelper;"

        // ============================================================
        // Inject key app check at the beginning of s3.a.d()
        // ============================================================
        // Original d() flow:
        //   1. if (!initialized) c();
        //   2. return (checkSignatures(...) == 0) | (isIAPPurchased) | (purchaseSet.size > 0)
        //
        // Patched d() flow:
        //   1. if (isKeyAppInstalled()) return true;  <-- NEW
        //   2. if (!initialized) c();
        //   3. return (checkSignatures(...) == 0) | (isIAPPurchased) | (purchaseSet.size > 0)
        //
        // This way, if the user has com.fstop.photo.key installed,
        // d() returns true immediately without ever reaching the
        // checkSignatures() call that would fail for patched APKs.

        PremiumCheckFingerprint.method.apply {
            val impl = implementation!!

            addInstructions(
                0,
                """
                    # License compatibility: check if key app is installed
                    # Uses ActivityThread internally — no dependency on obfuscated APK fields
                    invoke-static {}, $EXTENSION_CLASS->isKeyAppInstalled()Z
                    move-result v0
                    if-eqz v0, :cond_license_fallback
                    const/4 v0, 0x1
                    return v0
                    :cond_license_fallback
                """,
            )
        }
    }
}
