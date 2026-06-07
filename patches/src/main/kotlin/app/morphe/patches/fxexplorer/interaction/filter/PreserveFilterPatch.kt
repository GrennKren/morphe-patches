/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.interaction.filter

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import app.morphe.patches.fxexplorer.shared.changeFxPackageNamePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to preserve the active filter when returning from viewing a file.
 *
 * Problem:
 * When a user has an active filter in the file browser and opens a file
 * (image, video, text), upon returning, the filter is completely cleared
 * and the browser shows all files unfiltered. This is annoying because the
 * user has to re-enter the filter text every time they view a file.
 *
 * Root cause:
 * The directory refresh method (L0 in lf/s) unconditionally calls V0()
 * which clears the filter state. When the user returns from viewing a file,
 * onResume() triggers R0() -> L0() -> V0(), clearing the filter.
 *
 * Solution:
 * Modify L0() to skip calling V0() when a filter is active (g2 != null).
 * When V0() is skipped:
 * - The filter bar stays visible with the current text
 * - The filter text (g2) is preserved
 * - The adapter's internal filter string (B4 in jf/f) is preserved
 * - When new directory data is loaded, A0() in jf/f reads B4 and
 *   automatically re-applies the filter to the new data
 *
 * This ensures the filter persists through directory refreshes, whether
 * triggered by returning from a file view, pull-to-refresh, or any other
 * refresh mechanism.
 */
@Suppress("unused")
val preserveFilterPatch = bytecodePatch(
    name = "Preserve filter on refresh",
    description = "Preserves the active file filter when returning from viewing a file or when the directory refreshes. " +
        "Normally, the filter is cleared every time the directory refreshes, which is inconvenient " +
        "when opening files to view them and then returning to the filtered list.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    // Change package name and update all manifest references so the patched
    // app can be installed alongside the original FX Explorer.
    dependsOn(changeFxPackageNamePatch)

    execute {
        DirectoryRefreshFingerprint.method.apply {
            // Find the V0() call that unconditionally clears the filter.
            // In L0() the pattern is:
            //   invoke-virtual {p0}, Llf/s;->K0()V   (cancel running task)
            //   invoke-virtual {p0}, Llf/s;->V0()V   (CLEAR FILTER - target)
            //   iget-object v0, p0, Llf/s;->a2:...   (next instruction)
            val v0CallIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("Llf/s;->V0()V")
            }

            if (v0CallIndex == -1) {
                throw PatchException(
                    "Could not find V0() call in directory refresh method. " +
                        "The APK version may not be supported."
                )
            }

            // L0() has .locals 8, so v0-v7 are available.
            // v0 is free at this point (it's first used after V0() for Handler access).
            //
            // We replace the unconditional V0() call with a conditional one:
            //   iget-object v0, p0, Llf/s;->g2:Ljava/lang/String;   // Load filter text
            //   if-nez v0, :skip_clear                                 // If filter active, skip clear
            //   invoke-virtual {p0}, Llf/s;->V0()V                    // Clear filter (only when no filter)
            //   :skip_clear
            //   nop
            //
            // Then remove the original V0() call which has been shifted down.
            addInstructionsWithLabels(
                v0CallIndex,
                """
                    iget-object v0, p0, Llf/s;->g2:Ljava/lang/String;
                    if-nez v0, :skip_clear
                    invoke-virtual {p0}, Llf/s;->V0()V
                    :skip_clear
                    nop
                """
            )

            // After injection, instructions are:
            //   [0] iget-object  (injected)
            //   [1] if-nez       (injected)
            //   [2] invoke-virtual V0() (injected - conditional)
            //   [3] nop          (injected - label anchor)
            //   [4] invoke-virtual V0() (ORIGINAL - to remove)
            // The original V0() is now 4 instructions after the injection point.
            removeInstruction(v0CallIndex + 4)
        }
    }
}
