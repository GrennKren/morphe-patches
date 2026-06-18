/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fstop.misc.thumbnailslogger

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fstop.shared.Constants.COMPATIBILITY_FSTOP
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction


/**
 * Patch that injects logging into F-Stop's thumbnail pipeline so that
 * app-startup, viewpoint-entry, and bitmap-load events are recorded
 * in logcat with timestamps. This is a SEPARATE patch from
 * "Persist folder thumbnails" — it does not change any caching
 * behavior, it only adds log statements.
 *
 * <h2>Why this patch exists</h2>
 * The stock F-Stop app is essentially silent in logcat. Even with
 * {@code adb logcat --pid=$(adb shell pidof -s com.fstop.photo.morphe)}
 * running, no useful app-side activity is recorded — only generic
 * System.out spew from native code. This makes it impossible to
 * diagnose:
 * <ul>
 *   <li>How long app startup takes before the user sees the folder grid</li>
 *   <li>How long it takes for folder cover thumbnails to load after
 *       the user enters the viewpoint</li>
 *   <li>Whether the in-memory cache is hitting or missing</li>
 *   <li>Whether the cache is being cleared automatically (which would
 *       be a bug in the Persist folder thumbnails patch)</li>
 * </ul>
 *
 * <h2>Events logged (all tagged "MorpheFstop")</h2>
 * <ul>
 *   <li>{@code APP_ONCREATE} — from MyApplication.onCreate()</li>
 *   <li>{@code VIEWPOINT_ENTER} — from MainActivity.onResume()</li>
 *   <li>{@code BITMAP_LOADED} — from y1.h() (bitmap stored in cache)</li>
 *   <li>{@code BITMAP_CACHE_HIT} — from y1.f() (cache hit, bitmap returned)</li>
 *   <li>{@code CACHE_CLEARED} — from y1.b() (cache cleared)</li>
 * </ul>
 *
 * <h2>Capturing the log</h2>
 * <pre>
 *   adb logcat -c
 *   adb logcat -s MorpheFstop:I &gt; thumbnail-log.txt
 * </pre>
 *
 * <h2>How to read the log</h2>
 * Look for the sequence:
 * <pre>
 *   APP_ONCREATE t=...
 *   VIEWPOINT_ENTER t=... deltaFromAppStart=...ms
 *   BITMAP_LOADED #1 t=... deltaFromViewpoint=...ms path=...
 *   BITMAP_LOADED #2 t=... deltaFromViewpoint=...ms path=...
 *   ...
 * </pre>
 * The {@code deltaFromViewpoint} on the first BITMAP_LOADED line tells
 * you the time-to-first-thumbnail — i.e. how long the user waited
 * from opening the app to seeing the first folder cover image.
 *
 * <h2>Compatibility with the Persist folder thumbnails patch</h2>
 * Both patches can be applied together. The Persist patch NOPs the
 * automatic y1.b() callers but leaves y1.b() itself intact, so the
 * CACHE_CLEARED log line will still fire when the user clicks the
 * Settings -> Main -> Cache -> "Refresh thumbnail cache" button —
 * exactly as expected.
 */
