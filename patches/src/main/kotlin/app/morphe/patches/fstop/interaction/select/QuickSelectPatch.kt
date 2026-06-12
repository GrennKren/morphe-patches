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
 * - Unselected: gray circle outline
 * - Selected: green filled circle with checkmark
 *
 * The button is NOT inside the 3-dot menu or the toolbar itself — it's
 * a standalone floating icon positioned below the header bar on the right
 * side of the screen. It follows the toolbar show/hide behavior
 * (hidden in fullscreen mode).
 *
 * Implementation:
 * 1. Hook onCreateOptionsMenu — after menu inflation, calls
 *    QuickSelectHelper.addSelectButton() to add the floating button
 * 2. Hook onPrepareOptionsMenu — calls QuickSelectHelper.updateSelectButtonIcon()
 *    to keep the icon in sync when navigating between images
 */
@Suppress("unused")
val quickSelectPatch = bytecodePatch(
    name = "Quick select in media viewer",
    description = "Adds a select/deselect toggle icon button below the header bar " +
        "in the media viewer, allowing quick one-tap selection of the currently " +
        "viewed image or video without needing to long-press on the FilmStrip.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    extendWith("extensions/fstop.mpe")

    execute {
        val EXTENSION_CLASS = "Lapp/morphe/extension/fstop/QuickSelectHelper;"

        // ============================================================
        // Part 1: Add select button after menu inflation
        // ============================================================
        // Hook onCreateOptionsMenu(ViewImageActivityNew, Menu)
        // After getMenuInflater().inflate(view_image_menu, menu), we inject
        // a call to QuickSelectHelper.addSelectButton(this) which adds
        // the floating icon button to the activity's content view.
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
        // Hook onPrepareOptionsMenu(ViewImageActivityNew, Menu)
        // After x2(menu) call, inject QuickSelectHelper.updateSelectButtonIcon()
        // to update the icon based on the current item's selection state.
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
    }
}
