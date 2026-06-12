package app.morphe.patches.fstop.misc.license

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the N2() method that checks if the F-Stop premium key app is installed.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/p;->N2()Z
 * - PUBLIC STATIC, returns boolean, no parameters
 * - Contains const-string "com.fstop.photo.key"
 * - Calls A2(String, Context) which checks if the package is installed
 * - Returns true if the key app is installed (premium unlocked)
 *
 * The patch will replace the return value with true (const/4 vN, 0x1 + return).
 */
internal object KeyAppCheckFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/p;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    strings = listOf("com.fstop.photo.key"),
)

/**
 * Fingerprint for the x2() method that checks if the key app's activity is disabled.
 *
 * From APK decompilation:
 * - Method: Lcom/fstop/photo/p;->x2()Z
 * - PUBLIC STATIC, returns boolean, no parameters
 * - Contains const-string "com.fstop.photo.key" and "com.fstop.photo.key.MainActivity"
 * - Checks if the key app's MainActivity component is disabled
 *   (getComponentEnabledSetting == COMPONENT_ENABLED_STATE_DISABLED)
 * - Returns true if the component is disabled (key app is active)
 *
 * The patch will replace the return value with true (const/4 vN, 0x1 + return).
 * Returning true means "key app's launcher is disabled" = premium is valid.
 */
internal object KeyAppEnabledCheckFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/p;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    strings = listOf("com.fstop.photo.key.MainActivity"),
)
