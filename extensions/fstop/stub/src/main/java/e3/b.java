/*
 * Stub for F-Stop's database helper class (e3/b).
 * Used only at compile time so extension code can reference APK classes.
 *
 * ACCESS MODIFIER ANALYSIS (from actual DEX smali):
 * - a (SQLiteDatabase) — PUBLIC → direct access OK
 * - Y1() — PUBLIC → direct access OK
 * - b2() — PUBLIC → direct access OK
 */
package e3;

import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;

@SuppressWarnings("unused")
public class b {

    /** The underlying SQLite database. PUBLIC field. */
    public SQLiteDatabase a;

    public void Y1(int imageId, String fullPath, Bitmap bitmap) {}

    public boolean b2() { return a != null && a.isOpen(); }
}
