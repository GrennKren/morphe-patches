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
 *
 * IMPORTANT: The lf/b parameter (p2) is ALWAYS null from all callers —
 * both single-click "Open" and explicit "Open With" button. It CANNOT be
 * used to distinguish the two paths. Instead, we use stack trace inspection
 * via DefaultAppRegistry.shouldAutoOpen() to check if hf.b0 (the file-
 * opener class) is in the call stack. If it is, we arrived from the
 * single-click path and should try auto-open. If not, the user explicitly
 * requested the "Open With" dialog and we must show it.
 *
 * Part 5 — Redirect system dialog to FX's native "Open With" dialog:
 * Hooks p0.r(Context, kh/h, ResolveInfo) — the method that b0.a() calls
 * for video and audio files when their internal players are disabled.
 * Unlike image/text files which go through y0.j() (FX's native dialog),
 * video/audio files are routed through p0.r() which creates a system-level
 * "Open with" chooser (the dark/black themed dialog) via Llg/b; thread.
 *
 * This is the root cause of the bug: when clicking a video file, the user
 * sees the system's dark "Open with" dialog instead of FX Explorer's native
 * green "Open With" dialog. Image and text files work correctly because
 * b0.a() routes them through y0.j() directly.
 *
 * Solution: When p0.r() is called with null ResolveInfo (meaning "show all
 * apps"), redirect to y0.j() which shows FX's native dialog. When called
 * with a non-null ResolveInfo (meaning "launch this specific app"), continue
 * with the normal system dialog flow.
 *
 * This is safe because b0.a() already checks for internal video/audio players
 * BEFORE calling p0.r(), so the internal player functionality is preserved.
 */
@Suppress("unused")
val openExternallyPatch = bytecodePatch(
    name = "Open files externally",
    description = "Adds missing MIME types for .md, .yaml, .toml and other file extensions, " +
        "so files with these extensions can be opened directly with external applications " +
        "instead of showing the 'Open As' dialog. Also adds an 'Open With Default' section " +
        "to the 'Open With' dialog with a wildcard MIME type button that remembers your " +
        "app choice per file extension, and a 'Clear Default' button to reset stored defaults. " +
        "Fixes video and audio files showing the system's dark 'Open with' dialog instead of " +
        "FX Explorer's native green 'Open With' dialog when internal players are disabled.",
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
        // Delegates ALL UI construction to our Java extension
        // method OpenWithDefaultClickListener.addSection(y0), which:
        // 1. Creates a proper gray uppercase section header using y0.b(resId)
        //    (matching the style of "OPEN WITH FX", "OPEN WITH APPLICATION")
        // 2. Modifies the header text to "OPEN WITH DEFAULT"
        // 3. Adds a */* grid item with a custom teal icon
        // 4. Adds a "Clear Default" button if a default is stored
        //
        // This is simpler in smali (just one invoke-static) and more
        // maintainable (all UI logic is in Java, not smali).
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

                    # Delegate section creation to Java extension
                    # This handles: header, icon, */* button, and Clear Default button
                    invoke-static {v0}, $EXTENSION_CLASS->addSection(Lhf/y0;)V

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
        // Uses stack trace inspection via DefaultAppRegistry.shouldAutoOpen()
        // to check if hf.b0 is in the call stack. If it is, we arrived from
        // the single-click path and should try auto-open. If not, the user
        // explicitly requested the "Open With" dialog and we must show it.
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
                    # v1 = temp register (available: was set to const/4 v1, 1 at index 1)
                    #
                    # CRITICAL: We CANNOT use the lf/b parameter (p2) to distinguish between
                    # single-click "Open" and explicit "Open With" button because ALL callers
                    # pass null for lf/b. Instead, we use stack trace inspection via
                    # DefaultAppRegistry.shouldAutoOpen() to check if hf.b0 is in the call
                    # stack (present only for single-click path).

                    # Check if auto-open should be attempted (stack trace inspection)
                    invoke-static {}, $REGISTRY_CLASS->shouldAutoOpen()Z
                    move-result v1

                    # If shouldAutoOpen() returned false, skip auto-open and show dialog
                    if-eqz v1, :skip_auto_open

                    # shouldAutoOpen() returned true — we're in the single-click "Open" path
                    # Call tryOpenWithDefault(y0 dialog) — this uses the dialog's
                    # resolver (qe/d) which has the correct File/URI for the file.
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

                    :skip_auto_open
                    # shouldAutoOpen() returned false — explicit "Open With" request,
                    # always show dialog
                    nop
                """,
            )
        }

        // ============================================================
        // Part 5: Redirect system dialog to FX's native "Open With" dialog
        // ============================================================
        // Hook p0.r(Context, kh/h, ResolveInfo) — the method that b0.a() calls
        // for video and audio files when their internal players are disabled.
        //
        // PROBLEM: When b0.a() handles a video/audio file with internal player
        // disabled, it creates an ACTION_VIEW intent, queries the package manager
        // for apps, and if apps are found, calls p0.r(context, file, null).
        // This p0.r() method creates a Llg/b; thread that shows the SYSTEM-LEVEL
        // "Open with" chooser (dark/black themed), bypassing FX's native dialog.
        //
        // For image/text files, b0.a() routes through y0.j() which shows FX's
        // native green "Open With" dialog — this works correctly.
        //
        // FIX: When p0.r() is called with null ResolveInfo, redirect to y0.j()
        // instead. This ensures video/audio files show FX's native dialog,
        // which also allows the "Open With Default" feature (Part 2-4) to work.
        //
        // When p2 (ResolveInfo) is NOT null, the method was called with a specific
        // app to launch, so we continue with the normal system dialog flow.
        //
        // Register layout: p0=Context, p1=kh/h (extends kh/e), p2=ResolveInfo
        // v0 is free at method start (first local register)
        SystemDialogRedirectFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    # ===== Part 5: Redirect system dialog to FX's native "Open With" dialog =====
                    # p0 = Context, p1 = kh/h (file item, extends kh/e), p2 = ResolveInfo

                    # Check if ResolveInfo (p2) is null — null means "show all apps"
                    if-nez p2, :continue_system_dialog

                    # ResolveInfo is null → redirect to FX's native "Open With" dialog
                    # This fixes video/audio files showing the dark system chooser
                    # instead of FX Explorer's native green "Open With" dialog.
                    #
                    # kh/h extends kh/e, so passing p1 (kh/h) to y0.j(kh/e) is valid.
                    # The third parameter (lf/b) is always null from all callers.
                    const/4 v0, 0
                    invoke-static {p0, p1, v0}, Lhf/y0;->j(Landroid/content/Context; Lkh/e; Llf/b;)V
                    return-void

                    :continue_system_dialog
                    # ResolveInfo is not null → continue with normal system dialog flow
                    # (a specific app was chosen, show system dialog with that app)
                    nop
                """,
            )
        }
    }
}
