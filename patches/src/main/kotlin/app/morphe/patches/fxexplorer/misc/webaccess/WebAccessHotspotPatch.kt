/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.webaccess

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to enable FX Explorer's Web Access feature when the phone is acting
 * as a mobile hotspot, even without Mobile Data being active.
 *
 * PROBLEM:
 * When the phone is acting as a mobile hotspot WITHOUT Mobile Data enabled:
 * - Android's ConnectivityManager.getActiveNetworkInfo() returns null
 * - xa/c.b() returns a singleton with field a=1 (No network), IP=null
 * - t8/b.b(1) returns false → WiFi check fails → Web Access won't start
 *
 * Additionally, even if xa/c.b() is patched to return type=4 (hotspot):
 * - SharingService registers a BroadcastReceiver for WIFI_STATE_CHANGED
 * - When hotspot is active, WiFi client mode is DISABLED (wifi_state=1)
 * - The receiver calls stopSelf(), immediately killing the Web Access service
 * - WifiLock.acquire() with mode FULL_HIGH_PERF may fail in AP mode
 *
 * ROOT CAUSE (3 issues):
 * 1. NetworkInfo is null when Mobile Data OFF + Hotspot ON → type=1 (no network)
 * 2. WiFi state receiver kills service when wifi_state=1 (normal for hotspot mode)
 * 3. WifiLock FULL_HIGH_PERF is inappropriate for AP mode
 *
 * FIX (3 parts):
 * Part A: Patch xa/c.b(Context) — check isWifiApEnabled when NetworkInfo is null.
 *         If hotspot active, return type=4 with IP from network interface enumeration.
 * Part B: Patch SharingService$3.onReceive() — before calling stopSelf() when
 *         wifi_state=1, check if hotspot is active. If so, skip the shutdown.
 * Part C: Patch SharingService.onStartCommand() — replace WifiLock mode 3
 *         (FULL_HIGH_PERF) with mode 1 (FULL) which is compatible with AP mode.
 */
