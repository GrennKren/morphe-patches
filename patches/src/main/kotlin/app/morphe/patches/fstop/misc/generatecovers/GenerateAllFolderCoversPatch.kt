/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.generatecovers

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import org.w3c.dom.Element

/**
 * Patch: Generate all folder covers.
 *
 * Adds a "Generate all folder covers" button to Settings → Cache.
 * When tapped, starts a foreground service that iterates all folders,
 * resolves their cover image, and persists the result so covers load
 * instantly on restart (works together with the "Persist folder cover
 * metadata" patch).
 *
 * The notification shows progress (X/Y folders) with Pause/Resume/Stop
 * action buttons.
 *
 * <h2>Part 1: Resource patch — add Preference to XML</h2>
 * Add a `<Preference>` element to `res/xml/preferences_fragment_cache.xml`
 * with key="generateAllFolderCovers". This is cleaner than creating the
 * Preference programmatically and follows the vanilla XML-driven pattern.
 *
 * <h2>Part 2: Bytecode patch — register click listener</h2>
 * Inject `findPreference("generateAllFolderCovers")` +
 * `setOnPreferenceClickListener(new FolderCoverGeneratorClickListener())`
 * before `return-void` in `onCreatePreferences`. This follows the EXACT
 * same pattern as vanilla F-Stop's existing click listeners for
 * "deletePreviewImages" and "refreshCache".
 *
 * <h2>Part 3: Manifest patch — declare Service</h2>
 * Add a `<service>` element for FolderCoverGeneratorService to
 * AndroidManifest.xml.
 */
@Suppress("unused")
val generateAllFolderCoversPatch = bytecodePatch(
    name = "Generate all folder covers",
    description = "Adds a button in Settings → Cache to generate cover " +
        "thumbnails for all folders in the background. A progress " +
        "notification with Pause, Resume, and Stop buttons is shown while " +
        "generating. Works together with the 'Persist folder cover " +
        "metadata' patch — after generating, covers load instantly on " +
        "restart.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    extendWith("extensions/fstop.mpe")

    // ═══════════════════════════════════════════════════════════════
    // Part 1: Resource patch — add Preference to preferences_fragment_cache.xml
    // ═══════════════════════════════════════════════════════════════
    dependsOn(
        resourcePatch {
            execute {
                document("res/xml/preferences_fragment_cache.xml").use { document ->
                    val screen = document.getElementsByTagName("androidx.preference.PreferenceScreen").item(0) as Element

                    // Check if preference already exists
                    val preferences = screen.getElementsByTagName("Preference")
                    for (i in 0 until preferences.length) {
                        val pref = preferences.item(i) as Element
                        if (pref.getAttribute("app:key") == "generateAllFolderCovers") {
                            return@execute
                        }
                    }

                    // Create new <Preference> element
                    val pref = document.createElement("Preference")
                    pref.setAttribute("app:iconSpaceReserved", "false")
                    pref.setAttribute("app:key", "generateAllFolderCovers")
                    pref.setAttribute("app:title", "Generate all folder covers")
                    pref.setAttribute("app:summary", "Pre-generate cover thumbnails for all folders in the background")
                    screen.appendChild(pref)
                }
            }
        }
    )

    // ═══════════════════════════════════════════════════════════════
    // Part 3: Manifest patch — declare FolderCoverGeneratorService
    // ═══════════════════════════════════════════════════════════════
    dependsOn(
        resourcePatch {
            execute {
                document("AndroidManifest.xml").use { document ->
                    val manifest = document.getElementsByTagName("manifest").item(0) as Element
                    val application = manifest.getElementsByTagName("application").item(0) as Element

                    val services = application.getElementsByTagName("service")
                    for (i in 0 until services.length) {
                        val service = services.item(i) as Element
                        if (service.getAttribute("android:name") ==
                                "app.morphe.extension.fstop.FolderCoverGeneratorService") {
                            return@execute
                        }
                    }

                    val service = document.createElement("service")
                    service.setAttribute("android:name",
                        "app.morphe.extension.fstop.FolderCoverGeneratorService")
                    service.setAttribute("android:enabled", "true")
                    service.setAttribute("android:exported", "false")
                    service.setAttribute("android:foregroundServiceType", "dataSync")
                    application.appendChild(service)
                }
            }
        }
    )

    // ═══════════════════════════════════════════════════════════════
    // Part 2: Bytecode patch — register click listener
    // ═══════════════════════════════════════════════════════════════
    execute {
        val LISTENER_CLASS = "Lapp/morphe/extension/fstop/FolderCoverGeneratorClickListener;"

        SettingsFragmentCacheOnCreatePreferencesFingerprint.method.apply {
            val impl = implementation!!
            val instructions = impl.instructions.toList()

            // Find the last return-void
            val returnIndex = instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            }
            if (returnIndex == -1) {
                throw PatchException("Could not find return-void in onCreatePreferences")
            }

            // Inject before return-void — same pattern as vanilla:
            //   const-string p1, "generateAllFolderCovers"
            //   invoke-virtual {p0, p1}, Landroidx/preference/h;->findPreference(Ljava/lang/CharSequence;)Landroidx/preference/Preference;
            //   move-result-object p1
            //   new-instance p2, Lapp/morphe/extension/fstop/FolderCoverGeneratorClickListener;
            //   invoke-direct {p2}, Lapp/morphe/extension/fstop/FolderCoverGeneratorClickListener;-><init>()V
            //   invoke-virtual {p1, p2}, Landroidx/preference/Preference;->setOnPreferenceClickListener(Landroidx/preference/Preference$d;)V
            //
            // p0 = this (SettingsFragmentCache), p1/p2 = temporaries (.locals 0)
            addInstructions(returnIndex, """
                const-string p1, "generateAllFolderCovers"
                invoke-virtual {p0, p1}, Landroidx/preference/h;->findPreference(Ljava/lang/CharSequence;)Landroidx/preference/Preference;
                move-result-object p1
                new-instance p2, $LISTENER_CLASS
                invoke-direct {p2}, $LISTENER_CLASS-><init>()V
                invoke-virtual {p1, p2}, Landroidx/preference/Preference;->setOnPreferenceClickListener(Landroidx/preference/Preference${'$'}d;)V
            """.trimIndent())
        }
    }
}
