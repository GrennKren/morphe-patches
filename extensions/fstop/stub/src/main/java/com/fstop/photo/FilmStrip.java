/*
 * Stub class for F-Stop's FilmStrip custom view.
 * Used only at compile time so extension code can reference APK classes.
 *
 * Real DEX field names (verified via androguard):
 * - FilmStrip.l = ArrayList of p1 thumbnails (public)
 * - FilmStrip.D = boolean selection mode enabled (public)
 * - FilmStrip.i = Paint (private)
 * - FilmStrip.B = Rect (public)
 * - FilmStrip.C = Drawable (public) - checkmark drawable
 * - FilmStrip.w = Rect (public)
 * - FilmStrip.u = Rect (public)
 */
package com.fstop.photo;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

@SuppressWarnings("unused")
public class FilmStrip extends View {

    public java.util.ArrayList l;  // ArrayList of p1 thumbnails
    public boolean D;              // selection mode enabled flag

    public FilmStrip(Context context) {
        super(context);
    }

    public FilmStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FilmStrip(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }
}
