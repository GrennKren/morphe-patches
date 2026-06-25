/*
 * Stub class for F-Stop's image wrapper class (w1).
 * Used only at compile time so extension code can reference APK classes.
 *
 * ACCESS MODIFIER ANALYSIS (from actual DEX smali):
 * - Fields a, b, c are PRIVATE → must use public getters/setters
 * - Fields d, e, h are PUBLIC → direct access OK
 * - Constructor <init>()V is PUBLIC → direct access OK
 * - Method i(int, String, boolean)V is PUBLIC → direct access OK
 *
 * Field names verified from actual DEX smali (NOT jadx names):
 * - a (int, private) — imageId. Getter: c()I
 * - b (String, private) — fullPath. Getter: d()Ljava/lang/String;
 * - c (boolean, private) — isVideo. Getter: b()Z
 * - h (Boolean, public) — resolved flag. TRUE after cover is resolved.
 */
package com.fstop.photo;

@SuppressWarnings("unused")
public class w1 {

    public boolean d;
    public String e;
    public Boolean h;

    public w1() {}

    public w1(int imageId, String fullPath) {}

    /** Set imageId, fullPath, isVideo (PUBLIC method). */
    public void i(int imageId, String fullPath, boolean isVideo) {}

    /** Get imageId (PUBLIC method). */
    public int c() { return 0; }

    /** Get fullPath (PUBLIC method). */
    public String d() { return null; }

    /** Get isVideo (PUBLIC method). */
    public boolean b() { return false; }
}
