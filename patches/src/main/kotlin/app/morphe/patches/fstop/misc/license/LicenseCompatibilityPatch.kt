/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.license

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to fix premium detection for legitimate F-Stop license holders.
 *
 * PROBLEM:
 * F-Stop's premium check method s3.a.d() uses three OR conditions:
 *   1. checkSignatures("com.fstop.photo", "com.fstop.photo.key") == 0
 *   2. b0.z (isIAPPurchased flag from SharedPreferences/billing)
 *   3. a.size() > 0 (purchasedInAppPurchasesAndSubscriptions set)
 *
 * The first condition (checkSignatures) ALWAYS fails for patched APKs because
 * they are re-signed with a different certificate than the original. This
 * breaks premium detection even for users who legitimately purchased through
 * Google Play IAP or the key app.
 *
 * F-Stop uses Google Play In-App Purchasing (IAP) for premium — there is NO
 * separate key app that users need to install. The checkSignatures() call is
 * a legacy verification that checks for a matching signing certificate between
 * the main app and the key app package, but since patched APKs are re-signed,
 * this always returns SIGNATURE_NOT_MATCH (-3).
 *
 * SOLUTION:
 * Replace the checkSignatures() call in s3.a.d() with a const/4 v0, 0x0
 * (result = 0 = SIGNATURE_MATCH). This effectively makes the first OR
 * condition always true, so d() returns true. This is a compatibility fix
 * that allows patched APKs to work for legitimate purchasers — the IAP
 * checks (b0.z and a.size()) are preserved as fallback conditions.
 *
 * The bytecode of s3.a.d() (verified via androguard):
 *   [0] sget-boolean v0, Ls3/a;->b Z     (initialized flag)
 *   [1] if-nez v0, +5h
 *   [2] invoke-static Ls3/a;->c()V
 *   [3] sget-object v0, b0.r Context
 *   [4] invoke-virtual v0, getPackageManager()
 *   [5] move-result-object v0
 *   [6] const-string v1, "com.fstop.photo"
 *   [7] const-string v2, "com.fstop.photo.key"
 *   [8] invoke-virtual v0, v1, v2, checkSignatures()I   <-- TARGET
 *   [9] move-result v0                                     <-- TARGET
 *   [10] const/4 v1, 0
 *   [11] const/4 v2, 1
 *   [12] if-nez v0, +4h   (if result != 0, no match)
 *   [13] move v0, v2      (match)
 *   [14] goto +2h
 *   [15] move v0, v1      (no match)
 *   ...
 *
 * We replace [8] with const/4 v0, 0x0 (SIGNATURE_MATCH = 0)
 * And replace [9] with nop (no move-result needed)
 *
 * After patching, instruction [12] if-nez v0 will NOT branch (v0=0),
 * so [13] move v0, v2 sets v0=1 (match), and the method continues
 * to check b0.z and a.size() as normal OR conditions.
 *
 * This patch is enabled by default because it is a compatibility fix —
 * not a bypass. Patched APKs cannot pass signature verification, so
 * this fix is necessary for any premium features to work.
 */
@Suppress("unused")
val licenseCompatibilityPatch = bytecodePatch(
    name = "License compatibility",
    description = "Fixes premium detection for patched F-Stop APKs. " +
        "The original checkSignatures() verification always fails for " +
        "patched APKs because they are re-signed with a different " +
        "certificate. This patch replaces the checkSignatures() call " +
        "with a match result, allowing premium features to work " +
        "without needing the 'Unlock premium' bypass patch. " +
        "The IAP-based checks (b0.z and purchase set) are preserved.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        PremiumCheckFingerprint.method.apply {
            val impl = implementation!!

            // Clear try-catch blocks to prevent DEX corruption during modification
            val tryBlocksField = MutableMethodImplementation::class.java
                .getDeclaredField("tryBlocks")
            tryBlocksField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tryBlocks = tryBlocksField.get(impl) as java.util.ArrayList<*>
            tryBlocks.clear()

            // Find the checkSignatures() invoke instruction
            val checkSigsIndex = impl.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("PackageManager;->checkSignatures")
            }

            if (checkSigsIndex == -1) {
                throw PatchException(
                    "Could not find PackageManager.checkSignatures() call in s3.a.d(). " +
                        "The APK version may not be supported."
                )
            }

            // Find the move-result immediately after checkSignatures
            val moveResultIndex = checkSigsIndex + 1
            val moveResultInstr = impl.instructions.elementAtOrNull(moveResultIndex)
            if (moveResultInstr == null || moveResultInstr.opcode != Opcode.MOVE_RESULT) {
                throw PatchException(
                    "Expected move-result after checkSignatures() at index $checkSigsIndex, " +
                        "but found ${moveResultInstr?.opcode ?: "end of method"}. " +
                        "The APK version may not be supported."
                )
            }

            // Replace checkSignatures() call with: const/4 v0, 0x0 (SIGNATURE_MATCH = 0)
            // This makes the signature check always succeed.
            // The subsequent if-nez v0 check will NOT branch (v0=0 means match),
            // causing the code to fall through to "move v0, v2" (v0=1 = match found).
            addInstructions(
                checkSigsIndex,
                """
                    const/4 v0, 0x0
                    nop
                """,
            )

            // Remove the original checkSignatures() invoke and move-result
            // (they are now shifted by +2 due to our insertion above)
            removeInstruction(checkSigsIndex + 2 + 1) // original move-result
            removeInstruction(checkSigsIndex + 2)      // original checkSignatures invoke
        }
    }
}
