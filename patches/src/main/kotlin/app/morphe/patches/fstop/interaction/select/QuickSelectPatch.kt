/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.interaction.select

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to add a quick select icon button in F-Stop's media viewer.
 *
 * IMPLEMENTATION DETAILS:
 *
 * 1. Auto-hide: Hook I2() and a4() directly to show/hide the button.
 *
 * 2. Selection indicator: Button icon changes (gray circle → green checkmark).
 *    Uses c3.t.z() instead of U() for reliable state checking.
 *
 * 3. Toggle: Calls X(boolean) AND c0(int) to keep both selection fields in sync.
 *
 * 4. FilmStrip sync: Uses FilmStrip.e(String) for matching + syncAllFilmStripSelections()
 *    to ensure bidirectional consistency between button and thumbnails.
 *
 * 5. Page change: Hooks onPageSelected in ViewImageActivityNew$o inner class
 *    to update the button icon immediately when swiping — no delay.
 *
 * BYTECODE HOOKS:
 * - onCreateOptionsMenu: After menu inflation, calls addSelectButton()
 * - onPrepareOptionsMenu: After x2(menu), calls updateSelectButtonIcon()
 * - onPageSelected (inner class $o): After M3() call, calls onPageChanged()
 * - I2() (hide toolbar): Before return-void, calls onToolbarHidden()
 * - a4() (show toolbar): Before return-void, calls onToolbarShown()
 */
@Suppress("unused")
val quickSelectPatch = bytecodePatch(
    name = "Quick select in media viewer",
    description = "Adds a select/deselect toggle icon button below the header bar " +
        "in the media viewer, allowing quick one-tap selection of the currently " +
        "viewed image or video without needing to long-press on the FilmStrip. " +
        "The button automatically hides in fullscreen mode and syncs selection " +
        "state with the FilmStrip thumbnails.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    extendWith("extensions/fstop.mpe")

    execute {
        val EXTENSION_CLASS = "Lapp/morphe/extension/fstop/QuickSelectHelper;"

        // ============================================================
        // Part 1: Add select button after menu inflation
        // ============================================================
        CreateOptionsMenuFingerprint.method.apply {
            val inflateIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("MenuInflater;->inflate")
            }

            if (inflateIndex == -1) {
                throw PatchException(
                    "Could not find MenuInflater.inflate() call in onCreateOptionsMenu. " +
                        "The APK version may not be supported."
                )
            }

            var injectIndex = inflateIndex + 1
            val nextInstr = implementation!!.instructions.elementAt(injectIndex)
            if (nextInstr.opcode == Opcode.MOVE_RESULT ||
                nextInstr.opcode == Opcode.MOVE_RESULT_OBJECT ||
                nextInstr.opcode == Opcode.MOVE_RESULT_WIDE
            ) {
                injectIndex++
            }

            addInstructions(
                injectIndex,
                """
                    # Add quick select floating button below header bar
                    invoke-static {p0}, $EXTENSION_CLASS->addSelectButton(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 2: Update select button icon when preparing menu
        // ============================================================
        PrepareOptionsMenuFingerprint.method.apply {
            val x2Index = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("ViewImageActivityNew;->x2")
            }

            if (x2Index == -1) {
                throw PatchException(
                    "Could not find x2() call in onPrepareOptionsMenu. " +
                        "The APK version may not be supported."
                )
            }

            var injectIndex = x2Index + 1
            val nextInstr = implementation!!.instructions.elementAt(injectIndex)
            if (nextInstr.opcode == Opcode.MOVE_RESULT ||
                nextInstr.opcode == Opcode.MOVE_RESULT_OBJECT
            ) {
                injectIndex++
            }

            addInstructions(
                injectIndex,
                """
                    # Update quick select button icon
                    invoke-static {p0}, $EXTENSION_CLASS->updateSelectButtonIcon(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 3: Hook onPageSelected for instant icon update on swipe
        // ============================================================
        // ViewImageActivityNew$o.onPageSelected(I)V is the callback
        // called by ViewPager when the user swipes to a new page.
        // It accesses v0 = this.g (ViewImageActivityNew) at index [0].
        // We hook after M3() call (index [27]) to ensure u0 is updated.
        PageSelectedFingerprint.method.apply {
            val impl = implementation!!

            // Find the M3() call — after this, u0 is updated with new position
            val m3Index = impl.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("ViewImageActivityNew;->M3")
            }

            if (m3Index == -1) {
                throw PatchException(
                    "Could not find M3() call in onPageSelected. " +
                        "The APK version may not be supported."
                )
            }

            var injectIndex = m3Index + 1
            val nextInstr = impl.instructions.elementAtOrNull(injectIndex)
            if (nextInstr != null && (nextInstr.opcode == Opcode.MOVE_RESULT ||
                    nextInstr.opcode == Opcode.MOVE_RESULT_OBJECT ||
                    nextInstr.opcode == Opcode.MOVE_RESULT_WIDE)
            ) {
                injectIndex++
            }

            // v0 = this.g (ViewImageActivityNew) — loaded at instruction [0]
            // We call QuickSelectHelper.onPageChanged(v0)
            addInstructions(
                injectIndex,
                """
                    # Quick Select: update button on page change (swipe)
                    invoke-static {v0}, $EXTENSION_CLASS->onPageChanged(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 4: Hook I2() (hide toolbar) to hide quick select button
        // ============================================================
        HideToolbarFingerprint.method.apply {
            val impl = implementation!!

            val returnIndex = impl.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            }

            if (returnIndex == -1) {
                throw PatchException(
                    "Could not find return-void in I2(). The APK version may not be supported."
                )
            }

            addInstructions(
                returnIndex,
                """
                    # Quick Select: hide button when toolbar is hidden
                    invoke-static {p0}, $EXTENSION_CLASS->onToolbarHidden(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 5: Hook a4() (show toolbar) to show quick select button
        // ============================================================
        ShowToolbarFingerprint.method.apply {
            val impl = implementation!!

            val returnIndex = impl.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            }

            if (returnIndex == -1) {
                throw PatchException(
                    "Could not find return-void in a4(). The APK version may not be supported."
                )
            }

            addInstructions(
                returnIndex,
                """
                    # Quick Select: show button when toolbar is shown
                    invoke-static {p0}, $EXTENSION_CLASS->onToolbarShown(Landroid/app/Activity;)V
                """,
            )
        }
    }
}
