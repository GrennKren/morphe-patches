/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.misc.license

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for the license verification method (lh/n.l(Context)Z).
 *
 * This method verifies the FX Plus License Key app by:
 * 1. Checking SharedPreferences cache for IAB license
 * 2. Comparing the signature of nextapp.fx.rk with the app's own signature
 *
 * Key identifiers:
 * - Public static method returning boolean with Context parameter
 * - References "nextapp.fx.rk" string constant
 * - Calls PackageManager.getPackageInfo twice (once for key app, once for self)
 */
internal object LicenseCheckFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("nextapp.fx.rk"),
)
