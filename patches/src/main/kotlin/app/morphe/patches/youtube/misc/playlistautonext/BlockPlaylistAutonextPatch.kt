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

// ── Fingerprints ─────────────────────────────────────────────────────────────
// Match the navigation event handler in the YouTube sequencer.
//
// Strategy: Do not hardcode obfuscated class names.
// Primary anchor  : TextUtils.equals — Android SDK, never renamed.
// Secondary anchor: PlaybackStartDescriptor — YouTube SDK class, not obfuscated.
//
// YouTube 20.47+ restructured the navigation handler:
//   - Old (20.45): 1-param method calling method "z" + TextUtils.equals
//   - New (20.47): 2-param method with PlaybackStartDescriptor + TextUtils.equals
//   - New (20.47): 1-param fallback with PlaybackStartDescriptor + TextUtils.equals (no method "z")
//
// The PlaybackStartDescriptor method call is critical for uniqueness:
// without it, the fingerprint matches ~25 methods that also call
// TextUtils.equals with two reference parameters. Adding the
// PlaybackStartDescriptor return-type filter narrows the match to
// only the navigation handler.

// YouTube 20.47+ 2-param navigation handler (e.g. Lanah;->e(Lamfk;Lamfc;)V)
// p1 = playback request, p2 = navigation event containing navType enum
internal object NavigationFingerprintV2 : Fingerprint(
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

// YouTube 20.45 and earlier 1-param navigation handler (e.g. Lamah;->d(Lalzf;)V)
// p1 = navigation event containing navType enum, uses method "z" pattern
internal object NavigationFingerprintV1 : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            name = "z",
            parameters = listOf("L"),
            returnType = "L",
        ),
        methodCall(
            definingClass = "Landroid/text/TextUtils;",
            name = "equals",
            parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;"),
            returnType = "Z",
        ),
    ),
)

// YouTube 20.47 1-param fallback navigation handler (e.g. Lamfs;->d(Lameq;)V)
// p1 = navigation event containing navType enum, has PlaybackStartDescriptor but no method "z"
internal object NavigationFingerprintV1Fallback : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
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

        // Try fingerprints in order:
        //   V2        — 2-param handler (YouTube 20.47+)
        //   V1        — 1-param handler with method "z" (YouTube 20.45 and earlier)
        //   V1Fallback — 1-param handler with PlaybackStartDescriptor but no method "z" (YouTube 20.47)
        val result = try {
            NavigationFingerprintV2.match()
        } catch (_: Exception) {
            try {
                NavigationFingerprintV1.match()
            } catch (_: Exception) {
                NavigationFingerprintV1Fallback.match()
            }
        }

        val method = result.method

        // For 2-param (V2): nav event is the second parameter (p2).
        // For 1-param (V1/V1Fallback): nav event is the first parameter (p1).
        val isTwoParam = method.parameterTypes.size >= 2
        val navParamIndex = if (isTwoParam) 1 else 0
        val navParamType = method.parameterTypes[navParamIndex].toString()
        val navParamReg = if (isTwoParam) "p2" else "p1"

        // ── Dynamic enum field detection ──
        // The obfuscated field name holding the navigation type enum differs
        // across YouTube versions (e.g. "e" in 20.45, "c" in 20.47 V2, "e" in 20.47 V1Fallback).
        // Instead of hardcoding the field name, we detect it dynamically by:
        //   1. Collecting all class types whose superclass is java.lang.Enum
        //   2. Finding the field in the nav parameter class whose type is one of those enums

        // Pass 1: collect all enum class types
        val enumTypes = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.superclass == "Ljava/lang/Enum;") {
                enumTypes.add(classDef.type)
            }
        }

        // Pass 2: find the enum-typed field in the navigation event class
        var enumFieldName: String? = null
        var enumFieldType: String? = null

        classDefForEach { classDef ->
            if (classDef.type != navParamType) return@classDefForEach

            classDef.fields.forEach { field ->
                val fType = field.type.toString()
                if (fType in enumTypes) {
                    enumFieldName = field.name
                    enumFieldType = fType
                }
            }
        }

        enumFieldType ?: throw PatchException(
            "Could not find enum field in $navParamType. " +
            "This YouTube version may not be supported."
        )

        // Inject early-return check: if the navigation type should be blocked,
        // return before the sequencer can process the navigation.
        method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, $navParamReg, $navParamType->$enumFieldName:$enumFieldType
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
