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
 * This method uses ConnectivityManager to detect the active network type:
 * - Null NetworkInfo → type 1 (No network) — BUG: doesn't check hotspot!
 * - Type 0 (mobile) → checks isWifiApEnabled: type 4 (hotspot) or type 3
 * - Type 1 (WiFi) → delegates to a(Context) → type 2
 * - Type 9 (Ethernet) → type 5
 *
 * Key identifiers:
 * - Public static method returning Lxa/c; with 1 Context parameter
 * - Defining class Lxa/c;
 * - Calls ConnectivityManager.getActiveNetworkInfo()
 * - Uses reflection via static field e (isWifiApEnabled Method)
 * - References string "connectivity" (system service name)
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

/**
 * Fingerprint for the network type checker method in Lt8/b;.
 *
 * From APK analysis: b(I)Z — takes an integer network type (from xa/c.a field)
 * and returns true if Web Access should be allowed on that network type.
 *
 * Network type constants (from xa/c class):
 *   1 = No network (getActiveNetworkInfo() returns null)
 *   2 = WiFi connected (NetworkInfo.getType()==1)
 *   3 = Mobile data (NetworkInfo.getType()==0, hotspot NOT enabled)
 *   4 = WiFi AP / Hotspot (NetworkInfo.getType()==0, isWifiApEnabled==true)
 *   5 = Ethernet (NetworkInfo.getType()==9)
 *
 * Original behavior of b(int):
 *   type 1 (No network)    → false
 *   type 2 (WiFi)           → true
 *   type 3 (Mobile data)    → false
 *   type 4 (Hotspot)        → true
 *   type 5 (Ethernet)       → true
 *
 * Key identifiers:
 * - Public static method returning boolean with 1 int parameter
 * - Defining class Lt8/b;
 * - Simple if-else chain on the input integer
 * - 23 instruction code units, 4 registers
 */
internal object NetworkTypeCheckerFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.SYNTHETIC),
    parameters = listOf("I"),
    definingClass = "Lt8/b;",
)
