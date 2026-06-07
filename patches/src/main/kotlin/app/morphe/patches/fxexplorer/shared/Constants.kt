package app.morphe.patches.fxexplorer.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_FX_EXPLORER = Compatibility(
        name = "FX Explorer",
        packageName = "nextapp.fx",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0x4CAF50,
        signatures = setOf(
            // SHA-256 of signing certificate for FX Explorer 9.1.0.8
            "02f79730223f454ccd108414ec063618b762a4768aedfb64462d8416f417c677",
        ),
        targets = listOf(
            AppTarget(
                version = "9.1.0.8",
                minSdk = 26,
            ),
        )
    )
}
