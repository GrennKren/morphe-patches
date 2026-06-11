/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.webaccess

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
 * ROOT CAUSE:
 * The network type detection in xa/c.b(Context) has a logic gap:
 * - When NetworkInfo is null → immediately returns "no network" (type=1)
 * - The hotspot detection (isWifiApEnabled) is ONLY inside the mobile type=0 branch
 * - When Mobile Data is OFF but hotspot is ON, NetworkInfo is null → hotspot never detected
 *
 * FIX:
 * Patch xa/c.b(Context) to check isWifiApEnabled when NetworkInfo is null.
 * If hotspot is active, enumerate network interfaces for IP and return type=4
 * (WiFi AP) with the correct hotspot IP. Since t8/b.b(4) already returns true,
 * Web Access will work on hotspot without needing any other changes.
 *
 * CRITICAL REGISTER CONSTRAINT:
 * xa/c.b() has only 4 registers (v0-v3), with p0=v3 (Context parameter).
 * All injected code MUST use only v0-v3. Using v4+ causes VerifyError → crash.
 *
 * Register layout at injection point (after getActiveNetworkInfo + move-result-object):
 *   v0 = NetworkInfo (may be null)
 *   v1 = free (uninitialized)
 *   v2 = free (uninitialized)
 *   v3 = Context parameter (p0)
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
        // Patch xa/c.b(Context) — add hotspot check when NetworkInfo is null.
        //
        // Original xa/c.b() bytecode (4 registers: v0-v3, p0=v3=Context):
        //   0000: const-string v0, "connectivity"
        //   0001: invoke-virtual {v3, v0}, Context.getSystemService(String)Object
        //   0002: move-result-object v0
        //   0003: check-cast v0, ConnectivityManager
        //   0004: if-eqz v0, +071h          ← cm null → return no network
        //   0005: invoke-virtual {v0}, ConnectivityManager.getActiveNetworkInfo()
        //   0006: move-result-object v0
        //   0007: if-eqz v0, +06bh          ← ni null → return no network (INJECTION POINT)
        //   0008: invoke-virtual {v0}, NetworkInfo.getType()
        //   ... (type checking branches follow)
        //
        // When Mobile Data OFF + Hotspot ON: getActiveNetworkInfo() returns null,
        // instruction 0007 jumps to "return d" (no network singleton).
        // The hotspot check in the type=0 branch is never reached.
        //
        // Fix: Replace the if-eqz at 0007 with code that checks isWifiApEnabled
        // when NetworkInfo is null. Uses only v0-v3 (4 registers total).

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
    }
}
