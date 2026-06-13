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
 * PROBLEM:
 * In F-Stop's media viewer (ViewImageActivityNew), the only way to select
 * an image or video for batch operations is by long-pressing on thumbnails
 * in the grid view or the FilmStrip. There is no quick one-tap way to
 * select the currently viewed item.
 *
 * SOLUTION:
 * Adds a floating icon button BELOW the header bar in the media viewer.
 * When tapped, it toggles the selection state of the currently displayed
 * image or video. The icon changes to indicate the current state:
 * - Unselected: gray circle outline with dot
 * - Selected: green filled circle with checkmark
 *
 * IMPLEMENTATION DETAILS:
 *
 * 1. Auto-hide: Directly hook the bytecode of I2() (hide toolbar) and
 *    a4() (show toolbar) to call QuickSelectHelper.onToolbarHidden/onToolbarShown.
 *    The previous approach using OnLayoutChangeListener on the toolbar view
 *    doesn't work because F-Stop uses AlphaAnimation with setFillAfter(true)
 *    which does NOT trigger layout change listeners. Hooking I2()/a4() directly
 *    is guaranteed to work because they are the exact methods that control
 *    fullscreen mode.
 *
 * 2. Selection indicator: The button icon changes (gray circle → green check)
 *    to show the current selection state. Uses c3.t.z() (returns field 's')
 *    instead of c3.t.U() (checks field 'L'), because X() only updates 's'.
 *    Also calls c0() to keep 'L' in sync.
 *
 * 3. Toggle: The button calls toggleCurrentItemSelection() which calls both
 *    X(boolean) to set field 's' AND c0(int) to set field 'L', keeping both
 *    selection indicators in sync with the app's internal state.
 *
 * 4. FilmStrip sync: After toggling, syncFilmStripSelection() uses
 *    FilmStrip.e(String) to find the matching p1 thumbnail by path,
 *    then updates p1.m and calls FilmStrip.invalidate() to redraw
 *    the checkmark overlay on the thumbnail.
 *
 * BYTECODE HOOKS:
 * - onCreateOptionsMenu: After menu inflation, calls addSelectButton()
 * - onPrepareOptionsMenu: After x2(menu), calls updateSelectButtonIcon()
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

            // p0 = this (ViewImageActivityNew)
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

            // p0 = this (ViewImageActivityNew)
            addInstructions(
                injectIndex,
                """
                    # Update quick select button icon
                    invoke-static {p0}, $EXTENSION_CLASS->updateSelectButtonIcon(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 3: Hook I2() (hide toolbar) to hide quick select button
        // ============================================================
        // I2() uses AlphaAnimation with setFillAfter(true) to fade out the
        // toolbar. OnLayoutChangeListener does NOT fire for this animation,
        // so we must hook the method directly.
        // Bytecode pattern: ... iput-boolean v1, v6, H0 Z (v1=0) ... return-void
        // We inject before return-void: call QuickSelectHelper.onToolbarHidden(this)
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

            // p0 = this (ViewImageActivityNew)
            addInstructions(
                returnIndex,
                """
                    # Quick Select: hide button when toolbar is hidden
                    invoke-static {p0}, $EXTENSION_CLASS->onToolbarHidden(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 4: Hook a4() (show toolbar) to show quick select button
        // ============================================================
        // a4() uses AlphaAnimation(0.0f, 1.0f) with setFillAfter(true) to
        // fade in the toolbar. Same approach as I2().
        // Bytecode pattern: ... iput-boolean v2, v7, H0 Z (v2=1) ... return-void
        // We inject before return-void: call QuickSelectHelper.onToolbarShown(this)
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
