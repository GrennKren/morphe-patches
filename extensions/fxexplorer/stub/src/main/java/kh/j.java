/*
 * Stub for FX Explorer's base item interface (Lkh/j;).
 * Used only at compile time. At runtime, the real class from the APK is used.
 *
 * IMPORTANT: Method return types and declaring interface MUST match the APK's smali exactly:
 * - getName() → declared in kh/j, returns Ljava/lang/String; ✓
 * - getPath() → declared in kh/j, returns Lhh/f; (NOT Object!) ✓
 *
 * CRITICAL: w() is NOT declared in kh/j! It is declared in kh/e only.
 * If we put w() in kh/j, R8 compiles invoke-interface Lkh/j;->w() which
 * does NOT exist in the real APK → NoSuchMethodError → silent crash in tryOpenDirectly!
 * w() MUST be in kh/e only — see kh/e.java stub.
 */
package kh;

import hh.f;

@SuppressWarnings("unused")
public interface j {
    String getName();
    f getPath();    // MUST be hh.f — NOT Object! Matches real APK's getPath()Lhh/f;
    // DO NOT add w() here! w() is declared in kh/e, not kh/j.
    // Adding it here causes invoke-interface Lkh/j;->w() → NoSuchMethodError!
}
