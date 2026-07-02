/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER

/**
 * Patch to unlock all premium features without owning the FX Plus License Key app.
 *
 * PROBLEM:
 * FX Explorer gates premium features (Web Access, network connections, cloud
 * storage, Bluetooth transfer, etc.) behind the FX Plus License Key app
 * (nextapp.fx.rk). Users who don't own the license key app cannot use these
 * features even after patching.
 *
 * SOLUTION:
 * Hook the license verification method (lh.n.l(Context)Z) to always return
 * true. This makes the app think it is licensed, unlocking all premium features.
 *
 * The injected code at method entry:
 * ```smali
 *     const/4 v0, 0x1
 *     sput-boolean v0, Llh/n;->c:Z    # set cached license flag to true
 *     const/4 v0, 0x2
 *     sput v0, Llh/n;->d:I            # set license source to "signature" (legit-looking)
 *     const/4 v0, 0x1
 *     return v0                        # return true
 * ```
 *
 * Setting field `c` (cached license state) to true ensures any code that
 * reads this field directly also sees the licensed state. Setting field `d`
 * (license source: 1=IAB, 2=signature) to 2 makes the state look legitimate.
 *
 * REGISTER SAFETY:
 * - Method declares `.locals 11`, so v0-v10 are free at entry.
 * - p0 (Context parameter) is not touched by the injected code.
 * - The original first instruction (`sget-boolean v0, Llh/n;->c:Z`) is
 *   never reached because we return before it.
 *
 * COMPATIBILITY:
 * - Default DISABLED. Enable this patch only if you don't own the FX Plus
 *   License Key app.
 * - Independent of "License compatibility" — works whether or not that
 *   patch is enabled. If both are enabled, this patch takes precedence
 *   (the method returns true before reaching the signature comparison).
 * - Independent of "Side-by-side installation".
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Unlocks all premium features without needing the FX Plus License Key app.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    execute {
        LicenseCheckFingerprint.method.apply {
            implementation
                ?: throw PatchException("LicenseCheckFingerprint: no implementation")

            addInstructions(
                0,
                """
                    # Force license check to return true
                    const/4 v0, 0x1
                    sput-boolean v0, Llh/n;->c:Z
                    const/4 v0, 0x2
                    sput v0, Llh/n;->d:I
                    const/4 v0, 0x1
                    return v0
                """,
            )
        }
    }
}
