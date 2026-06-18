/*
 * Stub class for F-Stop's global state class (b0).
 * Used only at compile time so extension code can reference APK classes.
 *
 * Field names verified via androguard against the actual APK DEX:
 * - H4: static int — selection mode flag. -1 = no selection, >0 = selection active.
 *   Checked by c3/t.V() (returns H4 > 0) and c3/t.T() (returns L > 0 || H4 > 0).
 *   Used by x2() for menu item visibility (crop visible when H4 > 0, etc.).
 * - V4: static int — selection session ID counter. Incremented by x().
 *   Used by c3/t.X(true) which assigns the next session ID to B0.
 * - p: static e3.b — the singleton DB helper instance. Initialized during
 *   app startup. Used to access the SQLite Thumbnail table (read/write
 *   MicroThumbnail blobs). Referenced by y1, p, e0, etc. via
 *   `sget-object v0, Lcom/fstop/photo/b0;->p:Le3/b;`.
 * - X2: static boolean — prescanThumbnails preference. When true, the
 *   prescan pipeline saves thumbnails to SQLite. Loaded from
 *   SharedPreferences key "prescanThumbnails" in p.R2().
 */
package com.fstop.photo;

import e3.b;

@SuppressWarnings("unused")
public class b0 {

    /**
     * Selection mode flag.
     * -1 = no selection active, >0 = selection mode active.
     * When H4 > 0: c3/t.V() returns true, crop menu item shows,
     * some other menu items hide. Set by c3() (enter), q4() (exit).
     */
    public static int H4;  // selection mode position indicator (real DEX name)

    /**
     * Selection session ID counter. Incremented by x().
     * Each new selection gets a unique session ID stored in c3/t.B0.
     */
    public static int V4;  // selection ID counter (real DEX name)

    /**
     * The database helper instance (e3.b).
     * Initialized during app startup. Used to access the SQLite
     * Thumbnail table — read via a0(), write via Y1().
     */
    public static b p;

    /**
     * prescanThumbnails preference (forced true by the Persist folder
     * thumbnails patch). When true, the prescan pipeline saves
     * thumbnails to SQLite.
     */
    public static boolean X2;

    /**
     * Generate a new selection session ID.
     * Increments V4 and returns the new value.
     * Called by c3/t.X(true) when selecting an item.
     */
    public static int x() {
        return 0; // stub
    }
}
