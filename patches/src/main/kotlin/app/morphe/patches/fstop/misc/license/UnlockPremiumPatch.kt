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
 * Bypasses ALL license checks by making s3.a.d() and s3.a.e() always return
 * true. This unconditionally unlocks all premium features for the patched APK.
 *
 * The patch replaces the method bodies of:
 *   - s3.a.d() — the main premium check (signature + IAP + purchase set)
 *   - s3.a.e() — the billing flow check (IAP + purchase set, no signature)
 *
 * Both are replaced with: const/4 v0, 0x1; return v0 (always return true).
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
        "always return true. Unconditionally unlocks all premium features. " +
        "Enabled by default.",
    default = true,
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
