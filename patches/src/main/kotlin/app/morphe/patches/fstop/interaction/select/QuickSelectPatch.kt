/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.interaction.select

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to add a quick select button in F-Stop's media viewer toolbar.
 *
 * PROBLEM:
 * In F-Stop's media viewer (ViewImageActivityNew), the only way to select
 * an image or video for batch operations is by long-pressing:
 * 1. On the grid view — long-press a thumbnail
 * 2. On the FilmStrip (horizontal thumbnail strip at the bottom) — long-press
 *
 * There is no quick way to select the currently viewed item from the toolbar
 * or header area. This is inconvenient, especially when the FilmStrip is
 * hidden or when the user wants to quickly select without precise touch.
 *
 * SOLUTION:
 * Adds a "Select" toggle button to the media viewer's toolbar menu.
 * When tapped, it toggles the selection state of the currently displayed
 * image or video. The button icon changes to indicate the current state:
 * - Unselected: outline checkbox (gray)
 * - Selected: filled checkbox (green)
 *
 * The button appears in the toolbar (same area as share, delete, etc.),
 * NOT in fullscreen mode. It follows the toolbar show/hide behavior.
 *
 * Implementation:
 * 1. Hook onCreateOptionsMenu — after menu inflation, calls
 *    QuickSelectHelper.addSelectMenuItem() to add the select button
 * 2. Hook onPrepareOptionsMenu — calls QuickSelectHelper.updateSelectButtonIcon()
 *    to keep the icon in sync when navigating between images
 */
@Suppress("unused")
val quickSelectPatch = bytecodePatch(
    name = "Quick select in media viewer",
    description = "Adds a select/deselect toggle button to the media viewer's " +
        "toolbar, allowing quick selection of the currently viewed image or video " +
        "without needing to long-press on the FilmStrip thumbnail.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    extendWith("extensions/fstop.mpe")

    execute {
        val EXTENSION_CLASS = "Lapp/morphe/extension/fstop/QuickSelectHelper;"

        // ============================================================
        // Part 1: Add select menu item after menu inflation
        // ============================================================
        // Hook onCreateOptionsMenu(ViewImageActivityNew, Menu)
        // After getMenuInflater().inflate(view_image_menu, menu), we inject
        // a call to QuickSelectHelper.addSelectMenuItem(this, menu) which
        // adds the select button to the menu.
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

            // Find the move-result after inflate (or the next instruction)
            // We need to inject AFTER the inflate call and its move-result
            var injectIndex = inflateIndex + 1
            val nextInstr = implementation!!.instructions.elementAt(injectIndex)
            if (nextInstr.opcode == Opcode.MOVE_RESULT ||
                nextInstr.opcode == Opcode.MOVE_RESULT_OBJECT ||
                nextInstr.opcode == Opcode.MOVE_RESULT_WIDE
            ) {
                injectIndex++
            }

            // At this point, p0 = this (ViewImageActivityNew), p1 = Menu
            addInstructions(
                injectIndex,
                """
                    # Add quick select button to toolbar menu
                    invoke-static {p0, p1}, $EXTENSION_CLASS->addSelectMenuItem(Landroid/app/Activity;Landroid/view/Menu;)V
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

            // Inject after the x2() call and its move-result
            var injectIndex = x2Index + 1
            val nextInstr = implementation!!.instructions.elementAt(injectIndex)
            if (nextInstr.opcode == Opcode.MOVE_RESULT ||
                nextInstr.opcode == Opcode.MOVE_RESULT_OBJECT
            ) {
                injectIndex++
            }

            // p0 = this (ViewImageActivityNew), p1 = Menu
            addInstructions(
                injectIndex,
                """
                    # Update select button icon to match current selection state
                    invoke-static {p0, p1}, $EXTENSION_CLASS->updateSelectButtonIcon(Landroid/app/Activity;Landroid/view/Menu;)V
                """,
            )
        }
    }
}
