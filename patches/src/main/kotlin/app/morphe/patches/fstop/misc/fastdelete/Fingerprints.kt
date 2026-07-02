/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.fastdelete

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for `e3.b.x(ArrayList)String` — the stock file-delete pipeline.
 *
 * From apktool smali analysis of F-Stop 5.5.484 (e3/b.smali):
 * - Method: `Le3/b;->x(Ljava/util/ArrayList;)Ljava/lang/String;`
 * - PUBLIC, takes ArrayList, returns String (error message or null)
 * - `.locals 8` (v0-v7 are free at method entry)
 * - Calls `Le3/b;->w(Lc3/t;)Z` and `Lcom/fstop/photo/p;->d(...)`
 */
internal object DeleteFilesFingerprint : Fingerprint(
    definingClass = "Le3/b;",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/util/ArrayList;"),
    filters = listOf(
        // Order matters: morphe matches filters sequentially in the instruction stream.
        // In x(), p.d() is called BEFORE w() (p.d at smali line ~130, w at ~153).
        methodCall(
            definingClass = "Lcom/fstop/photo/p;",
            name = "d",
        ),
        methodCall(
            definingClass = "Le3/b;",
            name = "w",
        ),
    ),
)

/**
 * Fingerprint for `e3.b.J2(ArrayList)String` — the stock move-to-recycle-bin pipeline.
 *
 * From apktool smali analysis of F-Stop 5.5.484 (e3/b.smali):
 * - Method: `Le3/b;->J2(Ljava/util/ArrayList;)Ljava/lang/String;`
 * - PUBLIC, takes ArrayList, returns String
 * - `.locals 13` (v0-v12 are free at method entry)
 * - Calls `Lo8/a;->m(...)` and `Le3/b;->G2(I)Z`
 */
internal object MoveToRecycleBinFingerprint : Fingerprint(
    definingClass = "Le3/b;",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/util/ArrayList;"),
    filters = listOf(
        methodCall(
            definingClass = "Lo8/a;",
            name = "m",
        ),
        methodCall(
            definingClass = "Le3/b;",
            name = "G2",
        ),
    ),
)
