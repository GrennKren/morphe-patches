package app.morphe.patches.fstop.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_FSTOP = Compatibility(
        name = "F-Stop",
        packageName = "com.fstop.photo",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0x2196F3,
        signatures = setOf(
            // SHA-256 of signing certificate for F-Stop 5.5.484
            "fe0adb3436c0f08f91240b24da54def1bed0123ed6a9f9380f57a3ef02846b51",
        ),
        targets = listOf(
            AppTarget(
                version = "5.5.484",
                minSdk = 24,
            ),
        )
    )
}
