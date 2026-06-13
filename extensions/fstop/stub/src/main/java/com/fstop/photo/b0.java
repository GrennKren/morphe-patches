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
 */
package com.fstop.photo;

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
     * Generate a new selection session ID.
     * Increments V4 and returns the new value.
     * Called by c3/t.X(true) when selecting an item.
     */
    public static int x() {
        return 0; // stub
    }
}
