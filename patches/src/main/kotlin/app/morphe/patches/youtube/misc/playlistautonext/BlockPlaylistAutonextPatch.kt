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
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsResourcePatch
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
// Single fingerprint for all supported versions.
//
// Strategy: Do not hardcode obfuscated class names (bjex/bjcl, alzf/alyc, etc.).
// Primary anchor  : TextUtils.equals — Android SDK, never renamed.
// Secondary anchor: method "z" with a single object parameter + object return,
//                   called before TextUtils.equals.
//
// This pattern is consistent across all versions because:
//   - returnType "V", parameters ["L"] — unchanged
//   - Always calls a navigation method "z", then a string comparison
//   - TextUtils.equals is always present to check the navigation type

internal object NavigationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            name = "z",               // method name does not change
            parameters = listOf("L"), // single object parameter (alzf / alyc / ...)
            returnType = "L",         // returns an object (amrv / amqs / ...)
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
    dependsOn(
        settingsPatch,
        legacyPlayerControlsResourcePatch
    )

    execute {
        // Copy drawable icons for both button systems
        // Bold (new overlay) + Legacy (old XML layout)
        copyResources(
            "blockplaylistautonextbutton",
            ResourceGroup(
                "drawable",
                "morphe_block_playlist_autonext_on.xml",
                "morphe_block_playlist_autonext_off.xml",
                "morphe_block_playlist_autonext_bold.xml",
                "morphe_block_playlist_autonext_off_bold.xml",
            )
        )

        // Add button layout to the legacy bottom controls
        addLegacyBottomControl("blockplaylistautonextbutton")
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
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Settings: main toggle + button visibility
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_block_playlist_autonext"),
            SwitchPreference("morphe_block_playlist_autonext_button"),
        )

        // Hook main activity
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_PATCH_CLASS_DESCRIPTOR->setMainActivity(Landroid/app/Activity;)V",
        )

        val result = NavigationFingerprint.match()
        val method = result.method

        // Read its type directly from the method signature
        val navParamType = method.parameterTypes[0].toString()
        
        var enumFieldType: String? = null

        classDefForEach { classDef ->
            if (classDef.type != navParamType) return@classDefForEach
            enumFieldType = classDef.fields
                .firstOrNull { it.name == "e" }
                ?.type
        }

        enumFieldType ?: throw PatchException(
            "Could not find field 'e' in $navParamType. " +
            "This YouTube version may not be supported."
        )

        method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, $navParamType->e:$enumFieldType
                invoke-static { v0 }, $EXTENSION_PATCH_CLASS_DESCRIPTOR->shouldBlockNavType(Ljava/lang/Enum;)Z
                move-result v0
                if-eqz v0, :allow_autonext
                return-void
                :allow_autonext
                nop
            """,
        )

        // ── New bold overlay button system ──
        // Injects initializeButton(View) call into the fullscreen button creation method.
        // The View parameter is the fullscreen button itself, used as position/style anchor.
        addPlayerBottomButton(EXTENSION_BUTTON_CLASS_DESCRIPTOR)

        // ── Legacy button system ──
        initializeLegacyBottomControl(EXTENSION_BUTTON_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(EXTENSION_BUTTON_CLASS_DESCRIPTOR)
    }
}
