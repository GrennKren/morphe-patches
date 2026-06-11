/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.openexternally

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
 * wildcard MIME type button. When clicked, it checks for a stored default app
 * for the current file's extension. If a default is found, the file opens
 * directly without any dialog. If no default is stored, the dialog re-resolves
 * with the wildcard MIME type to show ALL apps, and the selected app is saved
 * as the default for that extension.
 *
 * Part 3 — Intercept app launches to save defaults:
 * Hooks y0.h(ResolveInfo, Uri, int) — the method that launches an app.
 * When the "selectingDefault" flag is set (after clicking the wildcard button),
 * saves the launched app as the default for the current file's extension.
 * This injection is simple (one invoke-static) and uses no labels or branches,
 * making it safe and crash-proof.
 *
 * Part 4 — Intercept dialog creation to check for stored defaults:
 * Hooks y0.j(Context, kh/e, lf/b) — the static method that creates the
 * "Open With" dialog. Before creating the dialog, checks DefaultAppRegistry
 * for a stored default app for the file's extension. If found, opens the file
 * directly with that app, skipping the dialog entirely.
 * Uses addInstructionsWithLabels for label support (NOT addInstructions).
 */
@Suppress("unused")
val openExternallyPatch = bytecodePatch(
    name = "Open files externally",
    description = "Adds missing MIME types for .md, .yaml, .toml and other file extensions, " +
        "so files with these extensions can be opened directly with external applications " +
        "instead of showing the 'Open As' dialog. Also adds an 'Open With Default' section " +
        "to the 'Open With' dialog with a wildcard MIME type button that remembers your " +
        "app choice per file extension.",
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

            val EXTENSION_CLASS = "Lapp/morphe/extension/fxexplorer/OpenWithDefaultClickListener;"

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

        // ============================================================
        // Part 3: Intercept app launches to save defaults
        // ============================================================
        // Hook y0.h(ResolveInfo, Uri, int) — when an app is launched from
        // the "Open With" dialog, check if selectingDefault flag is set.
        // If true, save the launched app as the default for the file's extension.
        //
        // This is a MINIMAL injection: just one invoke-static call at method start.
        // No labels, no branches, no register conflicts.
        // p0 = this (y0 dialog), p1 = ResolveInfo
        // The extension method catches all exceptions internally.
        ExternalLaunchFingerprint.method.apply {
            val REGISTRY_CLASS = "Lapp/morphe/extension/fxexplorer/DefaultAppRegistry;"

            addInstructions(
                0,
                """
                    # Check if we should save this app as default, and save if so
                    # This is safe: DefaultAppRegistry.onAppLaunchedFromDialog catches all exceptions
                    invoke-static {p0, p1}, $REGISTRY_CLASS->onAppLaunchedFromDialog(Lhf/y0;Landroid/content/pm/ResolveInfo;)V
                """,
            )
        }

        // ============================================================
        // Part 4: Intercept "Open With" dialog creation to check for stored defaults
        // ============================================================
        // Hook y0.j(Context, kh/e, lf/b) — AFTER the dialog constructor but
        // BEFORE the show-runnable is posted. At this point, the y0 dialog
        // instance has been created (v0) with its resolver (qe/d) fully
        // initialized, giving us access to the correct File/URI.
        //
        // CRITICAL DESIGN CHANGE (v3): Previous versions hooked at index 0
        // (before dialog creation) and tried to construct the intent from
        // scratch using tryOpenDirectly(filename, pathStr, ctx). This ALWAYS
        // FAILED because:
        // - hh/f.toString() does NOT produce a valid filesystem path
        // - FileProvider.a(ctx, file) doesn't work for all file types;
        //   the app uses FileProvider.b(ctx, file, type) with extra params
        // - The resolver (qe/d) which has the proper File/URI was not
        //   available before the dialog was created
        //
        // The new approach hooks at index 3 (after the y0 constructor at
        // index 2). The constructor creates the dialog AND the resolver
        // (qe/d), which properly handles:
        // - Local files: qe/d.f = File (with correct path from hc/i.z())
        // - Cloud files: qe/d.h = Uri
        // - MIME type: qe/d.i = String (from fileItem.w() or inferred)
        //
        // By calling tryOpenWithDefault(y0 dialog), we reuse the SAME
        // logic that works when the user clicks the wildcard button in the
        // dialog — proven to work by user testing.
        //
        // If a default is found and the file is opened, we dismiss the
        // dialog and return-void from y0.j(), which prevents the show-
        // runnable (s0) from being created/posted. The background resolver
        // thread (started by y0.i() in the constructor) will complete
        // harmlessly — it populates a dialog that's never shown.
        //
        // Register layout at index 3: v0 = y0 dialog, v1 = 0x1 (unused after)
        // p0, p1, p2 still hold original params (but not needed for our call)
        //
        // IMPORTANT: Uses addInstructionsWithLabels (NOT addInstructions)
        // because this injection contains labels (:goto_show_dialog).
        FileOpenDialogFingerprint.method.apply {
            val REGISTRY_CLASS = "Lapp/morphe/extension/fxexplorer/DefaultAppRegistry;"

            // Find the index right after the y0 constructor call
            val constructorIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_DIRECT &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("Lhf/y0;-><init>")
            }

            if (constructorIndex == -1) {
                throw PatchException(
                    "Could not find y0 constructor call in y0.j(). " +
                        "The APK version may not be supported."
                )
            }

            // Inject AFTER the constructor (at constructorIndex + 1)
            // At this point, v0 = y0 dialog with fully initialized resolver
            val injectIndex = constructorIndex + 1

            addInstructionsWithLabels(
                injectIndex,
                """
                    # ===== Part 4: Try to open with stored default before showing dialog =====
                    # v0 = y0 dialog instance (just constructed, resolver fully initialized)
                    # v1 = temp register

                    # Call tryOpenWithDefault(y0 dialog) — this uses the dialog's
                    # resolver (qe/d) which has the correct File/URI for the file.
                    # This is the SAME method that works when clicking the wildcard button.
                    invoke-static {v0}, $REGISTRY_CLASS->tryOpenWithDefault(Lhf/y0;)Z
                    move-result v1

                    if-eqz v1, :goto_show_dialog

                    # Default app found and launched — dismiss dialog and return
                    # (prevents s0 show-runnable from being created/posted)
                    invoke-virtual {v0}, Lhf/y0;->dismiss()V
                    return-void

                    :goto_show_dialog
                    # No default found — continue with normal dialog show flow
                    nop
                """,
            )
        }
    }
}
