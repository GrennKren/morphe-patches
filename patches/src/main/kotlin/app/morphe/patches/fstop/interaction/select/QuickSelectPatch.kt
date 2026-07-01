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
 * IMPLEMENTATION DETAILS (v12 — floating draggable PiP-style button):
 *
 * 1. Floating draggable button: The Quick Select button uses absolute X/Y
 *    positioning and an OnTouchListener that distinguishes tap vs drag via
 *    the system touch-slop threshold. Tapping toggles selection; dragging
 *    moves the button (position is persisted to SharedPreferences). This
 *    mirrors YouTube vanilla Picture-in-Picture behavior.
 *
 * 2. Persistent visibility (NOT auto-hidden on fullscreen):
 *    The button's visibility is controlled exclusively by the user via a
 *    "Hide Quick Select" / "Show Quick Select" menu item added to the 3-dot
 *    context menu. The previous behavior that hid the button when entering
 *    fullscreen mode (I2()) has been REMOVED. The button now stays visible
 *    across fullscreen transitions unless the user explicitly hides it.
 *    The visibility state is persisted to SharedPreferences.
 *
 * 3. Selection indicator: Button icon changes (gray circle outline → green checkmark).
 *    Uses c3.t.z() for reliable state checking.
 *
 * 4. Toggle: Calls X(boolean) to set per-item selected flag.
 *    Do NOT call c0(int) — it sets L (FAVORITES), not selection!
 *
 * 5. Bidirectional sync:
 *    Direction 1 (Button → FilmStrip): Set p1.m + FilmStrip.D + invalidate()
 *    Direction 2 (FilmStrip → Button): Hook g(I Z)V — the native selection callback
 *    that is called by FilmStrip$a.onLongPress() after user long-presses a thumbnail.
 *    When g() fires, c3/t.X() has already been called, so z() reflects the new state.
 *
 * 6. Page change: Hooks M3() in ViewImageActivityNew (called from onPageSelected
 *    in inner class $o) to update the button icon immediately when swiping.
 *    M3() is in ViewImageActivityNew itself, so p0 = this = Activity (type-safe!).
 *
 * 7. Native select items: The native savePositionAndZoomMenuItem and
 *    resetPositionAndZoomMenuItem are hidden since our button replaces their
 *    selection-related visibility behavior.
 *
 * BYTECODE HOOKS:
 * - onCreateOptionsMenu: After menu inflation, calls addSelectButton(Activity, Menu)
 *   — also injects the "Hide/Show Quick Select" menu item into the 3-dot menu.
 * - onPrepareOptionsMenu: After x2(menu), calls updateSelectButtonIcon(Activity, Menu)
 *   — also refreshes the toggle menu item's title.
 * - M3(): Before return-void, calls onPageChanged(Activity)
 * - I2() (hide toolbar): Before return-void, calls onToolbarHidden(Activity)
 *   — note: in v12 this NO LONGER hides the button; it only re-applies the
 *   user's persisted visibility preference.
 * - a4() (show toolbar): Before return-void, calls onToolbarShown(Activity)
 *   — note: in v12 this NO LONGER forces the button visible; it only
 *   re-applies the user's persisted visibility preference.
 * - g(I Z) (native selection callback): Before return-void, calls onNativeSelectionChange(Activity)
 */
@Suppress("unused")
val quickSelectPatch = bytecodePatch(
    name = "Quick select in media viewer",
    description = "Adds a quick select button in the image viewer so you " +
        "can select or deselect the current photo or video with a single " +
        "tap instead of long-pressing on the thumbnail strip.",
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
        // Part 4: Hook I2() (hide toolbar / enter fullscreen)
        // ============================================================
        // In v12, this hook NO LONGER hides the Quick Select button. The
        // previous behavior of auto-hiding the button when entering
        // fullscreen mode has been REMOVED by user request. The button now
        // stays visible across fullscreen transitions unless the user
        // explicitly hides it via the 3-dot menu.
        //
        // The hook is kept so that onToolbarHidden() can re-apply the user's
        // persisted visibility preference (in case something else changed
        // the button's visibility state).
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
                    # Quick Select: re-apply visibility preference on fullscreen entry
                    invoke-static {p0}, $EXTENSION_CLASS->onToolbarHidden(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 5: Hook a4() (show toolbar / exit fullscreen)
        // ============================================================
        // In v12, this hook NO LONGER forces the button visible. The button's
        // visibility is controlled exclusively by the user's persisted
        // preference (set via the 3-dot menu's "Hide/Show Quick Select" item).
        //
        // The hook is kept so that onToolbarShown() can re-apply the user's
        // preference when exiting fullscreen mode.
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
                    # Quick Select: re-apply visibility preference on fullscreen exit
                    invoke-static {p0}, $EXTENSION_CLASS->onToolbarShown(Landroid/app/Activity;)V
                """,
            )
        }

        // ============================================================
        // Part 6: Hook g(I Z)V — native selection callback
        // ============================================================
        // When user long-presses a FilmStrip thumbnail, FilmStrip$a.onLongPress()
        // toggles p1.m, invalidates FilmStrip, then calls g(index, selected).
        // g() calls c3/t.X(selected) on the item at that index.
        // After X() completes, we update our button icon to reflect the new state.
        //
        // g() registers: 4 total, p0=v1=this=ViewImageActivityNew=Activity
        // Has try-catch: happy path goto→return-void, catch→printStackTrace→return-void
        SelectionCallbackFingerprint.method.apply {
            val impl = implementation!!

            val returnIndex = impl.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            }

            if (returnIndex == -1) {
                throw PatchException(
                    "Could not find return-void in g(). The APK version may not be supported."
                )
            }

            addInstructions(
                returnIndex,
                """
                    # Quick Select: native selection changed (long-press on thumbnail)
                    invoke-static {p0}, $EXTENSION_CLASS->onNativeSelectionChange(Landroid/app/Activity;)V
                """,
            )
        }
    }
}
