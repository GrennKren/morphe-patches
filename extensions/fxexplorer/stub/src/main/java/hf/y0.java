/*
 * Stub class for FX Explorer's "Open With" dialog (Lhf/y0;).
 * Used only at compile time so extension code can reference APK classes.
 * At runtime, the real class from the APK is used instead.
 *
 * IMPORTANT: Field types MUST match the APK's smali exactly:
 * - X is Lkh/e; (NOT Object)
 * - f is Lqe/d; (NOT Object)
 * - e2 is Ljava/lang/String;
 */
package hf;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.net.Uri;

@SuppressWarnings("unused")
public class y0 extends Dialog {

    public kh.e X;        // Lkh/e; — MUST match APK type exactly
    public qe.d f;        // Lqe/d; — MUST match APK type exactly
    public String e2;     // Ljava/lang/String; — MIME type override

    public y0() {
        super((Context) null);
    }

    public void i() {
        // Stub - re-resolve apps with current MIME type override
    }

    public void f(String header) {
        // Stub - add section header
    }

    public void e(CharSequence label, android.graphics.drawable.Drawable icon, Object qeB, android.view.View.OnClickListener listener) {
        // Stub - add grid item
    }

    public void h(ResolveInfo ri, Uri uri, int flags) {
        // Stub - launch app with ACTION_VIEW
    }

    public void dismiss() {
        // Stub - dismiss dialog
    }
}