@Suppress("unused")
val webAccessHotspotPatch = bytecodePatch(
    name = "Web Access on hotspot",
    description = "Enables Web Access when the phone is acting as a mobile hotspot, " +
        "even without Mobile Data being active. " +
        "By default, FX Explorer's Web Access only works when connected to WiFi as a client " +
        "or when Mobile Data is active with 'Cellular Access' enabled. " +
        "This patch allows Web Access to start on a mobile hotspot LAN without requiring " +
        "Mobile Data, since the hotspot creates a functional local network that other " +
        "devices can connect to.",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    execute {
        // ============================================================
        // Part A: Patch xa/c.b(Context) — hotspot detection when NetworkInfo is null
        // ============================================================
        NetworkInfoFingerprint.method.apply {
            val instructions = implementation!!.instructions

            // Find the if-eqz after getActiveNetworkInfo() call
            var networkInfoNullCheckIndex = -1

            for ((index, inst) in instructions.withIndex()) {
                if (inst.opcode == Opcode.INVOKE_VIRTUAL &&
                    inst is ReferenceInstruction &&
                    inst.reference.toString().contains("getActiveNetworkInfo")
                ) {
                    // Pattern: invoke-virtual → move-result-object → if-eqz
                    val moveResultIndex = index + 1
                    val ifEqzIndex = index + 2
                    if (moveResultIndex < instructions.size &&
                        ifEqzIndex < instructions.size &&
                        instructions[moveResultIndex].opcode == Opcode.MOVE_RESULT_OBJECT &&
                        instructions[ifEqzIndex].opcode == Opcode.IF_EQZ
                    ) {
                        networkInfoNullCheckIndex = ifEqzIndex
                    }
                    break
                }
            }

            if (networkInfoNullCheckIndex == -1) {
                throw PatchException(
                    "Could not find NetworkInfo null check in xa/c.b(). " +
                        "The APK version may not be supported."
                )
            }

            // Remove the original if-eqz at networkInfoNullCheckIndex
            removeInstruction(networkInfoNullCheckIndex)

            // Inject hotspot detection code using ONLY v0-v3.
            //
            // Register allocation strategy (4 registers: v0-v3):
            //   v0 = NetworkInfo → Method → Boolean result → t4/j → xa/c instance
            //   v1 = free → WifiManager → invoke result → IP string → IP string
            //   v2 = free → "wifi" string → null (0) → type 4 → type 4
            //   v3 = Context → Context → Context → Context → null (0) for SSID
            //
            // Note: v3 is overwritten with 0 (null SSID) only right before the
            // constructor call, at which point we no longer need the Context.

            addInstructionsWithLabels(
                networkInfoNullCheckIndex,
                """
                    # v0 = NetworkInfo (may be null), v3 = Context (p0)
                    # If NetworkInfo is NOT null, continue to original code
                    if-nez v0, :continue_original

                    # === NetworkInfo is null — check if WiFi AP (hotspot) is active ===

                    # Get the isWifiApEnabled Method from static field xa/c.e
                    sget-object v0, Lxa/c;->e:Ljava/lang/reflect/Method;
                    if-eqz v0, :return_no_network

                    # Get WifiManager: v1 = v3.getApplicationContext().getSystemService("wifi")
                    invoke-virtual {v3}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
                    move-result-object v1
                    const-string v2, "wifi"
                    invoke-virtual {v1, v2}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
                    move-result-object v1
                    if-eqz v1, :return_no_network
                    check-cast v1, Landroid/net/wifi/WifiManager;

                    # Call isWifiApEnabled via reflection: v0.invoke(v1, null)
                    const/4 v2, 0
                    invoke-virtual {v0, v1, v2}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v1

                    # Check if result is Boolean
                    instance-of v0, v1, Ljava/lang/Boolean;
                    if-eqz v0, :return_no_network

                    # Get boolean value
                    check-cast v1, Ljava/lang/Boolean;
                    invoke-virtual {v1}, Ljava/lang/Boolean;->booleanValue()Z
                    move-result v0

                    # If hotspot NOT enabled, return no network
                    if-eqz v0, :return_no_network

                    # === Hotspot IS active! Detect IP via NetworkInterface enumeration ===
                    invoke-static {}, Lxa/c;->c()Lt4/j;
                    move-result-object v0
                    if-nez v0, :got_hotspot_ip
                    const/4 v1, 0
                    goto :create_hotspot_result

                    :got_hotspot_ip
                    iget-object v0, v0, Lt4/j;->X:Ljava/lang/Object;
                    check-cast v0, Ljava/net/InetAddress;
                    invoke-virtual {v0}, Ljava/net/InetAddress;->getHostAddress()Ljava/lang/String;
                    move-result-object v1

                    :create_hotspot_result
                    # Create xa/c(IP, type=4, SSID=null) — type 4 = WiFi AP (Hotspot)
                    new-instance v0, Lxa/c;
                    const/4 v2, 4
                    const/4 v3, 0
                    invoke-direct {v0, v1, v2, v3}, Lxa/c;-><init>(Ljava/lang/String;ILjava/lang/String;)V
                    return-object v0

                    :return_no_network
                    # Hotspot not active — return "no network" singleton
                    sget-object v0, Lxa/c;->d:Lxa/c;
                    return-object v0

                    :continue_original
                    # NetworkInfo is NOT null — continue with original code
                    nop
                """
            )
        }

        // ============================================================
        // Part B: Patch SharingService$3.onReceive() — skip shutdown when hotspot active
        // ============================================================
        // The original receiver checks:
        //   if (extras.getInt("wifi_state") == 1 && f7523f == 1 && X1 != null)
        //     → Log.i + stopSelf()
        //
        // When hotspot is active, WiFi client mode is DISABLED (wifi_state=1),
        // triggering the shutdown. We add a hotspot check BEFORE the Log+stopSelf block.
        WifiStateReceiverFingerprint.method.apply {
            val instructions = implementation!!.instructions

            // Strategy: Find the const-string "nextapp.fx" that's followed by
            // "Shutting down sharing due to Wi-Fi disable." and inject before it.
            var injectionIndex = -1
            for ((index, inst) in instructions.withIndex()) {
                if (inst.opcode == Opcode.CONST_STRING &&
                    inst is ReferenceInstruction &&
                    inst.reference.toString().contains("nextapp.fx")
                ) {
                    // Check if next const-string is "Shutting down sharing due to Wi-Fi disable."
                    val nextIndex = index + 1
                    if (nextIndex < instructions.size) {
                        val nextInst = instructions[nextIndex]
                        if (nextInst.opcode == Opcode.CONST_STRING &&
                            nextInst is ReferenceInstruction &&
                            nextInst.reference.toString().contains("Shutting down sharing")
                        ) {
                            injectionIndex = index
                            break
                        }
                    }
                }
            }

            if (injectionIndex == -1) {
                throw PatchException(
                    "Could not find Log.i block in SharingService\$3.onReceive(). " +
                        "The APK version may not be supported."
                )
            }

            // At this point, register state is:
            //   p0 = this (SharingService$3)
            //   p1 = SharingService instance (from this.a)
            //   p2 = free (was "nextapp.fx" string, about to be set)
            //   v0 = free
            //
            // We need to check if hotspot is active using xa/c.e (isWifiApEnabled Method).
            // We use the SharingService context (p1) to get WifiManager.

            addInstructionsWithLabels(
                injectionIndex,
                """
                    # === Hotspot check: Don't shut down if Mobile Hotspot is active ===
                    # p0 = this, p1 = SharingService instance

                    # Get isWifiApEnabled Method from xa/c.e
                    sget-object v0, Lxa/c;->e:Ljava/lang/reflect/Method;
                    if-eqz v0, :hotspot_check_done

                    # Get WifiManager from SharingService context
                    invoke-virtual {p1}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
                    move-result-object p2
                    const-string v0, "wifi"
                    invoke-virtual {p2, v0}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
                    move-result-object p2
                    if-eqz p2, :hotspot_check_done
                    check-cast p2, Landroid/net/wifi/WifiManager;

                    # Call isWifiApEnabled via reflection
                    sget-object v0, Lxa/c;->e:Ljava/lang/reflect/Method;
                    const/4 p1, 0
                    invoke-virtual {v0, p2, p1}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p2

                    # Check result is Boolean
                    instance-of v0, p2, Ljava/lang/Boolean;
                    if-eqz v0, :hotspot_check_done

                    # Get boolean value
                    check-cast p2, Ljava/lang/Boolean;
                    invoke-virtual {p2}, Ljava/lang/Boolean;->booleanValue()Z
                    move-result v0

                    # If hotspot IS active, skip the shutdown entirely — just return
                    if-nez v0, :hotspot_active_skip

                    :hotspot_check_done
                    # Hotspot NOT active — continue with original shutdown logic
                    nop

                    :hotspot_active_skip
                    # Hotspot is active — skip shutdown, just return
                    return-void
                """
            )
        }

        // ============================================================
        // Part C: Patch SharingService.onStartCommand() — use WifiLock mode 1 instead of 3
        // ============================================================
        // The original code creates WifiLock with mode 3 (WIFI_MODE_FULL_HIGH_PERF):
        //   invoke-virtual {v5, v3, v9}, WifiManager.createWifiLock(ILjava/lang/String;)WifiLock
        // where v3 = 3 (WIFI_MODE_FULL_HIGH_PERF)
        //
        // Mode 3 can cause issues when WiFi is in AP mode.
        // We insert const/4 v3, 0x1 right before the call to change mode to FULL.
        SharingServiceStartFingerprint.method.apply {
            val instructions = implementation!!.instructions

            // Find createWifiLock call
            var createWifiLockIndex = -1
            for ((index, inst) in instructions.withIndex()) {
                if (inst.opcode == Opcode.INVOKE_VIRTUAL &&
                    inst is ReferenceInstruction &&
                    inst.reference.toString().contains("createWifiLock")
                ) {
                    createWifiLockIndex = index
                    break
                }
            }

            if (createWifiLockIndex == -1) {
                throw PatchException(
                    "Could not find createWifiLock in SharingService.onStartCommand(). " +
                        "The APK version may not be supported."
                )
            }

            // Insert const/4 v3, 0x1 right before the createWifiLock call
            addInstructions(
                createWifiLockIndex,
                """
                    # Override WifiLock mode: use 1 (FULL) instead of 3 (FULL_HIGH_PERF)
                    # FULL_HIGH_PERF can fail when WiFi is in AP/hotspot mode
                    const/4 v3, 0x1
                """
            )
        }
    }
}
