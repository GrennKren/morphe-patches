/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.textviewer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to fix FX Explorer's text file viewer respecting the "Opening Files" settings.
 *
 * BUG: In FX Explorer, when "Text Files" is unchecked in Settings > Opening Files,
 * .txt files still open with the built-in FX Text Viewer/Editor instead of using
 * the "Open With" dialog. Video and image settings work correctly — only text files
 * have this bug.
 *
 * Root cause: In hf/b0.a() (the master file-open decision method), when the
 * textViewerUseInternal SharedPreferences flag is FALSE, the code uses a `goto`
 * instruction that jumps to the TextViewerActivity launch code instead of
 * the "Open With" dialog (y0.j() call).
 *
 * The relevant code flow in b0.a():
 *   [248] const-string v3, "textViewerUseInternal"
 *   [249] invoke-interface {v6,v3,v14}, SharedPreferences.getBoolean (default=true)
 *   [250] move-result v3
 *   [251] if-nez v3, +3 → [253]   // TRUE → check textViewerUseEditor
 *   [252] goto +0xd → [260]        // FALSE → BUG: still goes to TextViewerActivity!
 *   [253] const-string v3, "textViewerUseEditor"
 *   ...
 *   [260] const-string v3, "nextapp.fx.ui.viewer.TextViewerActivity"  // BUG target!
 *   ...
 *   [317] invoke-static/range {v17..v19}, Lhf/y0;->j(...)  // correct external path
 *
 * FIX: Replace the buggy `goto +0xd → [260]` at [252] with code that calls
 * y0.j() and returns. This makes the FALSE path open externally via the
 * "Open With" dialog instead of the internal TextViewerActivity.
 *
 * Parameter registers at the injection point:
 * v17=Activity(Context), v18=kh/e(fileItem), v19=lf/b(callback)
 * These are preserved throughout the method for y0.j() fallback calls.
 *
 * This is a PRE-EXISTING bug in FX Explorer, not caused by any patch.
 */
@Suppress("unused")
val textFileViewerFixPatch = bytecodePatch(
    name = "Fix text file viewer setting",
    description = "Fixes a bug where text files (.txt etc.) still open with the built-in " +
        "FX Text Viewer/Editor even when 'Text Files' is unchecked in " +
        "Settings > Opening Files. When unchecked, text files will now correctly " +
        "open via the 'Open With' dialog (or the default app if 'Open files externally' " +
        "patch is also applied). This fixes a pre-existing bug in FX Explorer.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    execute {
        FileOpenerFingerprint.method.apply {
            // Find the const-string "textViewerUseInternal" instruction
            val textViewerCheckIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("textViewerUseInternal")
            }

            if (textViewerCheckIndex == -1) {
                throw PatchException(
                    "Could not find 'textViewerUseInternal' string in b0.a(). " +
                        "The APK version may not be supported."
                )
            }

            // The goto instruction is 4 positions after the const-string:
            // [N]   const-string v3, "textViewerUseInternal"
            // [N+1] invoke-interface (SharedPreferences.getBoolean)
            // [N+2] move-result v3
            // [N+3] if-nez v3, +3 → editor check  (TRUE path)
            // [N+4] goto +0xd → [260]             (FALSE path — BUG!)
            val gotoIndex = textViewerCheckIndex + 4

            // Verify the instruction at gotoIndex is indeed a goto
            val gotoInstruction = implementation!!.instructions.elementAt(gotoIndex)
            if (gotoInstruction.opcode != Opcode.GOTO) {
                throw PatchException(
                    "Expected goto at index $gotoIndex after textViewerUseInternal check, " +
                        "but found ${gotoInstruction.opcode.name}. " +
                        "The APK version may not be supported."
                )
            }

            // Remove the buggy goto instruction
            removeInstruction(gotoIndex)

            // Insert the fix: call y0.j() and return when textViewerUseInternal=FALSE
            // This replaces the goto that incorrectly went to TextViewerActivity
            addInstructions(
                gotoIndex,
                """
                    # textViewerUseInternal is FALSE — open externally with "Open With" dialog
                    # v17=Activity(Context), v18=kh/e(fileItem), v19=lf/b(callback)
                    invoke-static/range {v17..v19}, Lhf/y0;->j(Landroid/content/Context;Lkh/e;Llf/b;)V
                    return-void
                """,
            )
        }
    }
}
