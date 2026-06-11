/*
 * Stub class for FX Explorer's "Open With" dialog (Lhf/y0;).
 * Used only at compile time so extension code can reference APK classes.
 * At runtime, the real class from the APK is used instead.
 *
 * IMPORTANT: Field types MUST match the APK's smali exactly:
 * - X is Lkh/e; (NOT Object)
 * - f is Lqe/d; (NOT Object)
 * - e2 is Ljava/lang/String;
 * - i is Landroid/widget/LinearLayout; (content layout)
 * - d2 is Lgh/g; (current grid layout, reset to null by section headers)
 * - ui is Lef/g; (UI theme helper)
 */
package hf;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;

@SuppressWarnings("unused")
public class y0 extends Dialog {

    public kh.e X;                          // Lkh/e; — MUST match APK type exactly
    public qe.d f;                          // Lqe/d; — MUST match APK type exactly
    public String e2;                       // Ljava/lang/String; — MIME type override
    public LinearLayout i;                  // Landroid/widget/LinearLayout; — content layout
    public Object d2;                       // Lgh/g; — current grid (use Object to avoid gh/g stub)
    public Object ui;                       // Lef/g; — UI theme helper (use Object to avoid ef/g stub)

    public y0() {
        super((Context) null);
    }

    /**
     * Section header — creates gh/k uppercase header from string resource ID.
     * Produces gray uppercase text like "OPEN WITH APPLICATION".
     */
    public void b(int stringResId) {
        // Stub - add section header from resource ID
    }

    /**
     * Sub-section info text — creates styled TextView (style 16, brown bold).
     * This is NOT for section headers — use b(int) instead!
     */
    public void f(String header) {
        // Stub - add sub-section info text
    }

    /**
     * Grid item — creates icon+text item in a gh/g grid layout.
     */
    public void e(CharSequence label, Drawable icon, Object qeB, View.OnClickListener listener) {
        // Stub - add grid item
    }

    public void h(ResolveInfo ri, Uri uri, int flags) {
        // Stub - launch app with ACTION_VIEW
    }

    public void i() {
        // Stub - re-resolve apps with current MIME type override
    }

    public void dismiss() {
        // Stub - dismiss dialog
    }
}
