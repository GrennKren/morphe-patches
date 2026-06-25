/*
 * Stub class for F-Stop's folder cover resolver class (c).
 * Used only at compile time so extension code can reference APK classes.
 *
 * ACCESS MODIFIER ANALYSIS (from actual DEX smali):
 * - Field a (HashMap) — PACKAGE-PRIVATE → MUST USE REFLECTION from extension code!
 *   .field a:Ljava/util/HashMap;  (no public/private modifier)
 * - Constructor <init>()V — PUBLIC
 * - Method b(c3/e)V — PUBLIC
 * - Method a()V (clear cache) — PUBLIC
 * - Method c(String)V (remove entry) — PUBLIC
 *
 * The singleton instance is b0.H (public static).
 *
 * CRITICAL: Extension classes in app.morphe.extension.fstop CANNOT directly
 * access c.a because it's package-private (com.fstop.photo). Must use
 * reflection: Field f = c.class.getDeclaredField("a"); f.setAccessible(true);
 */
package com.fstop.photo;

import java.util.HashMap;

@SuppressWarnings("unused")
public class c {

    /**
     * In-memory cover cache. PACKAGE-PRIVATE — use reflection from extension code!
     * Maps folder path (String) → c$a (cover w1 + sub-thumbnails ArrayList).
     */
    public HashMap a;

    public c() {
        a = new HashMap();
    }

    public void b(c3.e folder) {}
    public void a() {}
    public void c(String folderPath) {}
}
