/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.playlistautonext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

// ─────────────────────────────────────────────────────────────────────────────
// Extension class descriptor
// ─────────────────────────────────────────────────────────────────────────────

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BlockPlaylistAutonextPatch;"

// ─────────────────────────────────────────────────────────────────────────────
// Fingerprint
//
// Targets: moj.c(Lalyc;)Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;
//
// This is the method that returns the PlaybackStartDescriptor for the next
// video in a playlist/mix. When it returns null, navigation is blocked.
//
// Smali structure of the method:
//
//   iget-object v0, p1, Lalyc;->e:Lalyb;      ← index 0  (READ nav type)
//   invoke-direct {p0, v0}, Lmoj;->x(Lalyb;)Z ← index 1  (connection check)
//   move-result v0                             ← index 2
//   if-eqz v0, :cond_a                        ← index 3  (branch if NOT blocked)
//   const/4 p1, 0x0                            ← index 4
//   return-object p1                           ← index 5  (return null = block)
//   :cond_a                                    ← index 6
//   iget-object v0, p0, Lmoj;->f:Lalye;       ← index 7
//   invoke-interface {v0, p1}, Lalye;->c(...)  ← index 8
//   move-result-object p1                      ← index 9
//   return-object p1                           ← index 10
//
// ─────────────────────────────────────────────────────────────────────────────

internal object MojAutonextFingerprint : Fingerprint(
    // Return type: PlaybackStartDescriptor
    returnType = "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
    // Parameters: one object (alyc navigation command)
    parameters = listOf("L"),
    // Opcode pattern matching the smali structure above
    opcodes = listOf(
        Opcode.IGET_OBJECT,      // iget-object v0, p1, Lalyc;->e:Lalyb
        Opcode.INVOKE_DIRECT,    // invoke-direct x(Lalyb)Z  (connection check)
        Opcode.MOVE_RESULT,      // move-result v0
        Opcode.IF_EQZ,           // if-eqz v0, :cond_a
        Opcode.CONST_4,          // const/4 p1, 0x0
        Opcode.RETURN_OBJECT,    // return-object p1  (null = block)
        Opcode.IGET_OBJECT,      // iget-object v0, p0, Lmoj;->f:Lalye
        Opcode.INVOKE_INTERFACE, // invoke-interface Lalye;->c(Lalyc;)
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.RETURN_OBJECT,    // return-object p1
    ),
    // No string literals in this method, so we rely solely on opcode pattern
    strings = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────────
// Patch definition
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val blockPlaylistAutonextPatch = bytecodePatch(
    name = "Block playlist auto-next",
    description = "Adds an option to block automatic navigation to the next video in playlists and mixes.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // ── 1. Register the setting in the Player preference screen ───────────
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_block_playlist_autonext"),
        )

        // ── 2. Inject into moj.c(alyc) ───────────────────────────────────────
        //
        // Strategy:
        //   • The method reads `alyc.e` (the alyb nav-type enum) at index 0.
        //   • We inject BEFORE index 0, so we get the full alyc object (p1).
        //   • We call our extension with the alyb enum value read from p1.
        //   • If the extension returns true → return null (block navigation).
        //   • Otherwise → fall through to original logic.
        //
        MojAutonextFingerprint.method.apply {

            // Index 0 = first IGET_OBJECT (reads alyc.e into v0)
            // We insert our check right before it.
            val insertIndex = 0

            // p1 = the alyc parameter (register 1 in a 3-register method)
            // After our iget, v0 will hold the alyb enum value.
            // We use v0 as a scratch register (safe: it's written at index 0 anyway).

            addInstructionsWithLabels(
                insertIndex,
                """
                    # ── BlockPlaylistAutonextPatch injection ──────────────────
                    # Read the navigation type enum (alyc.e = alyb) from parameter p1
                    iget-object v0, p1, Lalyc;->e:Lalyb;

                    # Call extension: shouldBlockNavType(Enum) → boolean
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0

                    # If false (not blocked), skip to original code
                    if-eqz v0, :allow_autonext

                    # Block: return null (same as original block path)
                    const/4 p1, 0x0
                    return-object p1

                    # Original code continues here
                    :allow_autonext
                    nop
                    # ─────────────────────────────────────────────────────────
                """,
            )
        }
    }
}
