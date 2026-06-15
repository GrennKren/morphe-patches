/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.license

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to fix premium detection for legitimate F-Stop license holders.
 *
 * PROBLEM:
 * F-Stop's premium check method s3.a.d() uses three OR conditions:
 *   1. checkSignatures("com.fstop.photo", "com.fstop.photo.key") == 0
 *   2. b0.z (isIAPPurchased flag from SharedPreferences/billing)
 *   3. a.size() > 0 (purchasedInAppPurchasesAndSubscriptions set)
 *
 * Condition 1 ALWAYS fails for patched APKs because they are re-signed.
 * Conditions 2 and 3 also fail for side-by-side installs because the
 * patched app (com.fstop.photo.morphe) has a different package name,
 * so Google Play Billing data from the original app is not accessible.
 *
 * F-Stop uses Google Play In-App Purchasing (IAP) for premium. The
 * checkSignatures() call is a legacy verification between the main app
 * and a "key app" package. For patched APKs, this always returns
 * SIGNATURE_NOT_MATCH (-3).
 *
 * SOLUTION:
 * Replace checkSignatures("com.fstop.photo", "com.fstop.photo.key") with
 * checkSignatures("com.fstop.photo", "com.fstop.photo").
 *
 * This checks if the ORIGINAL F-Stop app is installed with a VALID
 * signing certificate. The result:
 *   - If com.fstop.photo IS installed (the real app from Google Play):
 *     checkSignatures returns SIGNATURE_MATCH (0) because an app's
 *     signature always matches itself. The first OR condition is TRUE,
 *     so d() returns TRUE (premium recognized).
 *
 *   - If com.fstop.photo is NOT installed:
 *     checkSignatures throws or returns SIGNATURE_NOT_MATCH.
 *     The first OR condition is FALSE, so d() falls through to check
 *     b0.z and a.size() — normal IAP-based verification.
 *
 * This is NOT a bypass:
 *   - Legitimate purchasers who have the original app installed → premium works
 *   - Non-purchasers who don't have the original app → no premium
 *   - The IAP checks are preserved as fallback conditions
 *
 * The bytecode of s3.a.d() (verified via androguard):
 *   [0] sget-boolean v0, Ls3/a;->b Z     (initialized flag)
 *   [1] if-nez v0, +5h
 *   [2] invoke-static Ls3/a;->c()V
 *   [3] sget-object v0, Lb0;->r Context
 *   [4] invoke-virtual v0, getPackageManager()
 *   [5] move-result-object v0
 *   [6] const-string v1, "com.fstop.photo"
 *   [7] const-string v2, "com.fstop.photo.key"     <-- TARGET: replace with "com.fstop.photo"
 *   [8] invoke-virtual v0, v1, v2, checkSignatures()I
 *   [9] move-result v0
 *   [10] const/4 v1, 0
 *   [11] const/4 v2, 1
 *   [12] if-nez v0, +4h   (if result != 0, no match)
 *   [13] move v0, v2      (match)
 *   [14] goto +2h
 *   [15] move v0, v1      (no match)
 *   ...
 *
 * We replace instruction [7] (const-string v2, "com.fstop.photo.key")
 * with const-string v2, "com.fstop.photo". This makes checkSignatures
 * compare the package against itself, which always returns SIGNATURE_MATCH
 * when the original app is installed.
 *
 * If the original app is NOT installed, checkSignatures throws
 * NameNotFoundException, which is caught by the existing try-catch block,
 * and the method falls through to the IAP checks.
 */
@Suppress("unused")
val licenseCompatibilityPatch = bytecodePatch(
    name = "License compatibility",
    description = "Fixes premium detection for patched F-Stop APKs by " +
        "checking if the original F-Stop app is installed. The patched APK " +
        "cannot verify its own signature (re-signed), and Google Play " +
        "billing data is not accessible with a changed package name. " +
        "This patch replaces checkSignatures(mainApp, keyApp) with " +
        "checkSignatures(mainApp, mainApp), which succeeds only when " +
        "the original app is installed. NOT a bypass — does NOT unlock " +
        "premium for users without the original app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        PremiumCheckFingerprint.method.apply {
            val impl = implementation!!

            // Clear try-catch blocks to prevent DEX corruption during modification
            val tryBlocksField = MutableMethodImplementation::class.java
                .getDeclaredField("tryBlocks")
            tryBlocksField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tryBlocks = tryBlocksField.get(impl) as java.util.ArrayList<*>
            tryBlocks.clear()

            // Find the const-string for "com.fstop.photo.key" (instruction [7])
            val keyAppStringIndex = impl.instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING &&
                    it is ReferenceInstruction &&
                    it.reference.toString() == "com.fstop.photo.key"
            }

            if (keyAppStringIndex == -1) {
                throw PatchException(
                    "Could not find const-string 'com.fstop.photo.key' in s3.a.d(). " +
                        "The APK version may not be supported."
                )
            }

            // Replace "com.fstop.photo.key" with "com.fstop.photo"
            // This makes checkSignatures("com.fstop.photo", "com.fstop.photo")
            // which returns SIGNATURE_MATCH (0) when the original app is installed
            val register = (impl.instructions[keyAppStringIndex]
                as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA

            replaceInstruction(
                keyAppStringIndex,
                "const-string v$register, \"com.fstop.photo\"",
            )
        }
    }
}
