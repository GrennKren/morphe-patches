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
 * Patch to unlock F-Stop premium features without requiring the key app.
 *
 * PROBLEM:
 * F-Stop's premium features are gated behind a license key app
 * (com.fstop.photo.key) that must be purchased separately on Google Play.
 * The app checks for the key in two ways:
 *
 * 1. p.N2() — Checks if the package "com.fstop.photo.key" is installed
 *    via A2() helper which calls PackageManager.getPackageInfo().
 *    Returns true if installed = premium unlocked.
 *
 * 2. p.x2() — Checks if the key app's MainActivity component is disabled
 *    via PackageManager.getComponentEnabledSetting(). The key app disables
 *    its own launcher activity after activation. Returns true if disabled
 *    = key app was activated = premium valid.
 *
 * These two methods are called throughout the app to determine if premium
 * features should be available.
 *
 * SOLUTION:
 * This patch modifies both methods to always return the values that
 * indicate a valid premium license:
 *
 * - N2() → always returns true (key app appears to be installed)
 * - x2() → always returns true (key app's launcher appears disabled = activated)
 *
 * Implementation:
 * For each method, we clear the body and insert simple return instructions.
 * This is safe because these methods are simple boolean checks with no
 * side effects beyond the PackageManager query.
 *
 * WARNING:
 * This patch is disabled by default. Use at your own risk. If you have
 * purchased the premium key app, you do NOT need this patch.
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Unlocks premium features by making the app think the " +
        "F-Stop Key app is installed and activated. This bypasses the license " +
        "verification that checks for the com.fstop.photo.key package. " +
        "Disabled by default — only enable if you understand the implications.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        // Helper: clear all try-catch blocks from a MutableMethodImplementation.
        // Without this, removing instructions that are referenced by exception handlers
        // leaves dangling handler addresses, which causes DEX verification to fail with
        // "Invalid handler addr" and the entire DEX file becomes unloadable.
        fun clearTryBlocks(impl: MutableMethodImplementation) {
            val field = MutableMethodImplementation::class.java.getDeclaredField("tryBlocks")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tryBlocks = field.get(impl) as java.util.ArrayList<*>
            tryBlocks.clear()
        }

        // ============================================================
        // Part 1: Patch N2() to always return true
        // ============================================================
        // Original: calls A2("com.fstop.photo.key", context) which checks
        // if the key package is installed. Returns true if found.
        // Patched: always returns true (const/4 v0, 0x1; return v0)

        KeyAppCheckFingerprint.method.apply {
            val impl = implementation!!
            // Clear try-catch blocks FIRST to avoid "Invalid handler addr" DEX errors
            clearTryBlocks(impl)
            val instructions = impl.instructions.toList()

            // Remove all instructions from end to 1
            for (i in instructions.size - 1 downTo 1) {
                impl.removeInstruction(i)
            }

            // Replace first instruction with: const/4 v0, 0x1; return v0
            addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """,
            )
            // Remove the original first instruction (now at index 2)
            removeInstruction(2)
        }

        // ============================================================
        // Part 2: Patch x2() to always return true
        // ============================================================
        // Original: checks if the key app's component is disabled.
        // Returns true if disabled (key app was activated).
        // Patched: always returns true (const/4 v0, 0x1; return v0)
        // This means "key app is activated" = premium is valid.

        KeyAppEnabledCheckFingerprint.method.apply {
            val impl = implementation!!
            // Clear try-catch blocks FIRST to avoid "Invalid handler addr" DEX errors
            clearTryBlocks(impl)
            val instructions = impl.instructions.toList()

            // Remove all instructions from end to 1
            for (i in instructions.size - 1 downTo 1) {
                impl.removeInstruction(i)
            }

            // Replace first instruction with: const/4 v0, 0x1; return v0
            addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """,
            )
            // Remove the original first instruction (now at index 2)
            removeInstruction(2)
        }
    }
}
