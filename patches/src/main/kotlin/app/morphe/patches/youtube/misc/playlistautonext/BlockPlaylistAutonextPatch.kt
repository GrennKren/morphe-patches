/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.playlistautonext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BlockPlaylistAutonextPatch;"

// Targets alzf.d(alyc)V
//
// alzf has no strings in d(), but has unique strings in b():
//   "Initializing a PlaybackSequencer in loaderNavigator, but it does not exist"
//   "Initializing a PlaybackSequencer for playback continuation, but it does not exist"
//
// So we use the class-level string to find alzf, then target d() by:
//   - returnType = "V"
//   - parameters = ["L"] (single alyc parameter)
//   - Short opcode pattern unique to d()
//
// d() smali:
//   [0] IGET_OBJECT        iget bjcl field
//   [1] INVOKE_VIRTUAL     bjcl->z(alyc) → amqs
//   [2] MOVE_RESULT_OBJECT
//   [3] IF_EQZ             null check → jump to return
//   [4] IGET_OBJECT        amqs->b (PlaybackStartDescriptor)
//   [5] CHECK_CAST
//   [6] IGET_OBJECT        descriptor->a (pkj)
//   [7] IGET_BOOLEAN       pkj->m
//   [8] IF_EQZ

internal object AlzfNavigationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("Initializing a PlaybackSequencer in loaderNavigator, but it does not exist"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.INVOKE_VIRTUAL),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.IF_EQZ),
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.CHECK_CAST),
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.IGET_BOOLEAN),
        opcode(Opcode.IF_EQZ),
    ),
)

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
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_block_playlist_autonext"),
        )

        AlzfNavigationFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p1, Lalyc;->e:Lalyb;
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :allow_autonext
                    return-void
                    :allow_autonext
                    nop
                """,
            )
        }
    }
}