/*
 * Stub class for F-Stop's media item data class (c3.t).
 * Used only at compile time so extension code can reference APK classes.
 *
 * IMPORTANT: Method names MUST match the actual DEX bytecode, NOT the jadx
 * decompiler output. Verified via androguard against actual DEX files.
 *
 * Key fields/methods (real DEX names):
 * - c3.t.L (private int) = selection count, U() returns (L > 0)
 * - c3.t.s (private boolean) = selected flag, set by X(boolean)
 * - c3.t.B0 (public int) = set by X(true) to b0.x() value
 * - c3.t.j (public String) = file path
 * - c3.t.U()Z = isSelected (returns L > 0)
 * - c3.t.X(Z)V = setSelected (sets s = val)
 */
package c3;

@SuppressWarnings("unused")
public class t {

    public String j;  // file path (real DEX name)

    /**
     * Check if this item is selected.
     * Real DEX: checks L > 0 (L is private int selection count).
     */
    public boolean U() {
        return false; // stub - isSelected
    }

    /**
     * Set the selected state of this item.
     * Real DEX: sets s = selected, if true also sets B0 = b0.x().
     */
    public void X(boolean selected) {
        // stub - setSelected
    }
}
