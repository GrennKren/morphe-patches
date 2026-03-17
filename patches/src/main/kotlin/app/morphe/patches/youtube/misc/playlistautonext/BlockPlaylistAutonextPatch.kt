/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.playlistautonext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BlockPlaylistAutonextPatch;"

// Targets alzf.d(Lalyc;)V
// Uniquely identified by TextUtils.equals() call inside the method.
internal object AlzfNavigationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/text/TextUtils;",
            name = "equals",
        ),
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

        // Hook main activity for Toast context (same as ShortsAutoplayPatch)
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->setMainActivity(Landroid/app/Activity;)V",
        )

        // Inject at start of alzf.d(alyc):
        // Read alyc.e (nav type enum), call extension.
        // If blocked → return-void (navigation never executes).
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