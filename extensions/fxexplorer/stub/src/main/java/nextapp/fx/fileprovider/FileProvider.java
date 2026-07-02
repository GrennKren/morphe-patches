/*
 * Stub for FX Explorer's FileProvider.
 * Used only at compile time. At runtime, the real class from the APK is used.
 */
package nextapp.fx.fileprovider;

import android.content.Context;
import android.net.Uri;

import java.io.File;

@SuppressWarnings("unused")
public class FileProvider {
    public static Uri a(Context ctx, File file) { return null; }
    public static Uri b(Context ctx, File file, int mode) { return null; }
}
