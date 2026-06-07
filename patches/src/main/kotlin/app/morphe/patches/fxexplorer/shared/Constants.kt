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

    /**
     * Hex-encoded DER bytes of the original FX Explorer signing certificate.
     *
     * Used by the signature spoofing patch so the patched app can still verify
     * the FX Plus License Key app (nextapp.fx.rk), which checks that both apps
     * share the same signing certificate. Since the patched APK is signed with
     * a different certificate (Morphe), we spoof the original signature during
     * the license verification comparison.
     *
     * Certificate details:
     * - Owner: CN=Tod Liebeck, OU=android.nextapp.com, O=NextApp, Inc.
     * - SHA-256: 02f79730223f454ccd108414ec063618b762a4768aedfb64462d8416f417c677
     */
    const val ORIGINAL_SIGNATURE_HEX =
        "30820275308201dea00302010202044b3336a9300d06092a864886f70d01010505" +
        "00307e310b3009060355040613025553310b3009060355040813024341311630" +
        "140603550407130d4e6577706f727420426561636831163014060355040a130d" +
        "4e6578744170702c20496e632e311c301a060355040b1313616e64726f69642" +
        "e6e6578746170702e636f6d311430120603550403130b546f64204c69656265" +
        "636b3020170d3039313232343039333834395a180f323134363131313630393" +
        "33834395a307e310b3009060355040613025553310b30090603550408130243" +
        "41311630140603550407130d4e6577706f72742042656163683116301406035" +
        "5040a130d4e6578744170702c20496e632e311c301a060355040b1313616e64" +
        "726f69642e6e6578746170702e636f6d311430120603550403130b546f64204" +
        "c69656265636b30819f300d06092a864886f70d010101050003818d00308189" +
        "02818100a38db1a17c555bb3ab134540b40781244d9a80d423b3fe808feeb5" +
        "bc5e6a4bce1870af6bb71f4f843aebcba7b403f3e1a9b21eee23e99a52b590" +
        "34e58f5396795d1c4ca94f1ece7b6340ae32dc6527d4437ce0e4012bb9acc9" +
        "a7988dd7f05a692a01ff0df379dfcfbde7601ea2a3ab35369b16ad9aaed6f7" +
        "64ab4392d1e93d390203010001300d06092a864886f70d01010505000381810" +
        "03b9c2b63a8dca94eedcb69411bbcfbc2993de32bf8d6847df313574bf2a73" +
        "6695b4f67c4f55fbc8a1139857e60a12ddcd51c7429330150ffd62a2d094be" +
        "7492d994adda3635d8bb8d5af7197250ddbd79065f0ef551d6185af0716076" +
        "0a491e97b5dcc4351987a89427e4164c90e999868f627ebaff519c407902ab" +
        "ba95fc80f"
}
