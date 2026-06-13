/*
 * Stub class for F-Stop's p1 thumbnail data class.
 * Used only at compile time so extension code can reference APK classes.
 *
 * Real DEX field names (verified via androguard):
 * - p1.h = String file path
 * - p1.m = boolean selected state (used by FilmStrip.b() for checkmark drawing)
 * - p1.k = c3.t$a media type enum
 * - p1.l = Integer (1 = video, etc.)
 * - p1.p = Rect bounds
 */
package com.fstop.photo;

@SuppressWarnings("unused")
public class p1 {

    public String h;      // file path (real DEX name)
    public boolean m;     // selected state for FilmStrip drawing (real DEX name)
    public Object k;      // c3.t$a media type enum
    public java.lang.Integer l;  // content type (1 = video, etc.)
    public android.graphics.Rect p;  // bounds
}
