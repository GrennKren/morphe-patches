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

/**
 * Fingerprint for the WiFi state change receiver in SharingService$3.
 *
 * This BroadcastReceiver's onReceive() checks if wifi_state == 1 (DISABLED)
 * and calls stopSelf() to kill the sharing service. When Mobile Hotspot is
 * active, WiFi client mode is DISABLED (wifi_state=1), causing this receiver
 * to immediately shut down the Web Access service.
 *
 * Key identifiers:
 * - Inner class of SharingService
 * - Extends BroadcastReceiver
 * - References string "wifi_state" and "Shutting down sharing due to Wi-Fi disable."
 */
internal object WifiStateReceiverFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
    definingClass = "Lnextapp/fx/plus/share/service/SharingService\$3;",
    strings = listOf("wifi_state", "Shutting down sharing due to Wi-Fi disable."),
)

/**
 * Fingerprint for SharingService.onStartCommand().
 *
 * This method creates a WifiLock with mode 3 (WIFI_MODE_FULL_HIGH_PERF)
 * which may fail or cause issues when WiFi is in AP mode (hotspot).
 * It also registers the WiFi state change receiver unconditionally.
 *
 * Key identifiers:
 * - Public final method returning int
 * - Parameters: Intent, int, int
 * - Defining class: SharingService
 * - References "SharingService: HTTP server not started."
 * - Calls xa/c.b(Context) for network type detection
 * - Calls WifiManager.createWifiLock()
 * - References "android.net.wifi.WIFI_STATE_CHANGED"
 */
internal object SharingServiceStartFingerprint : Fingerprint(
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Intent;", "I", "I"),
    definingClass = "Lnextapp/fx/plus/share/service/SharingService;",
    strings = listOf("SharingService: HTTP server not started."),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/net/wifi/WifiManager;",
            name = "createWifiLock",
        ),
    ),
)
