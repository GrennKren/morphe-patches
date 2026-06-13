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
 *   2. b0.z (isIAPPurchased flag from SharedPreferences)
 *   3. a.size() > 0 (purchasedInAppPurchasesAndSubscriptions set)
 *
 * The first condition (checkSignatures) ALWAYS fails for patched APKs because
 * they are re-signed with a different certificate than the original. This means
 * even users who have legitimately purchased the premium key app will not have
 * their premium status detected.
 *
 * SOLUTION:
 * Inject a key app presence check at the beginning of s3.a.d(). Before the
 * original check logic runs, we call LicenseHelper.isKeyAppInstalled(b0.r)
 * which uses getPackageInfo("com.fstop.photo.key") to verify the key app exists.
 * If found, d() returns true immediately, allowing legitimate license holders
 * to access premium features without needing the "Unlock premium" patch.
 *
 * CONTEXT ACQUISITION:
 * We use b0.r (the static Application Context) to pass to isKeyAppInstalled().
 * This is the SAME Context that s3.a.d() itself uses at instruction index #3:
 *   sget-object v0, Lcom/fstop/photo/b0;->r:Landroid/content/Context;
 *   invoke-virtual v0, Landroid/content/Context;->getPackageManager()...
 * Since d() already uses b0.r before our injected code runs, it is guaranteed
 * to be initialized.
 *
 * REGISTER SAFETY:
 * The original d() method uses registers v0-v3. Our injected code at index 0
 * uses v0 for the Context and result, which is safe because we return before
 * the original code executes if the key app is found.
 *
 * This patch is enabled by default because it is a compatibility fix — not a
 * bypass. Users who have purchased the premium key app should have their
 * license detected regardless of whether the APK has been patched.
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
        // Original d() bytecode (from androguard analysis):
        //   [0] sget-boolean v0, Ls3/a;->b Z  (initialized flag)
        //   [1] if-nez v0, +5h
        //   [2] invoke-static Ls3/a;->c()V    (initialize if needed)
        //   [3] sget-object v0, Lcom/fstop/photo/b0;->r Landroid/content/Context;
        //   [4] invoke-virtual v0, getPackageManager()
        //   ...
        //   [8] invoke-virtual v0, v1, v2, checkSignatures()
        //   ...
        //   [28] return v0
        //
        // Patched d() flow:
        //   0. sget-object v0, b0.r (Context)       <-- NEW: get Context
        //   1. invoke-static {v0}, isKeyAppInstalled  <-- NEW: check key app
        //   2. move-result v0                        <-- NEW: get result
        //   3. if-eqz v0, :cond_license_fallback     <-- NEW: skip if not found
        //   4. const/4 v0, 0x1                       <-- NEW: true
        //   5. return v0                              <-- NEW: return true
        //   6. :cond_license_fallback                 <-- NEW: label
        //   7. (original d() code continues here)

        PremiumCheckFingerprint.method.apply {
            addInstructions(
                0,
                """
                    # License compatibility: check if key app is installed
                    # Use b0.r — the same Context that d() itself uses at index #3
                    sget-object v0, Lcom/fstop/photo/b0;->r:Landroid/content/Context;
                    invoke-static {v0}, $EXTENSION_CLASS->isKeyAppInstalled(Landroid/content/Context;)Z
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
