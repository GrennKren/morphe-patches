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
// Matches the navigation event handler in the YouTube sequencer.
//
// Strategy: Do not hardcode obfuscated class names (amfc, amfa, etc.).
// Primary anchor  : TextUtils.equals — Android SDK, never renamed.
// Secondary anchor: PlaybackStartDescriptor — YouTube SDK class, not obfuscated.
//
// The actual method signature is e(Lamfk;Lamfc;)V where:
//   - p1 = Lamfk (cast to Lamgq, the playback request)
//   - p2 = Lamfc (navigation event containing navType enum in field "c")
//
// The second parameter's class (Lamfc) has:
//   - field b: I          (index)
//   - field c: Lamfa      (navType enum: NONE, PREV, NEXT, AUTOPLAY, AUTONAV, JUMP)
//   - field d: Lamfb      (playback start descriptor mutator)
//
// The PlaybackStartDescriptor method call is critical for uniqueness:
// without it, the fingerprint matches ~25 methods that also call
// TextUtils.equals with two reference parameters. Adding the
// PlaybackStartDescriptor return-type filter narrows the match to
// only the navigation handler.

internal object NavigationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L"),
    filters = listOf(
        methodCall(
            returnType = "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
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

        // The second parameter contains the navigation type enum.
        // p1 = playback request (Lamfk), p2 = navigation event (Lamfc)
        val navParamType = method.parameterTypes[1].toString()

        // Get the navigation event class definition directly.
        val navParamClassDef = classDefBy(navParamType)

        // Find the enum-typed field in the navigation event class.
        // In 20.47.62 the field is named "c" of type Lamfa, but obfuscated
        // names shift across versions. Detect the field dynamically by
        // checking which field's type extends java.lang.Enum.
        var enumFieldName: String? = null
        var enumFieldType: String? = null

        for (field in navParamClassDef.fields) {
            val fType = field.type.toString()
            if (!fType.startsWith("L")) continue

            // Look up the field's class and check if it is an enum.
            val fieldClassDef = try {
                classDefBy(fType)
            } catch (_: Exception) {
                continue
            }

            if (fieldClassDef.superclass == "Ljava/lang/Enum;") {
                enumFieldName = field.name
                enumFieldType = fType
                break
            }
        }

        enumFieldType ?: throw PatchException(
            "Could not find enum field in $navParamType. " +
            "This YouTube version may not be supported."
        )

        // p2 = second parameter (navigation event object)
        method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p2, $navParamType->$enumFieldName:$enumFieldType
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
