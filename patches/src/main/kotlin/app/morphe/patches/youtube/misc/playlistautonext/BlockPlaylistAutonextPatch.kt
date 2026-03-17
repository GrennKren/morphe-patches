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

// Targets: alnj.c(Lalyc;) — getNavigationDescriptor
// Identified by unique string "getNavigationDescriptor for "
//
// Smali structure:
//   [0] invoke-direct  w()
//   [1] move-result-object v0
//   [2] iget-object v1, alnj->a
//   [3] invoke-interface almw->d(alyc)   ← fetch descriptor into v1
//   [4] move-result-object v1
//   [5] const/4 v2, 0x0
//   [6] invoke-direct x(Object,Z)
//   [7] if-nez v1, :cond_30              ← INJECT BEFORE HERE
//   ... null path ...
//   :cond_30
//   iget-object p1, p1, Lalyc;->e:Lalyb ← read nav type
//   check AUTOPLAY / AUTONAV → set flags → return

internal object AlnjGetNavigationDescriptorFingerprint : Fingerprint(
    returnType = "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
    parameters = listOf("L"),
    strings = listOf("getNavigationDescriptor for "),
    filters = listOf(
        opcode(Opcode.INVOKE_DIRECT),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.INVOKE_INTERFACE),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.CONST_4),
        opcode(Opcode.INVOKE_DIRECT),
        opcode(Opcode.IF_NEZ),
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

        // Inject before IF_NEZ (index 7).
        // At this point v1 = descriptor, p1 = alyc.
        // Read p1.e (alyb enum), call extension, if blocked → return null.
        AlnjGetNavigationDescriptorFingerprint.method.apply {
            val insertIndex = 7

            addInstructionsWithLabels(
                insertIndex,
                """
                    iget-object v0, p1, Lalyc;->e:Lalyb;
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :allow_autonext
                    const/4 p1, 0x0
                    return-object p1
                    :allow_autonext
                    nop
                """,
            )
        }
    }
}