/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.openexternally

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to add missing MIME types and "Open With Default" feature to FX Explorer.
 *
 * Part 1 — MIME type injection:
 * FX Explorer's Lab/k; class has a static MIME type map that maps file extensions
 * to MIME types. Many common extensions are missing (.md, .yaml, .toml, etc.).
 * When an extension is not in the map, clicking a file shows the "Open As" dialog
 * instead of opening directly with the appropriate external application.
 *
 * Solution: Inject additional Map.put() calls into the <clinit> method of Lab/k;,
 * right before the Collections.unmodifiableMap() call that seals the map.
 *
 * Part 2 — "Open With Default" section in "Open With" dialog:
 * Adds a new section at the top of the "Open With" dialog containing a single
 * wildcard MIME type button. When clicked, it sets the MIME type override to
 * the wildcard type and re-resolves available apps, effectively showing ALL
 * apps that can open any file type. This is the same behavior as clicking the
 * wildcard button in the "Open As" dialog, but directly accessible without
 * navigating to "Open As" first.
 *
 * Implementation details for Part 2:
 * - Target method: Lhf/w0;.run()V (the dialog populator Runnable)
 * - Injection point: After the header is added (d2=null, header added to layout)
 *   and before the resolver results (Lqe/a; fields) are read
 * - The injection adds:
 *   1. A section header via y0.f("Open With Default")
 *   2. A grid item button via y0.e(label, icon, null, clickListener)
 *   3. The click listener is OpenWithDefaultClickListener from the extension,
 *      which sets y0.e2 = wildcard MIME and calls y0.i() to re-resolve
 */
