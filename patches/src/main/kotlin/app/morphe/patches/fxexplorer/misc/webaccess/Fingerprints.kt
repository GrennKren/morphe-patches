package app.morphe.patches.fxexplorer.misc.webaccess

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the network info detection method in Lxa/c;.
 *
 * From APK analysis: b(Landroid/content/Context;)Lxa/c; — takes a Context
 * and returns an xa/c instance containing network type, IP, and SSID.
 *
 * Key identifiers:
 * - Public static method returning Lxa/c; with 1 Context parameter
 * - Defining class Lxa/c;
 * - Calls ConnectivityManager.getActiveNetworkInfo()
 * - References string "connectivity" (system service name)
 *
 * IMPORTANT: This method has only 4 registers (v0-v3), p0=v3 (Context).
 * Any injected code MUST use only v0-v3 to avoid VerifyError.
 */
internal object NetworkInfoFingerprint : Fingerprint(
    returnType = "Lxa/c;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    definingClass = "Lxa/c;",
    strings = listOf("connectivity"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/net/ConnectivityManager;",
            name = "getActiveNetworkInfo",
        ),
    ),
)
