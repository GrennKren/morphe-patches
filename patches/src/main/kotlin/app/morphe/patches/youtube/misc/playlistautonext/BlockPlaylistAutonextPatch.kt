/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.playlistautonext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.playerControlsPatch
import app.morphe.patches.youtube.misc.playercontrols.playerControlsResourcePatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_PATCH_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BlockPlaylistAutonextPatch;"

private const val EXTENSION_BUTTON_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/videoplayer/BlockPlaylistAutonextButton;"

// ── Fingerprint ──────────────────────────────────────────────────────────────
// Multi-version support for blocking playlist auto-next navigation.
//
// YouTube 20.45.x:
//   - Target: amah.d(alzf)V
//   - Calls: bjex.z(alzf) → amrv
//   - Nav data: Lalzf; with field e:Lalze;
//
// YouTube 20.44.x:
//   - Target: alzf.d(alyc)V
//   - Calls: bjcl.z(alyc) → amqs
//   - Nav data: Lalyc; with field e:Lalyb;

// Fingerprint for YouTube 20.45.x
internal object NavigationFingerprintV20_45 : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = "Lbjex;",
            name = "z",
            parameters = listOf("Lalzf;"),
            returnType = "Lamrv;",
        ),
        methodCall(
            definingClass = "Landroid/text/TextUtils;",
            name = "equals",
            parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;"),
            returnType = "Z",
        ),
    ),
)

// Fingerprint for YouTube 20.44.x
internal object NavigationFingerprintV20_44 : Fingerprint(
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

// ── Resource patch ───────────────────────────────────────────────────────────

private val blockPlaylistAutonextButtonResourcePatch = resourcePatch {
    dependsOn(playerControlsResourcePatch)

    execute {
        // Copy drawable icons for the toggle button
        copyResources(
            "blockplaylistautonextbutton",
            ResourceGroup(
                "drawable",
                "morphe_block_playlist_autonext_on.xml",
                "morphe_block_playlist_autonext_off.xml",
            )
        )

        // Add button to the player controls overlay (same row as copy URL timestamp button)
        addBottomControl("blockplaylistautonextbutton")
    }
}

// ── Main patch ───────────────────────────────────────────────────────────────

@Suppress("unused")
val blockPlaylistAutonextPatch = bytecodePatch(
    name = "Block playlist auto-next",
    description = "Adds an option to block automatic navigation to the next video in playlists and mixes, with a toggle button in the player overlay.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        blockPlaylistAutonextButtonResourcePatch,
        playerControlsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Settings: main toggle (default) + button visibility
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_block_playlist_autonext"),
            SwitchPreference("morphe_block_playlist_autonext_button"),
        )

        // Hook main activity
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_PATCH_CLASS_DESCRIPTOR->setMainActivity(Landroid/app/Activity;)V",
        )

        // Try to match fingerprint for YouTube 20.45.x first
        val fingerprintV20_45Result = NavigationFingerprintV20_45.matchOrNull()

        if (fingerprintV20_45Result != null) {
            // YouTube 20.45.x detected
            // Inject into amah.d(alzf)
            // Field alzf.e contains alze enum (AUTONAV, AUTOPLAY, NEXT, etc.)
            fingerprintV20_45Result.method.addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p1, Lalzf;->e:Lalze;
                    invoke-static { v0 }, $EXTENSION_PATCH_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :allow_autonext
                    return-void
                    :allow_autonext
                    nop
                """,
            )
        } else {
            // Try fingerprint for YouTube 20.44.x
            val fingerprintV20_44Result = NavigationFingerprintV20_44.matchOrNull()
                ?: throw PatchException(
                    "Failed to match any fingerprint. " +
                    "Supported versions: 20.44.38, 20.45.36"
                )

            // YouTube 20.44.x detected
            // Inject into alzf.d(alyc)
            // Field alyc.e contains alyb enum (AUTONAV, AUTOPLAY, NEXT, etc.)
            fingerprintV20_44Result.method.addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p1, Lalyc;->e:Lalyb;
                    invoke-static { v0 }, $EXTENSION_PATCH_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :allow_autonext
                    return-void
                    :allow_autonext
                    nop
                """,
            )
        }

        // Initialize player overlay toggle button
        initializeBottomControl(EXTENSION_BUTTON_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(EXTENSION_BUTTON_CLASS_DESCRIPTOR)
    }
}
