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
 * CRITICAL INSIGHT about getPathText() vs getDirectory():
 * - getPathText() reads from field i2 (Lkh/d), which is updated ASYNCHRONOUSLY
 *   by the directory loading task (Llf/i). When L0() runs, i2 still holds
 *   the PREVIOUSLY LOADED directory — NOT the new one the user navigated to.
 * - getDirectory() reads from the content model (getContentModel().Y), which
 *   IS updated BEFORE L0() runs. So getDirectory() correctly returns the NEW
 *   directory path during L0() execution.
 *
 * This means:
 * - getPathText() = OLD directory path (for saving the current filter)
 * - getDirectory() = NEW directory path (for cache lookup and setLastPath)
 *
 * When L0() (directory refresh) runs:
 * 1. BEFORE V0(): Save current filter (g2) for the OLD directory.
 *    getPathText() returns the old path because i2 hasn't been updated yet.
 *    Then get the NEW directory from getDirectory() and store it as lastPath.
 * 2. V0() runs normally (clears filter for new directory).
 * 3. AFTER V0(): Use getLastPath() (which now holds the NEW directory path)
 *    to check the cache and restore the filter if found.
 *
 * Why we need getDirectory() conversion to String:
 * getPathText() already does this conversion internally (checking if the
 * directory is Lkh/d0; → call d(), otherwise → getPath().n(context)),
 * but it uses i2 which is stale. We replicate the same conversion logic
 * starting from getDirectory() instead.
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
            //   Step A: Save current filter (g2) for the OLD directory.
            //     getPathText() uses i2 which is STALE (points to old directory),
            //     so it gives us the correct key for saving the current filter.
            //   Step B: Get NEW directory path from getDirectory() (content model)
            //     and convert it to a String. This replicates the same logic as
            //     getPathText() but starts from getDirectory() instead of i2.
            //     Store as lastPath so Modification 2 can look it up.
            //   Step C: Call V0() to clear the filter.
            //
            // Conversion logic (same as getPathText() but from getDirectory()):
            //   if (directory instanceof Lkh/d0;) → call d() for String
            //   else → call getPath() → Lhh/f; → f.n(context) for String
            //
            // Null check: if getDirectory() returns null, skip setLastPath but
            // still call V0(). L0() itself returns early when getDirectory() is
            // null, so this is an edge case that shouldn't normally happen.
            //
            // Injected: 21 instructions.
            // Original V0() shifts to v0CallIndex + 21.

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
                    # Step A: Save filter for OLD directory (i2 = old path)
                    invoke-virtual {p0}, Llf/s;->getPathText()Ljava/lang/String;
                    move-result-object v0
                    iget-object v1, p0, Llf/s;->g2:Ljava/lang/String;
                    invoke-static {v0, v1}, $EXTENSION_CLASS->saveFilter(Ljava/lang/String;Ljava/lang/String;)V

                    # Step B: Get NEW directory path from content model
                    invoke-virtual {p0}, Llf/s;->getDirectory()Lkh/d;
                    move-result-object v0
                    if-nez v0, :dir_not_null

                    # getDirectory() is null — skip setLastPath, just clear filter
                    goto :do_clear

                    :dir_not_null
                    # Convert Lkh/d; to path string (same logic as getPathText)
                    instance-of v1, v0, Lkh/d0;
                    if-eqz v1, :not_d0
                    check-cast v0, Lkh/d0;
                    invoke-interface {v0}, Lkh/d0;->d()Ljava/lang/String;
                    move-result-object v0
                    goto :got_new_path

                    :not_d0
                    invoke-interface {v0}, Lkh/j;->getPath()Lhh/f;
                    move-result-object v0
                    iget-object v1, p0, Lnextapp/fx/ui/content/u;->activity:Lnextapp/fx/ui/content/k;
                    invoke-virtual {v0, v1}, Lhh/f;->n(Landroid/content/Context;)Ljava/lang/String;
                    move-result-object v0

                    :got_new_path
                    invoke-static {v0}, $EXTENSION_CLASS->setLastPath(Ljava/lang/String;)V

                    # Step C: Always clear filter
                    :do_clear
                    invoke-virtual {p0}, Llf/s;->V0()V
                """
            )

            // Remove original V0() call (shifted by 21 injected instructions)
            removeInstruction(v0CallIndex + 21)

            // === MODIFICATION 2: After V0() — restore cached filter ===
            //
            // Uses getLastPath() which now holds the NEW directory path
            // (set in Modification 1 Step B via getDirectory()).
            //
            // Why getLastPath() instead of getPathText():
            // getPathText() uses i2 which is still the OLD directory at this point
            // (i2 is only updated asynchronously after the directory load completes).
            // getLastPath() was set from getDirectory() which correctly returns
            // the NEW directory path.
            //
            // Injected: 11 instructions.

            addInstructionsWithLabels(
                v0CallIndex + 21,
                """
                    invoke-static {}, $EXTENSION_CLASS->getLastPath()Ljava/lang/String;
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
