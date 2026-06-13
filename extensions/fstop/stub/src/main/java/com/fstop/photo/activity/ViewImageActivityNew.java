/*
 * Stub class for F-Stop's ViewImageActivityNew.
 * Used only at compile time so extension code can reference APK classes.
 *
 * IMPORTANT: Field names MUST match the actual DEX bytecode, NOT the jadx
 * decompiler output. Verified via androguard against actual DEX files.
 */
package com.fstop.photo.activity;

import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;

import com.fstop.photo.FilmStrip;

@SuppressWarnings("unused")
public class ViewImageActivityNew extends Activity {

    public l3.k u0;           // data model holder (real DEX name)
    public boolean H0;        // toolbar visibility: true=visible, false=hidden (real DEX name)
    public FilmStrip Q0;      // FilmStrip instance (real DEX name)
    public Object L0;         // MyAppToolbar (real DEX name)

    public c3.t o() {
        return null; // stub
    }

    public boolean p3(int itemId, MenuItem menuItem) {
        return false; // stub
    }

    public void I2() {
        // stub - hide toolbar (fullscreen mode), sets H0 = false
    }

    public void a4() {
        // stub - show toolbar (normal mode), sets H0 = true
    }
}
