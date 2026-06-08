/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.interaction.filter

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.packagename.changePackageNamePatch
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import app.morphe.patches.fxexplorer.shared.Constants.ORIGINAL_SIGNATURE_HEX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/fxexplorer/FilterCache;"

/**
 * Patch to implement per-directory filter persistence for FX Explorer.
 *
 * Problem:
 * 1. When navigating into a subdirectory, the active filter from the parent
 *    directory remains active, filtering items in the subdirectory too.
 * 2. When returning to the parent directory, the filter was cleared entirely
 *    by V0(), losing the filter state.
 *
 * Desired behavior:
 * - /storage/download (filter "folder_1" active) → shows folder_1, folder_13
 * - Navigate INTO /storage/download/folder_1 → filter OFF (show all items)
 * - Navigate BACK to /storage/download → filter "folder_1" RESTORED
 * - Open a file (image) in /storage/download → return → filter still active
 *
 * Solution:
 * Uses a FilterCache extension class (HashMap<String, String>) to store
 * filter text keyed by directory path, plus a lastPath tracker.
 *
 * When L0() (directory refresh) runs:
 * 1. BEFORE V0(): Save current filter (g2) for the PREVIOUS directory (lastPath).
 *    This correctly associates the filter with the old directory, because by the
 *    time L0() runs, the fragment has already switched to the new directory.
 *    Also save the current path as lastPath for the next call.
 * 2. V0() runs normally (clears filter for new directory).
 * 3. AFTER V0(): Check if the current directory has a cached filter and restore it.
 *    Restoration uses W0() + EditText.setText() to ensure both the filter logic
 *    and the visible search text are updated correctly.
 *
 * Key insight about W0() and EditText:
 * W0(filterText, null) applies the filter but clears the EditText when the
 * "initialized" flag (b2) is false (which it is after V0() resets it).
 * The fix: after W0(), call EditText.setText(filterText) to show the text.
 * This triggers the TextWatcher → W0(text, o2.f) which is harmless since
 * the adapter filter is already applied (j() early-exits).
 *
 * Additionally, this patch:
 * - Changes the package name (default: nextapp.fx.morphe) so the patched app
 *   can be installed alongside the original.
 * - Spoofs the original signing certificate during license verification so the
 *   FX Plus License Key app (nextapp.fx.rk) is still recognized.
 */
