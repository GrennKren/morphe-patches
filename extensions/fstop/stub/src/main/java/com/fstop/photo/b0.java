/*
 * Stub class for F-Stop's global state class (b0).
 * Used only at compile time so extension code can reference APK classes.
 *
 * ACCESS MODIFIER ANALYSIS (from actual DEX smali):
 * - H (c) — PUBLIC STATIC → direct access OK
 * - p (e3.b) — PUBLIC STATIC → direct access OK
 * - r (Context) — PUBLIC STATIC → direct access OK
 * - X2 (boolean) — PUBLIC STATIC → direct access OK
 * - H4, V4 — PUBLIC STATIC → direct access OK
 */
package com.fstop.photo;

import java.util.HashSet;

import e3.b;

@SuppressWarnings("unused")
public class b0 {

    public static int H4;
    public static int V4;

    /** The folder cover resolver singleton (PUBLIC static). */
    public static c H;

    /** The database helper instance (PUBLIC static). */
    public static b p;

    /** The application context (PUBLIC static). */
    public static android.content.Context r;

    /** prescanThumbnails preference (PUBLIC static). */
    public static boolean X2;

    /** Recycle bin dirty flag (PUBLIC static). Set true when items are moved to recycle bin. */
    public static boolean R4;

    /** Deleted paths set (PUBLIC static). Used by stock code to track deleted files. */
    public static HashSet<String> Z;

    public static int x() { return 0; }
}
