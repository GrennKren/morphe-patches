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

// Targets: alzf.d(Lalyc;)V — the concrete implementation of aksi.d()
// This is what actually executes navigation when autonav/autoplay fires.
//
// Called from alju.run() case 4 (AUTONAV) and case 3 (AUTOPLAY):
//   ((aksi) alxyVar.c.md()).d(new alyc(alyb.AUTONAV, null, ...))
//
// Smali structure:
//   [0] iget-object v0, p0, Lalzf;->b:Lbjcl
//   [1] invoke-virtual {v0,p1}, Lbjcl;->z(Lalyc;)Lamqs;   ← resolve nav
//   [2] move-result-object p1
//   [3] if-eqz p1, :cond_41                               ← skip if null
//   ...
//   invoke-static TextUtils->equals(...)                   ← unique identifier
//   ...
//   [:cond_41] return-void
//
// Injection: before index 0 — read alyc.e, if AUTONAV/AUTOPLAY → return-void

internal object AlzfNavigationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),       // iget bjcl
        opcode(Opcode.INVOKE_VIRTUAL),    // bjcl->z(alyc)
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.IF_EQZ),            // if-eqz → :cond_41
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.CHECK_CAST),        // cast to PlaybackStartDescriptor
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.IGET_BOOLEAN),      // pkj->m
        opcode(Opcode.IF_EQZ),
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.IF_EQZ),
        opcode(Opcode.SGET_OBJECT),       // alsw->j (ENDED state)
        opcode(Opcode.INVOKE_INTERFACE),  // ao(alsw)
        opcode(Opcode.MOVE_RESULT),
        opcode(Opcode.IF_EQZ),
        opcode(Opcode.INVOKE_VIRTUAL),    // v() → videoId
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.INVOKE_INTERFACE),  // q() → currentId
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.INVOKE_STATIC),     // TextUtils.equals(...)
        opcode(Opcode.MOVE_RESULT),
        opcode(Opcode.IF_EQZ),
        opcode(Opcode.INVOKE_VIRTUAL),    // alzp->H()
        opcode(Opcode.RETURN_VOID),
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

        // Inject at index 0, before anything runs.
        // p1 = alyc (the navigation command).
        // Read p1.e (alyb enum), call extension.
        // If blocked → return-void immediately, navigation never happens.
        AlzfNavigationFingerprint.method.apply {
            val insertIndex = 0

            addInstructionsWithLabels(
                insertIndex,
                """
                    # Read nav type from alyc.e
                    iget-object v0, p1, Lalyc;->e:Lalyb;

                    # shouldBlockNavType(Enum) → boolean
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0

                    # If false → allow, skip to original code
                    if-eqz v0, :allow_autonext

                    # Block: return without doing anything
                    return-void

                    :allow_autonext
                    nop
                """,
            )
        }
    }
}