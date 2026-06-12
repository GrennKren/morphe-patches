/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.license

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation

/**
 * Patch to unlock F-Stop premium features.
 *
 * PROBLEM:
 * F-Stop's premium features are gated by s3.a.d() which checks:
 * 1. Signature match between com.fstop.photo and com.fstop.photo.key
 * 2. isIAPPurchased flag in SharedPreferences
 * 3. Non-empty purchasedInAppPurchasesAndSubscriptions set in SharedPreferences
 *
 * For patched APKs, the signature check always fails because the patched
 * app has a different signing certificate than the key app. This means
 * even users who have legitimately purchased the premium key app will
 * not have premium features detected.
 *
 * The Google Play IAP flags also won't be set since the patched app
 * cannot verify purchases through Google Play Billing.
 *
 * NOTE: The methods p.N2() and p.x2() that check for the key app package
 * and component enabled state are NOT premium feature gates — they only
 * control the "Show/Hide Key App Icon" preference in settings. The actual
 * premium feature gating is done exclusively through s3.a.d(), which is
 * called 40+ times across the codebase.
 *
 * SOLUTION:
 * This patch modifies s3.a.d() and s3.a.e() to always return true,
 * bypassing all three verification checks.
 *
 * WARNING:
 * This patch is disabled by default. If you have purchased the premium
 * key app, this patch is still needed because the patched APK's signature
 * differs from the original, causing checkSignatures() to fail.
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Unlocks premium features by bypassing the license verification " +
        "in s3.a.d(). This is needed even for legitimately purchased keys because " +
        "the patched APK has a different signing certificate, causing " +
        "checkSignatures() to fail. Disabled by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        // Helper: clear all try-catch blocks from a MutableMethodImplementation.
        fun clearTryBlocks(impl: MutableMethodImplementation) {
            val field = MutableMethodImplementation::class.java.getDeclaredField("tryBlocks")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tryBlocks = field.get(impl) as java.util.ArrayList<*>
            tryBlocks.clear()
        }

        // ============================================================
        // Part 1: Patch s3.a.d() to always return true
        // ============================================================
        // Original: checks signature match + IAP flag + purchase set
        // Patched: always returns true

        PremiumCheckFingerprint.method.apply {
            val impl = implementation!!
            clearTryBlocks(impl)
            val instructions = impl.instructions.toList()

            for (i in instructions.size - 1 downTo 1) {
                impl.removeInstruction(i)
            }

            addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """,
            )
            removeInstruction(2)
        }

        // ============================================================
        // Part 2: Patch s3.a.e() to always return true
        // ============================================================
        // Same as d() but without signature check (used by billing flow)
        // Patched: always returns true

        PremiumCheckNoSigFingerprint.method.apply {
            val impl = implementation!!
            clearTryBlocks(impl)
            val instructions = impl.instructions.toList()

            for (i in instructions.size - 1 downTo 1) {
                impl.removeInstruction(i)
            }

            addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """,
            )
            removeInstruction(2)
        }
    }
}
