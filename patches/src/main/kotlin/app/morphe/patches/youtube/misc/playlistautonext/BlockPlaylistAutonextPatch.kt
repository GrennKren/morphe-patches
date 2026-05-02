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
//
// YouTube's navigation sequencer has evolved across versions:
//
//   YouTube 20.45 and earlier:
//     Single 1-param handler: Lamah;->d(Lalzf;)V
//     - Lalzf has enum field "e" of type Lalze (navType enum)
//     - Calls method "z" on the parameter, then TextUtils.equals
//
//   YouTube 20.47+:
//     TWO navigation handlers exist:
//     V2 (primary):     2-param handler with PlaybackStartDescriptor
//     V1Fallback:       1-param handler (legacy fallback path)
//
//     V2 (e.g. Lanah;->e(Lamfk;Lamfc;)V):
//     - p1 = playback request, p2 = navigation event
//     - Calls a method returning PlaybackStartDescriptor
//     - Calls TextUtils.equals to check navigation type
//
//     V1Fallback (e.g. someClass;->someMethod(Lameq;)V):
//     - 1 parameter (navigation event object)
//     - Calls TextUtils.equals to check navigation type
//     - Does NOT call PlaybackStartDescriptor
//
// CRITICAL: On YouTube 20.47+, playlist auto-next may go through the
// V1Fallback path instead of (or in addition to) the V2 path. We must
// hook ALL navigation handlers to ensure blocking works.
//
// Strategy:
//   1. Find nav type enum classes via "AUTONAV" string constant
//   2. Find nav event classes that have a field of those enum types
//   3. V2: Specific fingerprint with PlaybackStartDescriptor
//   4. V1: Broad fingerprint + filter by nav event parameter type

// YouTube 20.47+ 2-param navigation handler (e.g. Lanah;->e(Lamfk;Lamfc;)V)
// Very specific: PlaybackStartDescriptor + TextUtils.equals + 2 params
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

// Broad 1-param fingerprint for V1/V1Fallback navigation handlers.
// Requires post-filtering by checking the parameter type against known
// navigation event classes (found via nav type enum string search).
internal object NavigationFingerprintV1 : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
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

        // ── Helper: find enum-typed field in a class ──
        // Returns Pair(field name, field type) or null.
        fun findEnumFieldInClass(classType: String): Pair<String, String>? {
            val classDef = classDefByOrNull(classType) ?: return null

            for (field in classDef.fields) {
                val fType = field.type.toString()
                if (!fType.startsWith("L")) continue

                val fieldClassDef = classDefByOrNull(fType) ?: continue
                if (fieldClassDef.superclass == "Ljava/lang/Enum;") {
                    return Pair(field.name, fType)
                }
            }

            return null
        }

        // ── Collect ALL navigation handlers to hook ──
        data class HandlerInfo(
            val method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod,
            val navParamReg: String,
            val navParamType: String,
            val enumFieldName: String,
            val enumFieldType: String,
        )

        val handlers = mutableListOf<HandlerInfo>()

        // ── Step 1: Find nav type enum classes via "AUTONAV" string ──
        // The navigation type enum (e.g. Lamfa, Lamep, Lalze) always contains
        // "AUTONAV" as a string constant (used in enum valueOf/switch).
        val navTypeEnumTypes = mutableSetOf<String>()
        for (enumClass in classDefByStrings("AUTONAV")) {
            if (enumClass.superclass == "Ljava/lang/Enum;") {
                navTypeEnumTypes.add(enumClass.type)
            }
        }

        // ── Step 2: Find nav event classes that have a field of nav type enum ──
        // These are the parameter types passed to navigation handlers.
        val navEventClassTypes = mutableSetOf<String>()
        classDefForEach { classDef ->
            for (field in classDef.fields) {
                if (field.type.toString() in navTypeEnumTypes) {
                    navEventClassTypes.add(classDef.type)
                    break
                }
            }
        }

        // ── Step 3: Hook V2 handler (2-param with PlaybackStartDescriptor) ──
        NavigationFingerprintV2.matchOrNull()?.let { result ->
            val method = result.method
            val navParamType = method.parameterTypes[1].toString()

            // Verify the second parameter is a known nav event class
            if (navParamType in navEventClassTypes) {
                val enumField = findEnumFieldInClass(navParamType)
                if (enumField != null) {
                    handlers.add(
                        HandlerInfo(method, "p2", navParamType, enumField.first, enumField.second)
                    )
                }
            }
        }

        // ── Step 4: Hook V1/V1Fallback handlers (1-param with TextUtils.equals) ──
        // Use broad fingerprint then filter by nav event parameter type.
        // This catches the V1Fallback on 20.47+ and the V1 handler on 20.45-.
        val v1Matches = NavigationFingerprintV1.matchAllOrNull() ?: emptyList()
        for (match in v1Matches) {
            val method = match.method
            val navParamType = method.parameterTypes[0].toString()

            // Skip if we already hooked this method via V2 fingerprint
            if (handlers.any { it.method == method }) continue

            // Only hook if the parameter type is a known nav event class
            if (navParamType !in navEventClassTypes) continue

            val enumField = findEnumFieldInClass(navParamType)
            if (enumField != null) {
                handlers.add(
                    HandlerInfo(method, "p1", navParamType, enumField.first, enumField.second)
                )
            }
        }

        // ── Step 5: Fallback for older versions without "AUTONAV" string ──
        // On very old YouTube versions, the nav type enum might not contain
        // "AUTONAV" as a string literal. Fall back to enum field detection.
        if (handlers.isEmpty() && navTypeEnumTypes.isEmpty()) {
            for (match in v1Matches) {
                val method = match.method
                val navParamType = method.parameterTypes[0].toString()

                if (handlers.any { it.method == method }) continue

                val enumField = findEnumFieldInClass(navParamType)
                if (enumField != null) {
                    handlers.add(
                        HandlerInfo(method, "p1", navParamType, enumField.first, enumField.second)
                    )
                }
            }
        }

        if (handlers.isEmpty()) {
            throw PatchException(
                "Could not find any navigation handler to hook. " +
                "Nav type enums found: ${navTypeEnumTypes.size}, " +
                "Nav event classes found: ${navEventClassTypes.size}, " +
                "V1 broad matches: ${v1Matches.size}. " +
                "This YouTube version may not be supported."
            )
        }

        // ── Hook each matched handler ──
        for ((method, navParamReg, navParamType, enumFieldName, enumFieldType) in handlers) {
            // Inject early-return check at the very start of the method:
            // If the navigation type should be blocked, return before the
            // sequencer can process the navigation.
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
        }

        // ── New bold overlay button system ──
        addPlayerBottomButton(EXTENSION_BUTTON_CLASS_DESCRIPTOR)

        // ── Legacy button system ──
        initializeLegacyBottomControl(EXTENSION_BUTTON_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(EXTENSION_BUTTON_CLASS_DESCRIPTOR)
    }
}