@Suppress("unused")
val preserveFilterPatch = bytecodePatch(
    name = "Preserve filter on refresh",
    description = "Implements per-directory filter persistence. " +
        "When navigating into a subdirectory, the filter is cleared. " +
        "When returning to the parent directory, the previous filter is restored. " +
        "Also enables side-by-side installation.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    dependsOn(changePackageNamePatch)

    extendWith("extensions/fxexplorer.mpe")

    // Inline resource patch that changes the package name and updates all manifest
    // references so the patched app can be installed alongside the original.
    dependsOn(
        resourcePatch {
            execute {
                val fromPackage = "nextapp.fx"
                val toPackage = setOrGetFallbackPackageName("$fromPackage.morphe")

                val transformations = mapOf(
                    "package=\"$fromPackage\"" to "package=\"$toPackage\"",
                    "android:sharedUserId=\"$fromPackage\"" to "",
                    "android:authorities=\"$fromPackage." to "android:authorities=\"$toPackage.",
                    "$fromPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
                        "$toPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                    "android:name=\"$fromPackage.intent." to "android:name=\"$toPackage.intent.",
                    "android:scheme=\"$fromPackage\"" to "android:scheme=\"$toPackage\"",
                    "android:taskAffinity=\"$fromPackage." to "android:taskAffinity=\"$toPackage.",
                )

                val manifest = get("AndroidManifest.xml")
                manifest.writeText(
                    transformations.entries.fold(manifest.readText()) { acc, (from, to) ->
                        acc.replace(from, to)
                    },
                )
            }
        }
    )

    // Inline bytecode patch that spoofs the original signing certificate during
    // the FX Plus License Key verification.
    dependsOn(
        bytecodePatch {
            execute {
                LicenseCheckFingerprint.method.apply {
                    var getPackageInfoCount = 0
                    val selfPkgInfoIndex = implementation!!.instructions.indexOfFirst {
                        if (it.opcode == Opcode.INVOKE_VIRTUAL &&
                            it is ReferenceInstruction &&
                            it.reference.toString().contains("PackageManager;->getPackageInfo")
                        ) {
                            getPackageInfoCount++
                            getPackageInfoCount == 2
                        } else {
                            false
                        }
                    }

                    if (selfPkgInfoIndex == -1) {
                        throw PatchException(
                            "Could not find the self getPackageInfo call in license check method. " +
                                "The APK version may not be supported."
                        )
                    }

                    val moveResultIndex = selfPkgInfoIndex + 1
                    val moveResultInstruction = getInstruction<OneRegisterInstruction>(moveResultIndex)
                    val pkgInfoRegister = moveResultInstruction.registerA

                    addInstructions(
                        moveResultIndex + 1,
                        """
                            new-instance v2, Landroid/content/pm/Signature;
                            const-string v3, "$ORIGINAL_SIGNATURE_HEX"
                            invoke-direct {v2, v3}, Landroid/content/pm/Signature;-><init>(Ljava/lang/String;)V
                            const/4 v3, 0x1
                            new-array v3, v3, [Landroid/content/pm/Signature;
                            const/4 v4, 0x0
                            aput-object v2, v3, v4
                            iput-object v3, v$pkgInfoRegister, Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;
                        """
                    )
                }
            }
        }
    )

    // Main bytecode patch: per-directory filter persistence
    execute {
        DirectoryRefreshFingerprint.method.apply {
            // === MODIFICATION 1: Before V0() — save filter for old directory ===
            //
            // Original L0() at v0CallIndex:
            //   invoke-virtual {p0}, Llf/s;->V0()V   // unconditional clear
            //
            // We replace this with:
            //   invoke-static {}, FilterCache->getLastPath()       // previous dir path
            //   move-result-object v0
            //   iget-object v1, p0, Llf/s;->g2                    // current filter text
            //   invoke-static {v0, v1}, FilterCache->saveFilter    // save for old dir
            //   invoke-virtual {p0}, Llf/s;->getPathText()         // current (new) path
            //   move-result-object v0
            //   invoke-static {v0}, FilterCache->setLastPath       // save for next call
            //   invoke-virtual {p0}, Llf/s;->V0()V                 // always clear
            //
            // Key: We use getLastPath() for the save key because by the time L0() runs,
            // the fragment has already switched directories. getPathText() returns the NEW
            // path, but g2 belongs to the OLD directory (lastPath).
            //
            // When g2 is null (no filter), saveFilter removes the cache entry for that path,
            // which correctly handles the case where the user manually cleared the filter.
            //
            // Injected: 8 instructions (no trailing nop needed — no labels in this block).
            // Original V0() shifts to v0CallIndex + 8.

            val v0CallIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("Llf/s;->V0()V")
            }

            if (v0CallIndex == -1) {
                throw PatchException(
                    "Could not find V0() call in directory refresh method. " +
                        "The APK version may not be supported."
                )
            }

            addInstructionsWithLabels(
                v0CallIndex,
                """
                    invoke-static {}, $EXTENSION_CLASS->getLastPath()Ljava/lang/String;
                    move-result-object v0
                    iget-object v1, p0, Llf/s;->g2:Ljava/lang/String;
                    invoke-static {v0, v1}, $EXTENSION_CLASS->saveFilter(Ljava/lang/String;Ljava/lang/String;)V
                    invoke-virtual {p0}, Llf/s;->getPathText()Ljava/lang/String;
                    move-result-object v0
                    invoke-static {v0}, $EXTENSION_CLASS->setLastPath(Ljava/lang/String;)V
                    invoke-virtual {p0}, Llf/s;->V0()V
                """
            )

            // Remove original V0() call (shifted by 8 injected instructions)
            removeInstruction(v0CallIndex + 8)

            // === MODIFICATION 2: After V0() — restore cached filter ===
            //
            // Injected right after the injected V0() (before original L0() code continues):
            //
            //   invoke-virtual {p0}, Llf/s;->getPathText()       // current dir path
            //   move-result-object v0
            //   invoke-static {v0}, FilterCache->getFilter        // check cache
            //   move-result-object v1                              // cached filter or null
            //   if-eqz v1, :no_cached_filter
            //   const/4 v0, 0x0                                    // null for hf/n callback
            //   invoke-virtual {p0, v1, v0}, Llf/s;->W0          // apply filter
            //   iget-object v0, p0, Llf/s;->o2                    // filter UI (Lhf/l0)
            //   iget-object v0, v0, Lhf/l0;->X1                   // EditText widget
            //   invoke-virtual {v0, v1}, EditText;->setText       // fix empty text bug
            //   :no_cached_filter
            //   nop
            //
            // Register safety: v0 and v1 are free — the next original instruction
            // (iget-object v0, v8, Llf/s;->a2) overwrites both.
            //
            // Why EditText.setText() is needed:
            // W0() has a "b2" (initialized) flag. When b2 is false (reset by V0()),
            // W0() clears the EditText to "". Setting b2=true before W0() would skip
            // the clear but also skip keyboard show + label update. Instead, we call
            // W0() normally (which applies the filter correctly) and then fix the
            // EditText text with setText(). The setText() triggers TextWatcher →
            // W0(text, o2.f) which is harmless (j() early-exits since B4 is already set).
            //
            // Injected: 10 instructions.

            addInstructionsWithLabels(
                v0CallIndex + 8,
                """
                    invoke-virtual {p0}, Llf/s;->getPathText()Ljava/lang/String;
                    move-result-object v0
                    invoke-static {v0}, $EXTENSION_CLASS->getFilter(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    if-eqz v1, :no_cached_filter
                    const/4 v0, 0x0
                    invoke-virtual {p0, v1, v0}, Llf/s;->W0(Ljava/lang/String;Lhf/n;)V
                    iget-object v0, p0, Llf/s;->o2:Lhf/l0;
                    iget-object v0, v0, Lhf/l0;->X1:Landroid/widget/EditText;
                    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setText(Ljava/lang/CharSequence;)V
                    :no_cached_filter
                    nop
                """
            )
        }
    }
}
