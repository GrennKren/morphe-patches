/*
 * Stub class for F-Stop's FilmStrip thumbnail data (p1).
 * Used only at compile time so extension code can reference APK classes.
 *
 * Field names verified via androguard against the actual APK DEX:
 * - h: String file path
 * - m: boolean selected state (used for drawing checkmarks)
 * - b(): returns h (file path)
 */
package com.fstop.photo;

@SuppressWarnings("unused")
public class p1 {

    public String h;  // file path (real DEX name)
    public boolean m;  // selected state for drawing checkmarks (real DEX name)

    /**
     * Get the file path of this thumbnail.
     * Returns field h.
     */
    public String b() {
        return h; // stub
    }
}
