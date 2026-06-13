/*
 * Stub class for F-Stop's FilmStrip view.
 * Used only at compile time so extension code can reference APK classes.
 *
 * Field names verified via androguard against the actual APK DEX:
 * - l: ArrayList of p1 thumbnails (public)
 * - D: boolean selection mode enabled (public)
 * - e(String)p1: finds thumbnail by file path (compares p1.h)
 */
package com.fstop.photo;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

@SuppressWarnings("unused")
public class FilmStrip extends View {

    public java.util.ArrayList l;  // ArrayList<p1> thumbnails (real DEX name)
    public boolean D;  // selection mode enabled (real DEX name)

    // Stub constructors required by Android View subclass
    public FilmStrip(Context context) { super(context); }
    public FilmStrip(Context context, AttributeSet attrs) { super(context, attrs); }
    public FilmStrip(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    /**
     * Find a p1 thumbnail by file path.
     * Iterates through l (ArrayList of p1), compares p1.h with the path.
     */
    public p1 e(String path) {
        return null; // stub
    }
}
