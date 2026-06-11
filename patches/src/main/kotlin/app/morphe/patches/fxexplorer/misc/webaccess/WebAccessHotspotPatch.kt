/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.webaccess

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Patch to enable FX Explorer's Web Access feature when the phone is acting
 * as a mobile hotspot, even without Mobile Data being active.
 *
 * PROBLEM:
 * FX Explorer's Web Access feature allows accessing device files via a web
 * browser or WebDAV on another device. However, the Web Access toggle button
 * (both the in-app power button and the Quick Settings tile) performs a WiFi
 * connectivity check before starting the service.
 *
 * When the phone is acting as a mobile hotspot WITHOUT Mobile Data enabled:
 * - Android's ConnectivityManager.getActiveNetworkInfo() returns null
 *   (no active data connection)
 * - xa/c.b() returns a singleton with field a=1 (No network), IP=null
 * - t8/b.b(1) returns false → WiFi check fails → Web Access won't start
 * - The toggle button appears to do nothing (no feedback, no error toast)
 *
 * This is WRONG because:
 * - The mobile hotspot creates a functional LAN even without Mobile Data
 * - Other devices connected to the hotspot CAN access Web Access
 * - The HTTP server should bind to the hotspot's local interface
 * - The "Cellular Access" setting only helps when Mobile Data is active
 *   (type 3), it does NOT help when there's no active data connection (type 1)
 *
 * ROOT CAUSE ANALYSIS:
 * The network type detection in xa/c.b(Context) works as follows:
 * 1. Get ConnectivityManager.getActiveNetworkInfo()
 * 2. If null → return singleton with type=1, IP=null (NO HOTSPOT CHECK!)
 * 3. If type=0 (mobile) → check isWifiApEnabled via reflection:
 *    - If hotspot enabled → return type=4, IP=hotspot IP
 *    - If not → return type=3, IP=mobile IP
 * 4. If type=1 (WiFi) → return type=2, IP=WiFi IP
 * 5. If type=9 → return type=5, IP=Ethernet IP
 *
 * When Mobile Data is OFF but hotspot is ON:
 * - getActiveNetworkInfo() returns NULL → type 1 (No network), IP=null
 * - The hotspot reflection check is NEVER reached because it's only
 *   performed inside the type=0 (mobile) branch
 * - This is the critical logic gap: hotspot detection requires Mobile Data!
 *
 * FIX STRATEGY (2 parts):
 *
 * Part 1 — Patch xa/c.b(Context) to check hotspot when NetworkInfo is null.
 * When getActiveNetworkInfo() returns null, instead of immediately returning
 * the "no network" singleton, we also check isWifiApEnabled() via reflection.
 * If the hotspot is active, we detect the IP via NetworkInterface enumeration
 * (same as the existing mobile+hotspot code path) and return type=4 with
 * the correct hotspot IP. This ensures:
 * - The Web Access server knows the correct hotspot IP to bind to
 * - The UI displays the correct IP and connection info to the user
 * - The t8/b.b() check receives type=4 (which already returns true)
 *
 * Part 2 — Patch t8/b.b(I)Z to return true for type 1 (No network).
 * This is a safety net: even if hotspot detection somehow fails (e.g.,
 * reflection blocked by OEM), type 1 (no network) will still allow
 * Web Access to start. The only type that returns false is type 3
 * (mobile data without hotspot), which correctly requires the
 * "Cellular Access" setting to be enabled.
 *
 * Network type constants (from xa/c class):
 *   1 = No network    → Part 1: becomes type 4 if hotspot active; Part 2: true
 *   2 = WiFi          → true (unchanged)
 *   3 = Mobile data   → false (unchanged) — requires "Cellular Access" setting
 *   4 = WiFi AP       → true (unchanged)
 *   5 = Ethernet      → true (unchanged)
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
        // === PART 1: Patch xa/c.b(Context) — add hotspot check when NetworkInfo is null ===
        //
        // Original xa/c.b() flow when getActiveNetworkInfo() returns null:
        //   0010: if-eqz v0, +071h  ← if (cm == null) goto return d (no network)
        //   ...
        //   001c: if-eqz v0, +06bh  ← if (ni == null) goto return d (no network)
        //   ...
        //   return d  ← singleton with a=1, b=null, c=null
        //
        // We need to intercept the second null check (NetworkInfo == null) and add
        // a hotspot detection step before returning the "no network" singleton.
        //
        // Injection point: right before the "return d" that happens when NetworkInfo is null.
        // We check isWifiApEnabled() via the static field xa/c.e (Method), and if true,
        // enumerate network interfaces to find the hotspot IP, then return a new
        // xa/c instance with type=4 (WiFi AP).
        //
        // Finding the injection point: look for the sget-object of field d followed by
        // return-object, which is the "no network" return path.
        //
        // Actually, the cleanest approach: find the second if-eqz (NetworkInfo null check)
        // and replace the goto target (which goes to "return d") with our hotspot check.

        NetworkInfoFingerprint.method.apply {
            // Find the second if-eqz that checks NetworkInfo == null
            // This is at approximately offset 0x001c in xa/c.b()
            val instructions = implementation!!.instructions

            // We look for the pattern: getActiveNetworkInfo() call followed by if-eqz
            var networkInfoNullCheckIndex = -1
            var getActiveNetworkInfoCount = 0

            for ((index, inst) in instructions.withIndex()) {
                if (inst.opcode == Opcode.INVOKE_VIRTUAL &&
                    inst is ReferenceInstruction &&
                    inst.reference.toString().contains("getActiveNetworkInfo")
                ) {
                    getActiveNetworkInfoCount++
                    if (getActiveNetworkInfoCount == 1) {
                        // The if-eqz should be 2 instructions after the invoke-virtual
                        // (move-result-object at index+1, if-eqz at index+2)
                        val ifEqzIndex = index + 2
                        if (ifEqzIndex < instructions.size &&
                            instructions[ifEqzIndex].opcode == Opcode.IF_EQZ
                        ) {
                            networkInfoNullCheckIndex = ifEqzIndex
                        }
                        break
                    }
                }
            }

            if (networkInfoNullCheckIndex == -1) {
                throw PatchException(
                    "Could not find NetworkInfo null check in xa/c.b(). " +
                        "The APK version may not be supported."
                )
            }

            // The if-eqz at networkInfoNullCheckIndex jumps to the "return d" (no network)
            // when NetworkInfo is null. We need to intercept this path.
            //
            // Strategy: Replace the if-eqz with code that:
            // 1. Checks if NetworkInfo is null
            // 2. If NOT null, continue to original code
            // 3. If null, check isWifiApEnabled via xa/c.e reflection
            // 4. If hotspot active, enumerate IPs and return type=4 xa/c
            // 5. If hotspot not active, fall through to original "return d"
            //
            // Register state at the if-eqz:
            // - v0 = NetworkInfo (null is being checked)
            // - v3 = Context (parameter, still available)
            // - v1 = null (from const/4 v1, 0 at offset 0x0028)
            //
            // We need more registers. Let me check the method's register count.
            // From dexdump: registers=8, ins=1, so p0=v7 (Context), v0-v6 available.

            // Actually, let me use a different approach. Instead of inserting complex
            // code into the middle of xa/c.b(), I'll add a helper method to the
            // extension and call it from the injection point.
            //
            // Even simpler: just add the hotspot check code inline. The method has
            // 8 registers (v0-v7, with v7=Context parameter). At the null check point,
            // v0 is NetworkInfo (null), v1 might be null, v2-v6 are free.
            //
            // The xa/c.e static field holds the isWifiApEnabled Method.
            // The xa/c.c() static method enumerates NetworkInterfaces for IP.
            // We can replicate the existing hotspot detection code from the mobile branch.

            // Replace the if-eqz at networkInfoNullCheckIndex with our hotspot check:
            // Remove the if-eqz and add our code with labels.
            removeInstruction(networkInfoNullCheckIndex)

            // At this point, v0 = NetworkInfo (could be null), v3 = Context (param)
            // We need to:
            // 1. If v0 != null, skip hotspot check (continue to original code)
            // 2. If v0 == null, check isWifiApEnabled
            // 3. If hotspot active, create xa/c(type=4, IP, null) and return
            // 4. If not active, goto original "return d"

            addInstructionsWithLabels(
                networkInfoNullCheckIndex,
                """
                    # v0 = NetworkInfo (may be null)
                    # v3 = Context parameter
                    # If NetworkInfo is NOT null, continue to original code
                    if-nez v0, :check_hotspot_on_null_network

                    # === NetworkInfo is null — check if WiFi AP (hotspot) is active ===
                    # This is the critical fix: when Mobile Data is OFF but hotspot is ON,
                    # getActiveNetworkInfo() returns null. We need to detect hotspot anyway.

                    # Get the isWifiApEnabled Method from static field xa/c.e
                    sget-object v0, Lxa/c;->e:Ljava/lang/reflect/Method;
                    if-eqz v0, :return_no_network

                    # Get WifiManager via Context.getSystemService("wifi")
                    invoke-virtual {v3}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
                    move-result-object v4
                    const-string v5, "wifi"
                    invoke-virtual {v4, v5}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
                    move-result-object v4
                    if-eqz v4, :return_no_network
                    check-cast v4, Landroid/net/wifi/WifiManager;

                    # Call isWifiApEnabled via reflection: xa/c.e.invoke(wifiManager, null)
                    const/4 v5, 0
                    invoke-virtual {v0, v4, v5}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v4

                    # Check if result is Boolean
                    instance-of v0, v4, Ljava/lang/Boolean;
                    if-eqz v0, :return_no_network

                    # Get boolean value
                    check-cast v4, Ljava/lang/Boolean;
                    invoke-virtual {v4}, Ljava/lang/Boolean;->booleanValue()Z
                    move-result v0

                    # If hotspot NOT enabled, return no network
                    if-eqz v0, :return_no_network

                    # === Hotspot IS active! Detect IP via NetworkInterface enumeration ===
                    # This is the same as the existing mobile+hotspot code path
                    invoke-static {}, Lxa/c;->c()Lt4/j;
                    move-result-object v0
                    if-nez v0, :got_hotspot_ip
                    const/4 v4, 0
                    goto :create_hotspot_result

                    :got_hotspot_ip
                    iget-object v0, v0, Lt4/j;->X:Ljava/lang/Object;
                    check-cast v0, Ljava/net/InetAddress;
                    invoke-virtual {v0}, Ljava/net/InetAddress;->getHostAddress()Ljava/lang/String;
                    move-result-object v4

                    :create_hotspot_result
                    # Create xa/c(IP, type=4, SSID=null) — type 4 = WiFi AP (Hotspot)
                    new-instance v0, Lxa/c;
                    const/4 v5, 4
                    const/4 v6, 0
                    invoke-direct {v0, v4, v5, v6}, Lxa/c;-><init>(Ljava/lang/String;ILjava/lang/String;)V
                    return-object v0

                    :return_no_network
                    # Hotspot not active — fall through to original "return d" code
                    # We need to jump to the sget-object d + return-object sequence
                    # This is at the end of the method
                    sget-object v0, Lxa/c;->d:Lxa/c;
                    return-object v0

                    :check_hotspot_on_null_network
                    # NetworkInfo is NOT null — continue with original code
                    nop
                """
            )
        }

        // === PART 2: Patch t8/b.b(I)Z — return true for type 1 (No network) ===
        //
        // Original bytecode of t8/b.b(I)Z (4 registers, ins=1, p0=v3):
        //   0000: const/4 v0, 0        // false
        //   0001: const/4 v1, 1        // true
        //   0002: if-eq v3, v1, +14    // if type==1 → return false (BUG!)
        //   0004: const/4 v2, 2
        //   0005: if-eq v3, v2, +10    // if type==2 → return true
        //   0007: const/4 v2, 3
        //   0008: if-eq v3, v2, +0c    // if type==3 → return false
        //   000a: const/4 v0, 4
        //   000b: if-eq v3, v0, +08    // if type==4 → return true
        //   000d: const/4 v0, 5
        //   000e: if-ne v3, v0, +03    // if type!=5 → throw
        //   0010: return v1            // type==5 → true
        //   0011: const/4 v3, 0
        //   0012: throw v3             // NPE for unknown
        //   0013: return v1            // true (type 4)
        //   0014: return v0            // false (type 3 → v0 was set to 4 but then 5...
        //                              //   actually at this point v0=0 from 0000 since
        //                              //   the reassignments at 000a and 000d are skipped)
        //                              //   Wait no — let me re-trace...
        //   0015: return v1            // true (type 2)
        //   0016: return v0            // false (type 1 → v0=0 from 0000)
        //
        // After Part 1, when hotspot is active without Mobile Data, xa/c.b() now
        // returns type=4 instead of type=1, so t8/b.b() would return true naturally.
        // However, we still patch t8/b.b() as a safety net for edge cases where
        // hotspot detection fails or for any other "no network" scenario where the
        // user explicitly wants to try starting Web Access.
        //
        // New behavior: Only type 3 (Mobile data without hotspot) returns false.
        // All other types return true (allowing Web Access to start).

        NetworkTypeCheckerFingerprint.method.apply {
            // Instead of replacing the entire method (which doesn't work reliably),
            // we inject a check at the very beginning of the method:
            // if (type != 3) return true;  // Allow all types except mobile data
            //
            // If type == 3 (mobile data), fall through to the original method
            // which will check the "Cellular Access" setting via the caller.
            // Actually, the original t8/b.b(3) returns false, which is correct —
            // mobile data requires "Cellular Access" to be enabled.
            //
            // Register layout: 4 registers (v0-v3), p0=v3 (input parameter)
            // v0 and v1 are free at method start (not yet assigned)

            addInstructionsWithLabels(
                0,
                """
                    # Safety net: allow all network types except type 3 (mobile data)
                    # Type 3 should still require "Cellular Access" setting
                    const/4 v0, 1
                    const/4 v1, 3
                    if-eq p0, v1, :check_cellular_setting
                    return v0
                    :check_cellular_setting
                    # Type 3 (mobile data) — fall through to original code
                    # Original code returns false for type 3
                    nop
                """
            )
        }
    }
}
