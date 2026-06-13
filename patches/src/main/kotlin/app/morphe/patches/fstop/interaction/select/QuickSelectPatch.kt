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
 * 2. Selection indicator: Button icon changes (gray circle outline → green checkmark).
 *    Uses c3.t.z() for reliable state checking.
 *
 * 3. Toggle: Calls X(boolean) AND c0(int) to keep both selection fields in sync.
 *
 * 4. FilmStrip sync: Uses FilmStrip.e(String) for matching + syncAllFilmStripSelections()
 *    to ensure bidirectional consistency between button and thumbnails.
 *    Also adds a pre-draw listener on the FilmStrip to detect selection changes
 *    made via long-press on thumbnails.
 *
 * 5. Page change: Hooks M3() in ViewImageActivityNew (called from onPageSelected
 *    in inner class $o) to update the button icon immediately when swiping.
 *    M3() is in ViewImageActivityNew itself, so p0 = this = Activity (type-safe!).
 *
 * 6. Native select items: The native savePositionAndZoomMenuItem and
 *    resetPositionAndZoomMenuItem are hidden since our button replaces their
 *    selection-related visibility behavior.
 *
 * BYTECODE HOOKS:
 * - onCreateOptionsMenu: After menu inflation, calls addSelectButton(Activity, Menu)
 * - onPrepareOptionsMenu: After x2(menu), calls updateSelectButtonIcon(Activity, Menu)
 * - M3(): Before return-void, calls onPageChanged(Activity)
 * - I2() (hide toolbar): Before return-void, calls onToolbarHidden(Activity)
 * - a4() (show toolbar): Before return-void, calls onToolbarShown(Activity)
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

            // onCreateOptionsMenu has registers: 4, p0=v2=Activity, p1=v3=Menu
            addInstructions(
                injectIndex,
                """
                    # Add quick select floating button below header bar
                    invoke-static {p0, p1}, $EXTENSION_CLASS->addSelectButton(Landroid/app/Activity;Landroid/view/Menu;)V
                """,
            )
        }

        // ============================================================
        // Part 2: Update select button icon when preparing menu
        // Also hide native select/deselect menu items since our button replaces them
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
            val nextInstr = implementation!!.instructions.elementAtOrNull(injectIndex)
            if (nextInstr != null && (nextInstr.opcode == Opcode.MOVE_RESULT ||
                    nextInstr.opcode == Opcode.MOVE_RESULT_OBJECT)
            ) {
                injectIndex++
            }

            // onPrepareOptionsMenu has registers: 2, p0=v0=Activity, p1=v1=Menu
            addInstructions(
                injectIndex,
                """
                    # Update quick select button icon and hide native select menu items
                    invoke-static {p0, p1}, $EXTENSION_CLASS->updateSelectButtonIcon(Landroid/app/Activity;Landroid/view/Menu;)V
                """,
            )
        }

        // ============================================================
        // Part 3: Hook M3() for instant icon update on swipe
        // ============================================================
        // ViewImageActivityNew.M3()V is called from onPageSelected in the
        // inner class $o when the user swipes to a new page.
        // M3() is on ViewImageActivityNew itself, so p0 = this = Activity.
        // This is type-safe (no VerifyError) unlike the previous approach
        // that hooked the inner class where register v0 was reused for
        // FilmStrip at the injection point.
        M3Fingerprint.method.apply {
            val impl = implementation!!

            val returnIndex = impl.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            }

            if (returnIndex == -1) {
                throw PatchException(
                    "Could not find return-void in M3(). " +
                        "The APK version may not be supported."
                )
            }

            addInstructions(
                returnIndex,
                """
                    # Quick Select: update button on page change (swipe)
                    invoke-static {p0}, $EXTENSION_CLASS->onPageChanged(Landroid/app/Activity;)V
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
