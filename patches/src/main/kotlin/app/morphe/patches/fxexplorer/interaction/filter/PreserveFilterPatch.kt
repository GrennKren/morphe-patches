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
 * Patch to implement per-directory, per-tab filter persistence for FX Explorer.
 *
 * Problem:
 * 1. When navigating into a subdirectory, the active filter from the parent
 *    directory remains active, filtering items in the subdirectory too.
 * 2. When returning to the parent directory, the filter was cleared entirely
 *    by V0(), losing the filter state.
 * 3. Different tabs viewing the same directory should have independent filter state.
 * 4. Filter state should survive app restart.
 *
 * Solution:
 * Uses a FilterCache extension class with:
 * - In-memory HashMap keyed by composite key (tabHash + ":" + pathString) for
 *   per-tab, per-directory isolation during runtime.
 * - SharedPreferences with path-only keys for cross-restart persistence.
 * - Per-tab lastPath tracking via HashMap<Integer, String> (tabHash → lastPath).
 *
 * CRITICAL INSIGHT about getPathText() vs getDirectory():
 * - getPathText() reads from field i2 (Lkh/d), which is updated ASYNCHRONOUSLY
 *   by the directory loading task (Llf/i). When L0() runs, i2 still holds
 *   the PREVIOUSLY LOADED directory — NOT the new one the user navigated to.
 * - getDirectory() reads from the content model (getContentModel().Y), which
 *   IS updated BEFORE L0() runs. So getDirectory() correctly returns the NEW
 *   directory path during L0() execution.
 *
 * Cache key consistency:
 * Both save and restore use the SAME composite key format built from
 * getDirectory() conversion + tabHash. The save key comes from getLastPath(tabHash)
 * which was set from getDirectory() conversion in the previous L0() call.
 * This guarantees no key mismatch between save and restore operations.
 *
 * Tab awareness:
 * - Each tab (WindowModel instance, class c1) is identified by
 *   System.identityHashCode(getWindowModel()).
 * - This hash is stable within a JVM session but changes on app restart.
 * - SharedPreferences fallback (path-only key) handles cross-restart persistence.
 *
 * When L0() (directory refresh) runs:
 * 1. Init FilterCache with Context (no-op after first call).
 * 2. Get tabHash = identityHashCode(getWindowModel()).
 * 3. BEFORE V0(): Save current filter (g2) for the previous directory of this tab.
 *    saveFilter(tabHash, getLastPath(tabHash), g2)
 *    The key is from lastPath which was set by getDirectory() conversion in the
 *    previous L0() — same format as the restore lookup.
 * 4. Get NEW directory path from getDirectory() and convert to String
 *    (replicating getPathText() logic: instanceof Lkh/d0; → d(),
 *     else → getPath().n(context)).
 *    Store as setLastPath(tabHash, newPath) for this tab.
 * 5. V0() runs normally (clears filter for new directory).
 * 6. AFTER V0(): Check cache for the new directory of this tab.
 *    getFilter(tabHash, getLastPath(tabHash)) → restore if found.
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
    description = "Implements per-directory, per-tab filter persistence. " +
        "When navigating into a subdirectory, the filter is cleared. " +
        "When returning to the parent directory, the previous filter is restored. " +
        "Filter state is isolated between tabs and persists across app restarts. " +
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

    // Main bytecode patch: per-directory, per-tab filter persistence
    execute {
        DirectoryRefreshFingerprint.method.apply {
            // === MODIFICATION 1: Before V0() — save filter + set new lastPath ===
            //
            // Original L0() at v0CallIndex:
            //   invoke-virtual {p0}, Llf/s;->V0()V   // unconditional clear
            //
            // We replace this with:
            //   Step A: Init FilterCache with Context (no-op after first call).
            //   Step B: Get tabHash = identityHashCode(getWindowModel()).
            //   Step C: Save current filter (g2) for the previous directory of this tab.
            //     saveFilter(tabHash, getLastPath(tabHash), g2)
            //     The key is from lastPath which was set by getDirectory() conversion
            //     in the previous L0() call — same format as the restore lookup.
            //   Step D: Get NEW directory path from getDirectory() (content model)
            //     and convert it to a String. Store as lastPath for this tab.
            //   Step E: Call V0() to clear the filter.
            //
            // Conversion logic (same as getPathText() but from getDirectory()):
            //   if (directory instanceof Lkh/d0;) → call d() for String
            //   else → call getPath() → Lhh/f; → f.n(context) for String
            //
            // Null check: if getDirectory() returns null, skip setLastPath but
            // still call V0(). L0() itself returns early when getDirectory() is
            // null, so this is an edge case that shouldn't normally happen.
            //
            // Register usage: v0 (tabHash int, reused), v1 (path string), v2 (temp)
            // All are safe — subsequent original code overwrites v0, v1 immediately.
            //
            // Injected: 27 instructions.
            // Original V0() shifts to v0CallIndex + 27.

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
                    # Step A: Init FilterCache with Context (no-op after first call)
                    iget-object v0, p0, Lnextapp/fx/ui/content/u;->activity:Lnextapp/fx/ui/content/k;
                    invoke-static {v0}, $EXTENSION_CLASS->init(Landroid/content/Context;)V

                    # Step B: Get tabHash = identityHashCode(getWindowModel())
                    invoke-virtual {p0}, Lnextapp/fx/ui/content/u;->getWindowModel()Lnextapp/fx/ui/content/c1;
                    move-result-object v0
                    invoke-static {v0}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                    move-result v0

                    # Step C: saveFilter(tabHash, getLastPath(tabHash), g2)
                    invoke-static {v0}, $EXTENSION_CLASS->getLastPath(I)Ljava/lang/String;
                    move-result-object v1
                    iget-object v2, p0, Llf/s;->g2:Ljava/lang/String;
                    invoke-static {v0, v1, v2}, $EXTENSION_CLASS->saveFilter(ILjava/lang/String;Ljava/lang/String;)V

                    # Step D: Get NEW directory path from content model
                    invoke-virtual {p0}, Llf/s;->getDirectory()Lkh/d;
                    move-result-object v1
                    if-nez v1, :dir_not_null

                    # getDirectory() is null — skip setLastPath, just clear filter
                    goto :do_clear

                    :dir_not_null
                    # Convert Lkh/d; to path string (same logic as getPathText)
                    instance-of v2, v1, Lkh/d0;
                    if-eqz v2, :not_d0
                    check-cast v1, Lkh/d0;
                    invoke-interface {v1}, Lkh/d0;->d()Ljava/lang/String;
                    move-result-object v1
                    goto :got_new_path

                    :not_d0
                    invoke-interface {v1}, Lkh/j;->getPath()Lhh/f;
                    move-result-object v1
                    iget-object v2, p0, Lnextapp/fx/ui/content/u;->activity:Lnextapp/fx/ui/content/k;
                    invoke-virtual {v1, v2}, Lhh/f;->n(Landroid/content/Context;)Ljava/lang/String;
                    move-result-object v1

                    :got_new_path
                    invoke-static {v0, v1}, $EXTENSION_CLASS->setLastPath(ILjava/lang/String;)V

                    # Step E: Always clear filter
                    :do_clear
                    invoke-virtual {p0}, Llf/s;->V0()V
                """
            )

            // Remove original V0() call (shifted by 27 injected instructions)
            removeInstruction(v0CallIndex + 27)

            // === MODIFICATION 2: After V0() — restore cached filter ===
            //
            // Uses getLastPath(tabHash) which holds the NEW directory path for this tab
            // (set in Modification 1 Step D via getDirectory() conversion).
            //
            // getFilter checks in-memory first (tab-specific composite key),
            // then falls back to SharedPreferences (path-only key) for persistence.
            //
            // Injected: 15 instructions.

            addInstructionsWithLabels(
                v0CallIndex + 27,
                """
                    # Get tabHash for current tab
                    invoke-virtual {p0}, Lnextapp/fx/ui/content/u;->getWindowModel()Lnextapp/fx/ui/content/c1;
                    move-result-object v0
                    invoke-static {v0}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                    move-result v0

                    # Check cache for new directory: getFilter(tabHash, getLastPath(tabHash))
                    invoke-static {v0}, $EXTENSION_CLASS->getLastPath(I)Ljava/lang/String;
                    move-result-object v1
                    invoke-static {v0, v1}, $EXTENSION_CLASS->getFilter(ILjava/lang/String;)Ljava/lang/String;
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
