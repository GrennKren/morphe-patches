package app.morphe.patches.fstop.misc.foldercoverpersist

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

internal object CoverResolverBFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/c;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lc3/e;"),
    filters = listOf(
        methodCall(
            definingClass = "Le3/b;",
            name = "O0",
        ),
    ),
)
