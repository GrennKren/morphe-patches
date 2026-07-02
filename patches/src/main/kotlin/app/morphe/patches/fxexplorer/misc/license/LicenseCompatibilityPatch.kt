/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.license

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import app.morphe.patches.fxexplorer.shared.Constants.ORIGINAL_SIGNATURE_HEX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to make the patched APK still recognize the FX Plus License Key app.
 *
 * PROBLEM:
 * The patched APK is signed with a different certificate (Morphe) than the
 * original FX Explorer. The FX Plus License Key app (nextapp.fx.rk) checks
 * that both apps share the same signing certificate. With a patched APK,
 * this signature comparison fails, so even users who own the FX Plus
 * License Key app would lose their premium features after patching.
 *
 * SOLUTION:
 * Hook the license verification method (lh.n.l(Context)Z) to spoof the
 * original signing certificate during the self-package signature lookup.
 *
 * The license verification method calls PackageManager.getPackageInfo twice:
 *   1. getPackageInfo("nextapp.fx.rk", 64)  — for the license key app
 *   2. getPackageInfo(context.getPackageName(), 64)  — for self
 *
 * After the second call (self lookup), we inject code that overwrites
 * PackageInfo.signatures with the original FX Explorer certificate.
 * This makes the subsequent signature comparison succeed, so the license
 * key app is still recognized.
 *
 * Use this patch if you own the FX Plus License Key app and want to keep
 * using your paid features with the patched APK. If you don't own the
 * license key app, use the "Unlock premium" patch instead.
 *
 * COMPATIBILITY:
 * - Independent of "Side-by-side installation" (works whether or not
 *   the package name is changed).
 * - Independent of "Unlock premium" (works whether or not premium is
 *   force-unlocked).
 */
@Suppress("unused")
val licenseCompatibilityPatch = bytecodePatch(
    name = "License compatibility",
    description = "Makes the patched app still recognize the FX Plus License Key app, " +
        "so your existing paid license keeps working. Use this if you own the FX Plus " +
        "license key. If you don't own one, use the 'Unlock premium' patch instead.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    execute {
        LicenseCheckFingerprint.method.apply {
            var getPackageInfoCount = 0
            val selfPkgInfoIndex = implementation!!.instructions.indexOfFirst {
                if (it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("PackageManager;->getPackageInfo")
                ) {
                    getPackageInfoCount++
                    getPackageInfoCount == 2  // second call = self lookup
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
                """,
            )
        }
    }
}
