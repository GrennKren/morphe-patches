package app.morphe.patches.fstop.misc.truelazyload

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Fingerprint for `Lcom/fstop/photo/x1;->a(Lc3/e;)V`.
 *
 * From APK decompilation:
 * - Class: `x1` extends `HandlerThread` (thread name = "ThumbnailDataSetter")
 * - Method: PUBLIC, takes a single `c3.e` parameter (a folder-item model object),
 *   returns void.
 * - Responsibility: enqueues a folder-item so that its cover image path can be
 *   resolved asynchronously by `x1.b()` (which drains the queue and calls
 *   `b0.H.b(eVar)` → `e3.b.O0(eVar)` to query the SQLite `Image` table for the
 *   first image inside the folder — that image becomes the folder's cover).
 *
 * STOCK BEHAVIOR (FIFO — the bug):
 * The stock implementation appends the new request to the END of the
 * `x1$d.f9485a` ArrayList via `add(bVar)` (smali:
 * `invoke-virtual {p1, v1}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z`).
 * The drain loop `x1.b()` always takes from index 0 (HEAD). Insert-at-tail +
 * take-from-head = FIFO. As a result, when the user opens the app and scrolls
 * down, folder cover path lookups are processed in submission order — top items
 * first, then middle, then bottom. Bottom items cannot have their thumbnails
 * loaded until x1 catches up to them, because the downstream `z1` (bitmap
 * reader) cannot read a bitmap for an item whose cover path is not yet known.
 *
 * FILTERS:
 * The method is uniquely identified by:
 * 1. definingClass = Lcom/fstop/photo/x1;
 * 2. returnType = V
 * 3. accessFlags = PUBLIC
 * 4. parameters = [Lc3/e;]
 * 5. The method body calls `Lcom/fstop/photo/x1$d;->a(...)` (private accessor
 *    for the inner ArrayList queue) — this is unique to the queue-insert method
 *    `a()` and not present in `b()` (the drain method) or `c()` (the
 *    Handler-attach method).
 */
internal object X1EnqueueFolderFingerprint : Fingerprint(
    definingClass = "Lcom/fstop/photo/x1;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lc3/e;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/fstop/photo/x1\$d;",
            name = "a",
        ),
    ),
)
