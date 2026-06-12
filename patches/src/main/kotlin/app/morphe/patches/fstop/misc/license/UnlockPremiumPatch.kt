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
 * Patch to bypass F-Stop premium verification entirely.
 *
 * ARCHITECTURE:
 * There are two patches for premium features:
 *
 * 1. "License compatibility" (always enabled): Fixes premium detection for
 *    users who have purchased the key app (com.fstop.photo.key). It injects
 *    a getPackageInfo() check at the start of s3.a.d() so the key app's
 *    presence is detected even though checkSignatures() fails for patched APKs.
 *    This is NOT a bypass — it only helps if the key app is legitimately
 *    installed.
 *
 * 2. "Unlock premium" (this patch, disabled by default): Bypasses ALL license
 *    checks by making s3.a.d() and s3.a.e() always return true. This is for
 *    users who have NOT purchased the premium key app and want to unlock
 *    premium features without a valid license.
 *
 * For legitimate license holders, the "License compatibility" patch alone
 * is sufficient. This "Unlock premium" patch should only be enabled by users
 * who do not have a valid premium license.
 *
 * NOTE: The methods p.N2() and p.x2() that check for the key app package
 * and component enabled state are NOT premium feature gates — they only
 * control the "Show/Hide Key App Icon" preference in settings. The actual
 * premium feature gating is done exclusively through s3.a.d(), which is
 * called 54 times across the codebase.
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Bypasses all license checks, making s3.a.d() and s3.a.e() " +
        "always return true. For users who have NOT purchased the premium " +
        "key app. If you have purchased the key app, the 'License compatibility' " +
        "patch (enabled by default) should be sufficient. Disabled by default.",
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
        // (or has the License compatibility check injected at start)
        // Patched: always returns true, bypassing everything

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
