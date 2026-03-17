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

// Targets alzf.d(Lalyc;)V — the concrete implementation of aksi.d()
// Called from alju.run() case 4 (AUTONAV) and case 3 (AUTOPLAY).
// Uniquely identified by two method calls:
//   1. Lbjcl;->z(Lalyc;)Lamqs  — resolves navigation command
//   2. TextUtils.equals(...)    — compares video IDs

internal object AlzfNavigationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = "Lbjcl;",
            name = "z",
            parameters = listOf("Lalyc;"),
            returnType = "Lamqs;",
        ),
        methodCall(
            definingClass = "Landroid/text/TextUtils;",
            name = "equals",
            parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;"),
            returnType = "Z",
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

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->setMainActivity(Landroid/app/Activity;)V",
        )

        // Inject at start of alzf.d(alyc):
        // 1. Read alyc.e (nav type: AUTONAV, AUTOPLAY, NEXT, etc.)
        // 2. Call extension to check if should block
        // 3. If blocked:
        //    a. Call alzp.H() to dismiss the playlist panel UI
        //    b. return-void to prevent navigation
        // 4. If not blocked: fall through to original code
        //
        // alzf fields used:
        //   p0 = this (alzf instance)
        //   p0.a = alzp instance  (Lalzf;->a:Lalzp)
        //   alzp.H() = dismisses/resets the sequence navigator UI state
        AlzfNavigationFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    # Read nav type from alyc.e (alyb enum)
                    iget-object v0, p1, Lalyc;->e:Lalyb;

                    # Call extension: shouldBlockNavType(Enum) → boolean
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0

                    # If false (not blocked), skip to original code
                    if-eqz v0, :allow_autonext

                    # Block: dismiss playlist panel UI via alzp.H()
                    iget-object v0, p0, Lalzf;->a:Lalzp;
                    invoke-virtual { v0 }, Lalzp;->H()V

                    # Return without executing navigation
                    return-void

                    :allow_autonext
                    nop
                """,
            )
        }
    }
}