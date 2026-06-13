/*
 * Stub class for F-Stop's media item data class (c3.t).
 * Used only at compile time so extension code can reference APK classes.
 *
 * IMPORTANT: Method and field names MUST match the actual DEX bytecode,
 * NOT the jadx decompiler output. Verified via androguard against the APK.
 *
 * Selection state has TWO fields that must be kept in sync:
 * - s (boolean, private) — the authoritative selected flag
 * - L (int, private) — used by U() to check if L > 0
 *
 * Methods:
 * - z()Z: returns field s (boolean) — USE THIS for reliable selection check
 * - Q()Z: also returns field s (same as z())
 * - U()Z: returns (L > 0) — checks the int field, NOT s!
 * - X(Z)V: sets s = val, also calls b0.x() and sets B0 when selecting
 * - c0(I)V: sets L = val — MUST be called alongside X() to keep in sync
 * - j field: String file path (public)
 */
package c3;

@SuppressWarnings("unused")
public class t {

    public String j;  // file path (real DEX name, public)

    /**
     * Check if this item is selected.
     * Returns field 's' (boolean selected flag).
     * This is more reliable than U() which checks field 'L' (int).
     */
    public boolean z() {
        return false; // stub - returns field s
    }

    /**
     * Check if this item is selected via the L field.
     * Returns (L > 0). Note: X() does NOT update L, so this may
     * return stale values after toggling. Use z() instead.
     */
    public boolean U() {
        return false; // stub - checks L > 0
    }

    /**
     * Set the selected state of this item.
     * Sets field 's' = val. When selecting (val=true), also calls
     * b0.x() and sets B0. Does NOT update field 'L' — call c0() too.
     */
    public void X(boolean selected) {
        // stub - sets s, optionally sets B0
    }

    /**
     * Set the L field (selection counter/index).
     * Must be called alongside X() to keep both selection fields in sync.
     * c0(1) when selecting, c0(0) when deselecting.
     */
    public void c0(int val) {
        // stub - sets L = val
    }
}
