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

/**
 * Patch to preserve the active filter when returning from viewing a file.
 *
 * Problem:
 * When a user has an active filter in the file browser and opens a file
 * (image, video, text), upon returning, the filter is completely cleared
 * and the browser shows all files unfiltered. This is annoying because the
 * user has to re-enter the filter text every time they view a file.
 *
 * Root cause:
 * The directory refresh method (L0 in lf/s) unconditionally calls V0()
 * which clears the filter state. When the user returns from viewing a file,
 * onResume() triggers R0() -> L0() -> V0(), clearing the filter.
 *
 * Solution:
 * Modify L0() to skip calling V0() when a filter is active (g2 != null).
 * When V0() is skipped:
 * - The filter bar stays visible with the current text
 * - The filter text (g2) is preserved
 * - The adapter's internal filter string (B4 in jf/f) is preserved
 * - When new directory data is loaded, A0() in jf/f reads B4 and
 *   automatically re-applies the filter to the new data
 *
 * This ensures the filter persists through directory refreshes, whether
 * triggered by returning from a file view, pull-to-refresh, or any other
 * refresh mechanism.
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
    description = "Preserves the active file filter when returning from viewing a file or when the directory refreshes. " +
        "Normally, the filter is cleared every time the directory refreshes, which is inconvenient " +
        "when opening files to view them and then returning to the filtered list.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    dependsOn(changePackageNamePatch)

    // Inline resource patch that changes the package name and updates all manifest
    // references so the patched app can be installed alongside the original.
    // Uses setOrGetFallbackPackageName to coordinate with the universal
    // "Change package name" patch: if the user sets a custom package name,
    // that name is used; otherwise the default "nextapp.fx.morphe" is applied.
    dependsOn(
        resourcePatch {
            execute {
                val fromPackage = "nextapp.fx"
                val toPackage = setOrGetFallbackPackageName("$fromPackage.morphe")

                val transformations = mapOf(
                    // Change package attribute
                    "package=\"$fromPackage\"" to "package=\"$toPackage\"",

                    // Remove sharedUserId — ties the app to a specific signing certificate.
                    // Must be removed because the patched APK uses a different certificate.
                    "android:sharedUserId=\"$fromPackage\"" to "",

                    // Update provider authorities
                    "android:authorities=\"$fromPackage." to "android:authorities=\"$toPackage.",

                    // Update permissions
                    "$fromPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
                        "$toPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",

                    // Update intent action names
                    "android:name=\"$fromPackage.intent." to "android:name=\"$toPackage.intent.",

                    // Update scheme references
                    "android:scheme=\"$fromPackage\"" to "android:scheme=\"$toPackage\"",

                    // Update taskAffinity references
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
    // the FX Plus License Key verification. The license check (lh/n.l) compares
    // the signature of nextapp.fx.rk with the app's own signature. Since the
    // patched app is signed with a different certificate, the comparison fails.
    // This patch replaces the self-signature with the original one so the
    // comparison succeeds and the legitimate license key is recognized.
    dependsOn(
        bytecodePatch {
            execute {
                LicenseCheckFingerprint.method.apply {
                    // Find the second getPackageInfo call — the one that queries the
                    // app's own package name (for signature comparison against the key app).
                    //
                    // The pattern in lh/n.l() is:
                    //   const-string v0, "nextapp.fx.rk"
                    //   invoke-virtual {v3, v0, v4}, PackageManager;->getPackageInfo(...)  // key app
                    //   move-result-object v0
                    //   invoke-virtual {p0}, Context;->getPackageName()
                    //   move-result-object p0
                    //   invoke-virtual {v3, p0, v4}, PackageManager;->getPackageInfo(...)  // SELF ← target
                    //   move-result-object p0  ← p0 now holds our own PackageInfo

                    var getPackageInfoCount = 0
                    val selfPkgInfoIndex = implementation!!.instructions.indexOfFirst {
                        if (it.opcode == Opcode.INVOKE_VIRTUAL &&
                            it is ReferenceInstruction &&
                            it.reference.toString().contains("PackageManager;->getPackageInfo")
                        ) {
                            getPackageInfoCount++
                            getPackageInfoCount == 2 // Second call = self package query
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

                    // The move-result-object follows the invoke-virtual
                    val moveResultIndex = selfPkgInfoIndex + 1
                    val moveResultInstruction = getInstruction<OneRegisterInstruction>(moveResultIndex)
                    val pkgInfoRegister = moveResultInstruction.registerA

                    // After move-result-object, inject code to replace the signatures
                    // in the PackageInfo with the original FX Explorer certificate.
                    //
                    // This creates a new Signature from the hex-encoded original certificate,
                    // wraps it in an array, and assigns it to PackageInfo.signatures.
                    //
                    // Available registers: method has .locals 11, and at this point v2-v10 are
                    // free (v0=key app pkgInfo, v1=1, v3=PackageManager reused, v4=0x40, v5+=loop vars).
                    // We use v2, v3, v4 which are about to be reassigned in the signature loop.
                    addInstructions(
                        moveResultIndex + 1,
                        """
                            # Create Signature from original certificate hex
                            new-instance v2, Landroid/content/pm/Signature;
                            const-string v3, "$ORIGINAL_SIGNATURE_HEX"
                            invoke-direct {v2, v3}, Landroid/content/pm/Signature;-><init>(Ljava/lang/String;)V

                            # Create new Signature[1] array
                            const/4 v3, 0x1
                            new-array v3, v3, [Landroid/content/pm/Signature;

                            # Store the original signature in the array
                            const/4 v4, 0x0
                            aput-object v2, v3, v4

                            # Replace PackageInfo.signatures with our spoofed one
                            iput-object v3, v$pkgInfoRegister, Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;
                        """
                    )
                }
            }
        }
    )

    // Main bytecode patch: preserve filter on directory refresh
    execute {
        DirectoryRefreshFingerprint.method.apply {
            // Find the V0() call that unconditionally clears the filter.
            // In L0() the pattern is:
            //   invoke-virtual {p0}, Llf/s;->K0()V   (cancel running task)
            //   invoke-virtual {p0}, Llf/s;->V0()V   (CLEAR FILTER - target)
            //   iget-object v0, p0, Llf/s;->a2:...   (next instruction)
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

            // Replace the unconditional V0() call with a conditional one:
            //   iget-object v0, p0, Llf/s;->g2:Ljava/lang/String;   // Load filter text
            //   if-nez v0, :skip_clear                                 // If filter active, skip clear
            //   invoke-virtual {p0}, Llf/s;->V0()V                    // Clear filter (only when no filter)
            //   :skip_clear
            //   nop
            //
            // Then remove the original V0() call which has been shifted down.
            addInstructionsWithLabels(
                v0CallIndex,
                """
                    iget-object v0, p0, Llf/s;->g2:Ljava/lang/String;
                    if-nez v0, :skip_clear
                    invoke-virtual {p0}, Llf/s;->V0()V
                    :skip_clear
                    nop
                """
            )

            // After injection, instructions are:
            //   [0] iget-object  (injected)
            //   [1] if-nez       (injected)
            //   [2] invoke-virtual V0() (injected - conditional)
            //   [3] nop          (injected - label anchor)
            //   [4] invoke-virtual V0() (ORIGINAL - to remove)
            // The original V0() is now 4 instructions after the injection point.
            removeInstruction(v0CallIndex + 4)
        }
    }
}
