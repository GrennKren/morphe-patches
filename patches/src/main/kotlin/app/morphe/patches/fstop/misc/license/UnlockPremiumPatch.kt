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
 * 1. "License compatibility" (always enabled): Fixes the checkSignatures()
 *    call in s3.a.d() that always fails for patched APKs. It replaces the
 *    checkSignatures() result with SIGNATURE_MATCH (0), so the first OR
 *    condition in d() is always true. The IAP checks (b0.z and a.size())
 *    are preserved as fallback conditions. This is a compatibility fix,
 *    not a bypass.
 *
 * 2. "Unlock premium" (this patch, disabled by default): Bypasses ALL license
 *    checks by making s3.a.d() and s3.a.e() always return true. This is for
 *    users who want to unlock all premium features unconditionally.
 *
 * For most users, the "License compatibility" patch alone is sufficient.
 * This "Unlock premium" patch should only be enabled by users who want
 * to bypass all premium restrictions.
 *
 * NOTE: F-Stop uses Google Play In-App Purchasing (IAP) for premium —
 * there is no separate key app. The checkSignatures() call in d() is a
 * legacy check that always fails for patched APKs regardless of purchase
 * status. The methods p.N2() and p.x2() are NOT premium feature gates —
 * they only control the "Show/Hide Key App Icon" preference in settings.
 * The actual premium feature gating is done exclusively through s3.a.d(),
 * which is called 54 times across the codebase.
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Bypasses all license checks, making s3.a.d() and s3.a.e() " +
        "always return true. For users who want to unconditionally unlock " +
        "all premium features. The 'License compatibility' patch (enabled by " +
        "default) should be sufficient for most users. Disabled by default.",
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