@Suppress("unused")
val openExternallyPatch = bytecodePatch(
    name = "Open files externally",
    description = "Adds missing MIME types for .md, .yaml, .toml and other file extensions, " +
        "so files with these extensions can be opened directly with external applications " +
        "instead of showing the 'Open As' dialog. Also adds an 'Open With Default' section " +
        "to the 'Open With' dialog with a wildcard MIME type button.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    extendWith("extensions/fxexplorer.mpe")

    execute {
        // ============================================================
        // Part 1: Inject missing MIME types into the MIME map
        // ============================================================
        MimeMapInitFingerprint.method.apply {
            val unmodifiableMapIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("Collections;->unmodifiableMap")
            }

            if (unmodifiableMapIndex == -1) {
                throw PatchException(
                    "Could not find Collections.unmodifiableMap() call in MIME map initializer. " +
                        "The APK version may not be supported."
                )
            }

            val anyPutIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("AbstractMap;->put")
            }

            if (anyPutIndex == -1) {
                throw PatchException("Could not find any AbstractMap.put() call in MIME map initializer.")
            }

            val mapRegister = getInstruction<FiveRegisterInstruction>(anyPutIndex).registerC
            val keyReg = findFreeRegister(unmodifiableMapIndex, mapRegister)
            val valReg = findFreeRegister(unmodifiableMapIndex, mapRegister, keyReg)

            val additionalMimeTypes = listOf(
                "md" to "text/markdown",
                "markdown" to "text/markdown",
                "yaml" to "text/yaml",
                "yml" to "text/yaml",
                "toml" to "application/toml",
                "cfg" to "text/plain",
                "ps1" to "text/plain",
                "go" to "text/plain",
                "tsv" to "text/tab-separated-values",
            )

            val mimePutInstructions = additionalMimeTypes.flatMap { (ext, mime) ->
                listOf(
                    "const-string v$keyReg, \"$ext\"",
                    "const-string v$valReg, \"$mime\"",
                    "invoke-virtual {v$mapRegister, v$keyReg, v$valReg}, Ljava/util/AbstractMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                )
            }.joinToString("\n")

            addInstructions(
                unmodifiableMapIndex,
                mimePutInstructions,
            )
        }

        // ============================================================
        // Part 2: Add "Open With Default" section to "Open With" dialog
        // ============================================================
        DialogPopulatorFingerprint.method.apply {
            // Find the injection point: the line where d2 is set to null
            // after the header is built, and the header LinearLayout is added to the main layout.
            //
            // In the smali, this is:
            //   iput-object v1, v0, Lhf/y0;->d2:Lgh/g;   (d2 = null, v1 = null)
            //   invoke-virtual {v4, v8}, Landroid/view/ViewGroup;->addView(...)V   (add header)
            //   iget-object v1, v2, Lqe/a;->b:Ljava/util/List;   ← WE INJECT BEFORE THIS
            //
            // At this point:
            //   v0 = Lhf/y0; (the dialog)
            //   v4 = Landroid/widget/LinearLayout; (main content layout i)
            //   v5 = Resources
            //   v6 = Context
            //   v7 = Lqe/d; (resolver data)
            //   v8 = just used for header LinearLayout, now free
            //   v1 = null (just set to null for d2)

            val qeAFieldBIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.IGET_OBJECT &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("Lqe/a;->b:Ljava/util/List;")
            }

            if (qeAFieldBIndex == -1) {
                throw PatchException(
                    "Could not find Lqe/a;->b access in dialog populator. " +
                        "The APK version may not be supported."
                )
            }

            // We inject right before the first qe/a field access.
            // At this point v0 = y0 (dialog), and we can safely use v1, v8
            // (they were just set/used and will be overwritten by subsequent code).
            //
            // The injection adds:
            // 1. Section header: y0.f("Open With Default")
            // 2. Click listener: new OpenWithDefaultClickListener(y0)
            // 3. Grid item button: y0.e("*/*", null_icon, null, clickListener)
            //
            // We use the extension class OpenWithDefaultClickListener which is
            // compiled into the extension .mpe and available at runtime.
            // It implements View.OnClickListener and on click:
            //   - Sets y0.e2 = wildcard MIME string
            //   - Calls y0.i() to re-resolve with the new MIME type

            val EXTENSION_CLASS = "Lapp/morphe/extension/fxexplorer/OpenWithDefaultClickListener;"

            // Register usage at injection point (before iget-object v1, v2, Lqe/a;->b):
            // v0 = Lhf/y0; (the dialog) — MUST preserve
            // v1 = null (just set for d2) — safe to overwrite
            // v8 = header LinearLayout (just used for addView) — safe to overwrite
            //
            // Strategy: We need 5 registers for the invoke-virtual call to y0.e().
            // The method signature is: e(CharSequence, Drawable, qe/b, OnClickListener)V
            // We need: v0=y0, v1=label, v2=null(drawable), v3=null(qe/b), v4=listener
            //
            // But v2 is LIVE (holds qe/a) — we must save and restore it.
            // v3 might be live from the e2!=null path — we must save and restore it.
            // v4 is LIVE (holds LinearLayout) — we must save and restore it.
            //
            // Simpler approach: Use invoke-virtual/range which allows us to specify
            // non-consecutive register ranges. But it actually requires consecutive regs.
            //
            // Best approach: Use registers that are truly free at this point.
            // v1 and v8 are safe. We need 3 more for the 5-arg invoke.
            // Save v2, v3, v4 to higher registers (v21, v22 are free), use v1-v4,
            // then restore v2, v3, v4 after the call.

            addInstructions(
                qeAFieldBIndex,
                """
                    # Save live registers that we'll temporarily use
                    move-object/from16 v21, v2
                    move-object/from16 v22, v4

                    # Add "Open With Default" section header
                    const-string v1, "Open With Default"
                    invoke-virtual {v0, v1}, Lhf/y0;->f(Ljava/lang/String;)V

                    # Prepare arguments for y0.e(label, null_icon, null_tag, clickListener)
                    # v0 = y0 (already set)
                    # v1 = label string
                    const-string v1, "*/*"
                    # v2 = null (Drawable)
                    const/4 v2, 0x0
                    # v3 = null (qe/b tag)
                    const/4 v3, 0x0
                    # v4 = click listener
                    new-instance v4, $EXTENSION_CLASS
                    invoke-direct {v4, v0}, $EXTENSION_CLASS-><init>(Lhf/y0;)V

                    # Call y0.e(v1=CharSequence, v2=Drawable, v3=qe/b, v4=OnClickListener)
                    invoke-virtual {v0, v1, v2, v3, v4}, Lhf/y0;->e(Ljava/lang/CharSequence;Landroid/graphics/drawable/Drawable;Lqe/b;Landroid/view/View${'$'}OnClickListener;)V

                    # Restore saved registers
                    move-object/from16 v2, v21
                    move-object/from16 v4, v22
                """,
            )
        }
    }
}
