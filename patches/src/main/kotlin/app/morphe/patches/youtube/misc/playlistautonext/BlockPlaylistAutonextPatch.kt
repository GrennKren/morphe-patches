/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.playlistautonext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode

// ─────────────────────────────────────────────────────────────────────────────
// Extension class descriptor
// ─────────────────────────────────────────────────────────────────────────────

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BlockPlaylistAutonextPatch;"

// ─────────────────────────────────────────────────────────────────────────────
// Fingerprint
//
// Targets: moj.c(Lalyc;)Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;
// ─────────────────────────────────────────────────────────────────────────────

internal object MojAutonextFingerprint : Fingerprint(
    returnType = "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.INVOKE_DIRECT),
        opcode(Opcode.MOVE_RESULT),
        opcode(Opcode.IF_EQZ),
        opcode(Opcode.CONST_4),
        opcode(Opcode.RETURN_OBJECT),
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.INVOKE_INTERFACE),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.RETURN_OBJECT),
    ),
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
        // Register the setting in the Player preference screen
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_block_playlist_autonext"),
        )

        // Inject into moj.c(alyc)
        MojAutonextFingerprint.method.apply {
            val insertIndex = 0

            addInstructionsWithLabels(
                insertIndex,
                """
                    # Read the navigation type enum (alyc.e = alyb) from parameter p1
                    iget-object v0, p1, Lalyc;->e:Lalyb;

                    # Call extension: shouldBlockNavType(Enum) → boolean
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0

                    # If false (not blocked), skip to original code
                    if-eqz v0, :allow_autonext

                    # Block: return null
                    const/4 p1, 0x0
                    return-object p1

                    :allow_autonext
                    nop
                """,
            )
        }
    }
}