@Suppress("unused")
val thumbnailCacheLoggerPatch = bytecodePatch(
    name = "Thumbnail cache logger",
    description = "Injects log statements into F-Stop's thumbnail pipeline " +
        "(tagged \"MorpheFstop\") so you can diagnose thumbnail-load " +
        "latency, cache-hit rates, and cache-clear events via logcat. " +
        "Logs APP_ONCREATE (app startup), VIEWPOINT_ENTER (MainActivity " +
        "onResume), BITMAP_LOADED (y1.h store), BITMAP_CACHE_HIT " +
        "(y1.f cache hit), CACHE_CLEARED (y1.b), SQLITE_READ (e3/b.a0 " +
        "SQLite thumbnail read attempt), and SQLITE_WRITE (e3/b.Y1 " +
        "prescan save to SQLite). The SQLITE_READ/WRITE events let you " +
        "diagnose why thumbnails don't survive a force-stop: if you see " +
        "SQLITE_WRITE events, prescan IS running; if SQLITE_READ events " +
        "on restart are followed by slow BITMAP_LOADED events, the " +
        "SQLite cache is incomplete (prescan was interrupted). " +
        "Safe to combine with the Persist folder thumbnails patch — " +
        "this patch only adds logging, it does not change caching behavior.",
) {
    compatibleWith(COMPATIBILITY_FSTOP)

    execute {
        // ═══════════════════════════════════════════════════════════════
        // Helper: smali descriptor for the logger class
        // ═══════════════════════════════════════════════════════════════
        val LOGGER_CLASS = "Lapp/morphe/extension/fstop/ThumbnailLogger;"

        // ═══════════════════════════════════════════════════════════════
        // Hook 1: MyApplication.onCreate() — log APP_ONCREATE
        // ═══════════════════════════════════════════════════════════════
        // Inject right after `invoke-super` (the first instruction).
        // The method has `.locals 6` so we have plenty of registers.
        // We use a register that's safe to clobber at this point —
        // v0 is fine because the next instruction assigns to v0 anyway.
        MyApplicationOnCreateFingerprint.method.apply {
            val impl = implementation!!
            // The first instruction is `invoke-super {p0}, ...Application;->onCreate()V`.
            // We inject immediately after it (at index 1).
            if (impl.instructions.first().opcode != Opcode.INVOKE_SUPER) {
                throw PatchException(
                    "Expected invoke-super as first instruction of MyApplication.onCreate()"
                )
            }
            addInstructions(1, """
                invoke-static {}, $LOGGER_CLASS->onAppCreate()V
            """.trimIndent())
        }

        // ═══════════════════════════════════════════════════════════════
        // Hook 2: MainActivity.onResume() — log VIEWPOINT_ENTER
        // ═══════════════════════════════════════════════════════════════
        // Inject right after `invoke-super {p0}, ...FragmentActivity;->onResume()V`.
        MainActivityOnResumeFingerprint.method.apply {
            val impl = implementation!!
            if (impl.instructions.first().opcode != Opcode.INVOKE_SUPER) {
                throw PatchException(
                    "Expected invoke-super as first instruction of MainActivity.onResume()"
                )
            }
            addInstructions(1, """
                invoke-static {}, $LOGGER_CLASS->onViewpointEnter()V
            """.trimIndent())
        }

        // ═══════════════════════════════════════════════════════════════
        // Hook 3: y1.h(String, int, Bitmap, boolean, int) — log BITMAP_LOADED
        // ═══════════════════════════════════════════════════════════════
        // y1.h() is the bitmap-store method. p1 = file path (String).
        // Method signature: public h(Ljava/lang/String;ILandroid/graphics/Bitmap;ZI)V
        // We inject a call to ThumbnailLogger.onBitmapLoaded(p1) at the
        // very start of the method. The method has `.locals 3` which
        // is enough to add a single invoke-static that only uses p1.
        //
        // The logger call is BEFORE the monitor-enter, so it does NOT
        // need to hold the y1.b[] lock — safe.
        Y1StoreBitmapFingerprint.method.apply {
            val impl = implementation!!
            // Inject at index 0 — before everything. The method body
            // starts with `iget-object v0, p0, ...->b:[Ly1$a;` which
            // we move after our logger call.
            addInstructions(0, """
                invoke-static {p1}, $LOGGER_CLASS->onBitmapLoaded(Ljava/lang/String;)V
            """.trimIndent())
        }

        // ═══════════════════════════════════════════════════════════════
        // Hook 4: y1.f(String, int, h3.b, boolean, int) — log BITMAP_CACHE_HIT
        // ═══════════════════════════════════════════════════════════════
        // y1.f() looks up a bitmap in the cache. p1 = file path (String).
        // There are 2 return paths:
        //   1. Cache hit: returns the cached bitmap (around line 854)
        //   2. Cache miss: returns null (line 921) after triggering async load
        //
        // We need to log only on the cache-hit path. The hit path is:
        //   monitor-exit v1
        //   return-object v2
        //
        // We replace this 2-instruction sequence with:
        //   invoke-static {p1}, ThumbnailLogger->onBitmapCacheHit(String)V
        //   monitor-exit v1
        //   return-object v2
        //
        // This way the logger is called ONLY on cache hit (just before
        // the bitmap is returned). The monitor-exit must happen BEFORE
        // return-object but AFTER the logger call (logger is non-blocking
        // and doesn't touch the cache).
        Y1GetBitmapFingerprint.method.apply {
            val impl = implementation!!

            // Find the cache-hit return path: a `monitor-exit` immediately
            // followed by `return-object`. This is the "if-eqz v2, :cond_1
            // monitor-exit v1 / return-object v2" sequence in the smali.
            val instructions = impl.instructions.toList()
            var hitReturnIndex = -1
            for (i in 0 until instructions.size - 1) {
                val instr = instructions[i]
                val next = instructions[i + 1]
                if (instr.opcode == Opcode.MONITOR_EXIT &&
                    next.opcode == Opcode.RETURN_OBJECT
                ) {
                    hitReturnIndex = i
                    break
                }
            }
            if (hitReturnIndex == -1) {
                throw PatchException(
                    "Could not find cache-hit return path (monitor-exit + return-object) in y1.f()"
                )
            }

            // Insert the logger call BEFORE the monitor-exit. We use
            // addInstructions at hitReturnIndex, which shifts the
            // monitor-exit + return-object down by 1 instruction.
            addInstructions(hitReturnIndex, """
                invoke-static {p1}, $LOGGER_CLASS->onBitmapCacheHit(Ljava/lang/String;)V
            """.trimIndent())
        }

        // ═══════════════════════════════════════════════════════════════
        // Hook 5: y1.b() — log CACHE_CLEARED
        // ═══════════════════════════════════════════════════════════════
        // y1.b() is the cache-clear method. We inject a logger call at
        // the very start so every cache-clear event is logged — whether
        // it's triggered by the user (Settings -> Main -> Cache ->
        // "Refresh thumbnail cache") or by an automatic path that the
        // Persist folder thumbnails patch may have missed.
        //
        // y1.b() has `.locals 1`. We can use v0 for the const-string.
        // The method body starts with `const/4 v0, 0x1` (the first
        // argument to y1.c(I)V), which we move after our logger call.
        Y1ClearCacheFingerprint.method.apply {
            val impl = implementation!!
            addInstructions(0, """
                const-string v0, "y1.b()"
                invoke-static {v0}, $LOGGER_CLASS->onCacheCleared(Ljava/lang/String;)V
            """.trimIndent())
        }

        // ═══════════════════════════════════════════════════════════════
        // Hook 6: e3/b.a0(String, y1, int) — log SQLITE_READ
        // ═══════════════════════════════════════════════════════════════
        // e3/b.a0() is the SQLite thumbnail reader. It runs
        // `SELECT MicroThumbnail FROM Thumbnail WHERE FullPath = ?` and
        // decodes the blob to a Bitmap. p1 = file path (String).
        //
        // We inject a logger call at the very start to log every SQLite
        // read attempt. This lets you see whether F-Stop is reading from
        // SQLite on app restart, and correlate with BITMAP_LOADED events
        // to determine hit/miss rate.
        //
        // e3/b.a0() has `.locals 4`. p1 is the path parameter. We inject
        // `invoke-static {p1}, ThumbnailLogger->onSQLiteRead(String)V`
        // at index 0, before the method's `b2()` call.
        E3BSQLiteReadFingerprint.method.apply {
            val impl = implementation!!
            addInstructions(0, """
                invoke-static {p1}, $LOGGER_CLASS->onSQLiteRead(Ljava/lang/String;)V
            """.trimIndent())
        }

        // ═══════════════════════════════════════════════════════════════
        // Hook 7: e3/b.Y1(int, String, Bitmap) — log SQLITE_WRITE (prescan)
        // ═══════════════════════════════════════════════════════════════
        // e3/b.Y1() is the SQLite thumbnail writer (called by prescan).
        // It scales the bitmap to a MicroThumbnail blob and does
        // INSERT/REPLACE INTO Thumbnail + UPDATE Image SET IsProcessed=1.
        // p1 = imageId (int), p2 = fullPath (String), p3 = bitmap.
        //
        // We inject a logger call at the very start to log every prescan
        // save. This lets you verify prescan is running and see its
        // progress rate. If SQLITE_WRITE events stop before all your
        // folders are processed, prescan was interrupted (e.g. by
        // force-stop) and the SQLite cache is incomplete.
        //
        // e3/b.Y1() has `.locals 3`. We inject the logger call at index 0.
        E3BSQLiteWriteFingerprint.method.apply {
            val impl = implementation!!
            addInstructions(0, """
                invoke-static {p1, p2}, $LOGGER_CLASS->onSQLiteWrite(ILjava/lang/String;)V
            """.trimIndent())
        }
    }
}
