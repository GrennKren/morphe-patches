/*
 * Stub for FX Explorer's resolver helper (Lqe/d;).
 * Used only at compile time. At runtime, the real class from the APK is used.
 *
 * IMPORTANT: Field types MUST match the APK's smali exactly:
 * - f is Ljava/io/File; (NOT just File)
 * - h is Landroid/net/Uri;
 * - i is Ljava/lang/String;
 */
package qe;

import android.net.Uri;

import java.io.File;

@SuppressWarnings("unused")
public class d {
    public File f;          // The local file (if any)
    public Uri h;           // The URI (for cloud files)
    public String i;        // MIME type
}
