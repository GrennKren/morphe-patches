/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.playlistautonext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BlockPlaylistAutonextPatch;"

// Targets alzf.d(Lalyc;)V
//
// Full smali structure of d():
//   iget-object       Lalzf;->b:Lbjcl              ← get bjcl field
//   invoke-virtual    Lbjcl;->z(Lalyc;)Lamqs       ← resolve nav → amqs
//   move-result-object
//   if-eqz            → :cond_41 (return-void)
//   iget-object       Lamqs;->b
//   check-cast        PlaybackStartDescriptor
//   iget-object       PlaybackStartDescriptor->a:Lpkj
//   iget-boolean      Lpkj;->m:Z
//   if-eqz            → :cond_34
//   ...
//   invoke-static     TextUtils->equals(CharSequence,CharSequence)Z
//   ...
//   invoke-virtual    Laqkl;->g(PlaybackStartDescriptor;Lalsl;Z)V  ← execute nav
//   return-void
//
// We use a combination of filters to uniquely identify this method:
// 1. invoke-virtual  Lbjcl;->z(Lalyc;)Lamqs  — unique call to resolve nav
// 2. invoke-static   TextUtils->equals        — unique static call
// Both together should be very specific to alzf.d()

internal object AlzfNavigationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        // Filter 1: bjcl->z(alyc) returns amqs — very specific to this method
        methodCall(
            definingClass = "Lbjcl;",
            name = "z",
            parameters = listOf("Lalyc;"),
            returnType = "Lamqs;",
        ),
        // Filter 2: TextUtils.equals call
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