/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.telemetry

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to disable all telemetry and crash reporting in F-Stop Photo Gallery.
 *
 * F-Stop collects data through three separate telemetry systems:
 *
 * 1. Bugsnag Crash Reporting (Java):
 *    Initialized in MyApplication.f() which creates a Bugsnag client,
 *    adds a callback to attach user/device data, and registers it as
 *    the uncaught exception handler. This sends crash reports, device
 *    info (free space, memory, storage paths, OS version), and debug
 *    data to Bugsnag servers.
 *
 * 2. Firebase Analytics:
 *    Initialized in MyApplication.onCreate() which creates a
 *    FirebaseAnalytics instance and stores it in b0.j0. Used to track
 *    events like "FSTOP_firstStartWithPermissionGranted" and
 *    "FSTOP_click_purchase_button_in_dialog".
 *
 * 3. Bugsnag Native (JNI):
 *    Called via NativeMethods.performNativeBugsnagSetup() which is a
 *    native method implemented in libfunctions-jni.so. This initializes
 *    Bugsnag's native (C/C++) crash reporting alongside the Java one.
 *
 * This patch disables all three by:
 * - Replacing MyApplication.f() body with return-void (disables Java Bugsnag)
 * - Replacing FirebaseAnalytics.getInstance() result with null (disables Firebase)
 * - NOP-ing the invoke-virtual call to performNativeBugsnagSetup() in onCreate()
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables Bugsnag crash reporting (both Java and native), " +
        "and Firebase Analytics event tracking. Prevents F-Stop from sending " +
        "crash reports, device information, and usage analytics to third-party servers.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        // ============================================================
        // Part 1: Disable Java Bugsnag initialization
        // ============================================================
        // Replace MyApplication.f() body with return-void.
        // This prevents:
        // - Bugsnag client creation (com.bugsnag.android.w.J())
        // - User data attachment callback
        // - Bugsnag client initialization (com.bugsnag.android.n.f())
        // - Custom uncaught exception handler registration
        BugsnagInitFingerprint.method.apply {
            val impl = implementation!!
            val instructions = impl.instructions.toList()
            // Remove all instructions from end to 1, then replace index 0 with return-void
            for (i in instructions.size - 1 downTo 1) {
                impl.removeInstruction(i)
            }
            // Replace first instruction with return-void
            replaceInstruction(0, "return-void")
        }

        // ============================================================
        // Part 2: Disable Firebase Analytics
        // ============================================================
        // Replace FirebaseAnalytics.getInstance() result with null.
        // The sput-object that stores it in b0.j0 will then store null,
        // and all logEvent() calls check "if (firebaseAnalytics != null)"
        // before logging, so they will be no-ops.
        FirebaseAnalyticsInitFingerprint.method.apply {
            val impl = implementation!!
            val firebaseGetInstanceIndex = impl.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("FirebaseAnalytics;->getInstance")
            }

            if (firebaseGetInstanceIndex == -1) {
                throw Exception("Could not find FirebaseAnalytics.getInstance() call in MyApplication.onCreate()")
            }

            // The next instruction after invoke-static is move-result-object
            // which stores the result. We replace it with const/4 vN, 0 (null)
            val moveResultIndex = firebaseGetInstanceIndex + 1
            val moveResultInstr = impl.instructions.elementAt(moveResultIndex)

            if (moveResultInstr.opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw Exception("Expected MOVE_RESULT_OBJECT after FirebaseAnalytics.getInstance()")
            }

            val register = (moveResultInstr as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA

            // Replace move-result-object with const/4 vN, 0x0 (null)
            replaceInstruction(
                moveResultIndex,
                "const/4 v$register, 0x0",
            )
        }

        // ============================================================
        // Part 3: Disable native Bugsnag setup
        // ============================================================
        // Instead of trying to modify the native method (which has no
        // implementation/code), we NOP the invoke-virtual call to
        // performNativeBugsnagSetup() in MyApplication.onCreate().
        //
        // The call sequence in onCreate() is:
        //   new-instance v0, Lcom/fstop/Native/NativeMethods;
        //   const/4 v1, 0x0
        //   invoke-direct {v0, v1}, Lcom/fstop/Native/NativeMethods;-><init>(Lcom/fstop/Native/FolderScannedProcessor;)V
        //   invoke-virtual {v0}, Lcom/fstop/Native/NativeMethods;->performNativeBugsnagSetup()V
        //
        // We find the invoke-virtual for performNativeBugsnagSetup() and
        // replace it with a NOP (or just remove it).
        FirebaseAnalyticsInitFingerprint.method.apply {
            // We're still in onCreate() - find the performNativeBugsnagSetup call
            val impl = implementation!!
            val nativeBugsnagCallIndex = impl.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("performNativeBugsnagSetup")
            }

            if (nativeBugsnagCallIndex == -1) {
                // Not fatal - the native method might not exist in this version
                return@apply
            }

            // Replace the invoke-virtual with nop (can't remove because it shifts indices)
            // Actually, we can remove it since we're not referencing later indices
            impl.removeInstruction(nativeBugsnagCallIndex)
        }
    }
}
