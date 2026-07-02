/*
 * Stub class for F-Stop's media item data class (c3.t).
 * Used only at compile time so extension code can reference APK classes.
 *
 * IMPORTANT: Method and field names MUST match the actual DEX bytecode,
 * NOT the jadx decompiler output. Verified via androguard against the APK.
 *
 * SELECTION STATE FIELDS (verified from DEX):
 * - s (boolean, private) — THE authoritative selected flag.
 *   Set by X(Z)V, returned by z()Z and Q()Z.
 *   When X(true) is called, also assigns a session ID to B0 via b0.x().
 *
 * - L (int, private) — Favorite/rating counter.
 *   Checked by U()Z (returns L > 0). Updated by c0(I)V.
 *   *** THIS IS NOT SELECTION! It's for FAVORITES/PROTECTED state! ***
 *   DO NOT call c0() for selection toggling!
 *
 * - B0 (int) — Selection session ID.
 *   Set by X(true) to the result of b0.x() (an incrementing counter).
 *   Used to track which selection session this item belongs to.
 *
 * - S (int, public) — DataSourceType.
 *   0 = local file, non-zero = cloud source ID.
 *   Controls menu visibility for cloud vs local operations.
 *   *** THIS IS NOT SELECTION! ***
 *
 * - g1 (boolean) — Image loaded/rendering flag.
 *   Set false during q4() and c3() resets.
 *   DO NOT modify this — it controls image rendering!
 *
 * GLOBAL SELECTION STATE:
 * - b0.H4 (static int) — Selection mode flag.
 *   -1 = no selection, >0 = selection active.
 *   Checked by V()Z, T()Z, and x2() for menu visibility.
 */
package c3;

@SuppressWarnings("unused")
public class t {

    public String j;   // file path (real DEX name, public)
    public int i;      // database ID (real DEX name, public)
    public int S;      // DataSourceType: 0=local, non-zero=cloud (real DEX name, public)
    public boolean g1; // image loaded flag (real DEX name, public) — DO NOT MODIFY
    public int B0;     // selection session ID (real DEX name, public)

    // ── Fast delete patch stubs ─────────────────────────────────────
    /** Storage type: 0=local-with-MediaStore, 1=SMB, 2=other-remote, 3=cloud (real DEX name). */
    public int Q;
    /** Used by d3.i.b(R, S) for Q==2 cloud deletes (real DEX name). */
    public String R;

    /** Stock: returns the file wrapper (o8.d) for this item's path. */
    public o8.d N(boolean z) { return null; }

    /**
     * Check if this item is selected.
     * Returns field 's' (boolean selected flag).
     * This is THE authoritative selection check.
     */
    public boolean z() {
        return false; // stub - returns field s
    }

    /**
     * Alias for z() — also returns field 's'.
     */
    public boolean Q() {
        return false; // stub - returns field s
    }

    /**
     * Check if this item is favorited/protected.
     * Returns (L > 0). L is the FAVORITE counter, NOT selection!
     * DO NOT use this for selection checks — use z() instead.
     */
    public boolean U() {
        return false; // stub - checks L > 0 (favorites)
    }

    /**
     * Check if in selection mode (per-item or global).
     * Returns (L > 0 || b0.H4 > 0).
     */
    public boolean T() {
        return false; // stub
    }

    /**
     * Check if global selection mode is active.
     * Returns (b0.H4 > 0).
     */
    public static boolean V() {
        return false; // stub - returns H4 > 0
    }

    /**
     * Set the selected state of this item.
     * Sets field 's' = val. When selecting (val=true), also calls
     * b0.x() and assigns the result to B0 (session ID).
     * Does NOT update field 'L' or field 'S' or b0.H4.
     */
    public void X(boolean selected) {
        // stub - sets s, optionally sets B0 via b0.x()
    }

    /**
     * Set the L field (favorite/rating counter).
     * *** DO NOT USE FOR SELECTION! L is for FAVORITES, not selection! ***
     * Calling c0(1) makes U() return true, which changes menu items
     * related to favorites/protected state, NOT selection.
     * This was the root cause of the blank image bug!
     */
    public void c0(int val) {
        // stub - sets L = val (FAVORITES, NOT selection!)
    }
}
