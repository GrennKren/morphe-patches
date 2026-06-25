/*
 * Stub class for F-Stop's folder cover data inner class (c$a).
 * Used only at compile time so extension code can reference APK classes.
 *
 * ACCESS MODIFIER ANALYSIS (from actual DEX smali):
 * - Field a (w1) — PACKAGE-PRIVATE → MUST USE REFLECTION from extension classes
 * - Field b (ArrayList) — PACKAGE-PRIVATE → MUST USE REFLECTION from extension classes
 * - Field c (c) — PACKAGE-PRIVATE, final synthetic → set by constructor
 * - Constructor <init>(c, ArrayList)V — PUBLIC → direct access OK
 *
 * Field names verified from actual DEX smali (NOT jadx names).
 */
package com.fstop.photo;

import java.util.ArrayList;

@SuppressWarnings("unused")
public class c$a {

    /** Cover image wrapper. PACKAGE-PRIVATE — use reflection from extension code! */
    public w1 a;

    /** Sub-thumbnails list. PACKAGE-PRIVATE — use reflection from extension code! */
    public ArrayList b;

    /** Constructor — PUBLIC, can be called from any package. */
    public c$a(c outer, ArrayList subThumbs) {
        // Stub — real constructor creates empty w1 for a, copies ArrayList for b
    }
}
