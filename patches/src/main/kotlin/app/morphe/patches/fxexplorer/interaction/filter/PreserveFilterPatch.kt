/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.patches.fxexplorer.interaction.filter

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fxexplorer.shared.Constants.COMPATIBILITY_FX_EXPLORER
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/fxexplorer/FilterCache;"

/**
 * Patch to implement per-directory, per-tab filter persistence for FX Explorer.
 *
 * Problem:
 * 1. When navigating into a subdirectory, the active filter from the parent
 *    directory remains active, filtering items in the subdirectory too.
 * 2. When returning to the parent directory, the filter was cleared entirely
 *    by V0(), losing the filter state.
 * 3. Different tabs viewing the same directory should have independent filter state.
 * 4. Filter state should persist within the current app session.
 *
 * Solution:
 * Uses a FilterCache extension class with:
 * - In-memory HashMap keyed by composite key (tabHash + ":" + pathString) for
 *   per-tab, per-directory isolation during runtime.
 * - Cache is in-memory only — automatically cleared on app process restart.
 *   This prevents stale data from causing crashes when the filter bar (o2)
 *   has not been created yet in a new session.
 * - Per-tab lastPath tracking via HashMap<Integer, String> (tabHash → lastPath).
 *
 * CRITICAL INSIGHT about getPathText() vs getDirectory():
 * - getPathText() reads from field i2 (Lkh/d), which is updated ASYNCHRONOUSLY
 *   by the directory loading task (Llf/i). When L0() runs, i2 still holds
 *   the PREVIOUSLY LOADED directory — NOT the new one the user navigated to.
 * - getDirectory() reads from the content model (getContentModel().Y), which
 *   IS updated BEFORE L0() runs. So getDirectory() correctly returns the NEW
 *   directory path during L0() execution.
 *
 * Cache key consistency:
 * Both save and restore use the SAME composite key format built from
 * getDirectory() conversion + tabHash. The save key comes from getLastPath(tabHash)
 * which was set from getDirectory() conversion in the previous L0() call.
 * This guarantees no key mismatch between save and restore operations.
 *
 * Tab awareness:
 * - Each tab (WindowModel instance, class c1) is identified by
 *   System.identityHashCode(getWindowModel()).
 * - This hash is stable within a JVM session but changes on app restart.
 * - In-memory only cache means no stale data survives restart.
 *
 * When L0() (directory refresh) runs:
 * 1. Init FilterCache with Context (no-op after first call).
 * 2. Get tabHash = identityHashCode(getWindowModel()).
 * 3. BEFORE V0(): Save current filter (g2) for the previous directory of this tab.
 *    saveFilter(tabHash, getLastPath(tabHash), g2)
 *    The key is from lastPath which was set by getDirectory() conversion in the
 *    previous L0() — same format as the restore lookup.
 * 4. Get NEW directory path from getDirectory() and convert to String
 *    (replicating getPathText() logic: instanceof Lkh/d0; → d(),
 *     else → getPath().n(context)).
 *    Store as setLastPath(tabHash, newPath) for this tab.
 * 5. V0() runs normally (clears filter for new directory).
 * 6. AFTER V0(): Check cache for the new directory of this tab.
 *    getFilter(tabHash, getLastPath(tabHash)) → restore if found.
 *
 * Key insight about W0() and EditText:
 * W0(filterText, null) applies the filter. When b2==false (first time / after V0()),
 * W0() clears EditText text, requests focus, and shows the keyboard.
 * When b2==true, W0() skips all UI initialization (no clear, no focus, no keyboard).
 *
 * Keyboard prevention strategy for auto-restore:
 * 1. Check o2 (filter bar) is not null — if null, skip restore entirely.
 *    o2 can be null if the filter bar was never created in this session
 *    (e.g., app just restarted with no in-memory cache data).
 * 2. Set b2=true BEFORE calling W0() — prevents keyboard show during restore.
 * 3. After W0(), call EditText.setText(filterText) to display the filter text.
 * 4. clearFocus() + hideSoftInputFromWindow() — ensure keyboard is hidden.
 * 5. Reset b2=false AFTER restore — so next manual Filter tap shows keyboard.
 *    Without this reset, b2 stays true and W0() would skip keyboard show
 *    even when the user explicitly taps the Filter button.
 * 6. Restore SOFT_INPUT_STATE_HIDDEN (0x2) — original window soft input mode.
 *
 * NOTE: Side-by-side installation and license compatibility are now separate
 * patches. Enable them independently as needed.
 */
@Suppress("unused")
val preserveFilterPatch = bytecodePatch(
    name = "Preserve filter on refresh",
    description = "Implements per-directory, per-tab filter persistence and scroll position preservation. " +
        "When navigating into a subdirectory, the filter is cleared. " +
        "When returning to the parent directory, the previous filter is restored. " +
        "Filter state is isolated between tabs (in-memory only, cleared on restart). " +
        "Scroll position is preserved when returning from viewing a file in an external app " +
        "(requires the 'Open files externally' patch to be enabled).",
) {
    compatibleWith(COMPATIBILITY_FX_EXPLORER)

    extendWith("extensions/fxexplorer.mpe")

    // Main bytecode patch: per-directory, per-tab filter persistence
    execute {
        DirectoryRefreshFingerprint.method.apply {
            // === MODIFICATION 1: Before V0() — save filter + set new lastPath ===
            //
            // Original L0() at v0CallIndex:
            //   invoke-virtual {p0}, Llf/s;->V0()V   // unconditional clear
            //
            // We replace this with:
            //   Step A: Init FilterCache with Context (no-op after first call).
            //   Step B: Get tabHash = identityHashCode(getWindowModel()).
            //   Step C: Save current filter (g2) for the previous directory of this tab.
            //     saveFilter(tabHash, getLastPath(tabHash), g2)
            //     The key is from lastPath which was set by getDirectory() conversion
            //     in the previous L0() call — same format as the restore lookup.
            //   Step D: Get NEW directory path from getDirectory() (content model)
            //     and convert it to a String. Store as lastPath for this tab.
            //   Step E: Call V0() to clear the filter.
            //
            // Conversion logic (same as getPathText() but from getDirectory()):
            //   if (directory instanceof Lkh/d0;) → call d() for String
            //   else → call getPath() → Lhh/f; → f.n(context) for String
            //
            // Null check: if getDirectory() returns null, skip setLastPath but
            // still call V0(). L0() itself returns early when getDirectory() is
            // null, so this is an edge case that shouldn't normally happen.
            //
            // Register usage: v0 (tabHash int, reused), v1 (path string), v2 (temp)
            // All are safe — subsequent original code overwrites v0, v1 immediately.
            //
            // Injected: 27 instructions.
            // Original V0() shifts to v0CallIndex + 27.

            val v0CallIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it is ReferenceInstruction &&
                    it.reference.toString().contains("Llf/s;->V0()V")
            }

            if (v0CallIndex == -1) {
                throw PatchException(
                    "Could not find V0() call in directory refresh method. " +
                        "The APK version may not be supported."
                )
            }

            addInstructionsWithLabels(
                v0CallIndex,
                """
                    # Step A: Init FilterCache with Context (no-op after first call)
                    iget-object v0, p0, Lnextapp/fx/ui/content/u;->activity:Lnextapp/fx/ui/content/k;
                    invoke-static {v0}, $EXTENSION_CLASS->init(Landroid/content/Context;)V

                    # Step B: Get tabHash = identityHashCode(getWindowModel())
                    invoke-virtual {p0}, Lnextapp/fx/ui/content/u;->getWindowModel()Lnextapp/fx/ui/content/c1;
                    move-result-object v0
                    invoke-static {v0}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                    move-result v0

                    # Step C: saveFilter(tabHash, getLastPath(tabHash), g2)
                    invoke-static {v0}, $EXTENSION_CLASS->getLastPath(I)Ljava/lang/String;
                    move-result-object v1
                    iget-object v2, p0, Llf/s;->g2:Ljava/lang/String;
                    invoke-static {v0, v1, v2}, $EXTENSION_CLASS->saveFilter(ILjava/lang/String;Ljava/lang/String;)V

                    # Step D: Get NEW directory path from content model
                    invoke-virtual {p0}, Llf/s;->getDirectory()Lkh/d;
                    move-result-object v1
                    if-nez v1, :dir_not_null

                    # getDirectory() is null — skip setLastPath, just clear filter
                    goto :do_clear

                    :dir_not_null
                    # Convert Lkh/d; to path string (same logic as getPathText)
                    instance-of v2, v1, Lkh/d0;
                    if-eqz v2, :not_d0
                    check-cast v1, Lkh/d0;
                    invoke-interface {v1}, Lkh/d0;->d()Ljava/lang/String;
                    move-result-object v1
                    goto :got_new_path

                    :not_d0
                    invoke-interface {v1}, Lkh/j;->getPath()Lhh/f;
                    move-result-object v1
                    iget-object v2, p0, Lnextapp/fx/ui/content/u;->activity:Lnextapp/fx/ui/content/k;
                    invoke-virtual {v1, v2}, Lhh/f;->n(Landroid/content/Context;)Ljava/lang/String;
                    move-result-object v1

                    :got_new_path
                    invoke-static {v0, v1}, $EXTENSION_CLASS->setLastPath(ILjava/lang/String;)V

                    # Step E: Always clear filter
                    :do_clear
                    invoke-virtual {p0}, Llf/s;->V0()V
                """
            )

            // Remove original V0() call (shifted by 27 injected instructions)
            removeInstruction(v0CallIndex + 27)

            // === MODIFICATION 2: After V0() — restore cached filter ===
            //
            // Uses getLastPath(tabHash) which holds the NEW directory path for this tab
            // (set in Modification 1 Step D via getDirectory() conversion).
            //
            // getFilter checks in-memory cache only (tab-specific composite key).
            // Cache is empty on app restart, preventing stale data crashes.
            //
            // Keyboard prevention strategy:
            // 1. Check o2 (filter bar) is not null — if null, skip restore entirely.
            //    o2 can be null if the filter bar was never created in this session.
            //    With in-memory-only cache, this shouldn't happen normally (filter
            //    can only be cached if o2 was previously created), but we check
            //    defensively to prevent NullPointerException.
            // 2. Set SOFT_INPUT_STATE_ALWAYS_HIDDEN (0x3) on the window
            // 3. Set b2=true BEFORE calling W0() — W0() checks b2:
            //    if false → shows keyboard + clears EditText + requestFocus
            //    if true → skips all of that. By setting b2=true before W0(),
            //    the auto-restore will NOT trigger keyboard show.
            // 4. After W0(), setText(filterText) shows the filter text in EditText
            // 5. clearFocus() + hideSoftInputFromWindow() — ensure keyboard hidden
            // 6. Reset b2=false AFTER restore — so next manual Filter tap shows
            //    keyboard. Without this, b2 stays true and W0() would skip
            //    keyboard show even on explicit user tap.
            // 7. Restore SOFT_INPUT_STATE_HIDDEN (0x2) — original mode

            addInstructionsWithLabels(
                v0CallIndex + 27,
                """
                    # Get tabHash for current tab
                    invoke-virtual {p0}, Lnextapp/fx/ui/content/u;->getWindowModel()Lnextapp/fx/ui/content/c1;
                    move-result-object v0
                    invoke-static {v0}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                    move-result v0

                    # Check cache for new directory: getFilter(tabHash, getLastPath(tabHash))
                    invoke-static {v0}, $EXTENSION_CLASS->getLastPath(I)Ljava/lang/String;
                    move-result-object v1
                    invoke-static {v0, v1}, $EXTENSION_CLASS->getFilter(ILjava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    if-eqz v1, :skip_restore

                    # Null check: o2 (filter bar) must exist before we can set b2 or restore
                    # o2 is null if the filter bar was never created in this session.
                    # With in-memory-only cache, this is a defensive check.
                    iget-object v2, p0, Llf/s;->o2:Lhf/l0;
                    if-eqz v2, :skip_restore

                    # Set SOFT_INPUT_STATE_ALWAYS_HIDDEN on window
                    iget-object v0, p0, Lnextapp/fx/ui/content/u;->activity:Lnextapp/fx/ui/content/k;
                    invoke-virtual {v0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                    move-result-object v0
                    const/16 v3, 0x3
                    invoke-virtual {v0, v3}, Landroid/view/Window;->setSoftInputMode(I)V

                    # Set b2=true BEFORE calling W0() to prevent keyboard flash.
                    # W0() checks b2: if false → shows keyboard + clears EditText.
                    # If true → skips keyboard entirely. v2 still holds o2 from null check.
                    const/4 v0, 0x1
                    iput-boolean v0, v2, Lhf/l0;->b2:Z

                    # Apply filter
                    const/4 v0, 0x0
                    invoke-virtual {p0, v1, v0}, Llf/s;->W0(Ljava/lang/String;Lhf/n;)V

                    # Set filter text in EditText
                    iget-object v0, p0, Llf/s;->o2:Lhf/l0;
                    iget-object v2, v0, Lhf/l0;->X1:Landroid/widget/EditText;
                    invoke-virtual {v2, v1}, Landroid/widget/EditText;->setText(Ljava/lang/CharSequence;)V

                    # Ensure keyboard hidden: clearFocus + hideSoftInputFromWindow
                    invoke-virtual {v2}, Landroid/view/View;->clearFocus()V
                    iget-object v1, v0, Lhf/l0;->a2:Landroid/view/inputmethod/InputMethodManager;
                    invoke-virtual {v2}, Landroid/view/View;->getWindowToken()Landroid/os/IBinder;
                    move-result-object v2
                    const/4 v0, 0x0
                    invoke-virtual {v1, v2, v0}, Landroid/view/inputmethod/InputMethodManager;->hideSoftInputFromWindow(Landroid/os/IBinder;I)Z

                    # Reset b2=false so next manual Filter tap shows keyboard properly.
                    # Without this, b2 stays true and W0() would skip keyboard show
                    # even when the user explicitly taps the Filter button.
                    iget-object v0, p0, Llf/s;->o2:Lhf/l0;
                    const/4 v1, 0x0
                    iput-boolean v1, v0, Lhf/l0;->b2:Z

                    # Restore original SOFT_INPUT_STATE_HIDDEN (0x2)
                    iget-object v0, p0, Lnextapp/fx/ui/content/u;->activity:Lnextapp/fx/ui/content/k;
                    invoke-virtual {v0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                    move-result-object v0
                    const/16 v1, 0x2
                    invoke-virtual {v0, v1}, Landroid/view/Window;->setSoftInputMode(I)V

                    :skip_restore
                    nop
                """
            )
        }

        // === MODIFICATION 3: Skip directory refresh when returning from external app ===
        //
        // Root cause of scroll position loss:
        // When the user opens a file with an external app, FX Explorer's Activity
        // goes through onPause → onStop. When the user returns, onResume() fires
        // and calls R0(true) → L0() which does a FULL directory reload. This
        // creates a new RecyclerView adapter, destroying the scroll position.
        //
        // The existing scroll restoration mechanism (setScrollPosition() after
        // setAdapter()) doesn't work reliably because of RecyclerView layout pass timing.
        //
        // Solution: Skip the refresh entirely when returning from an external app launch.
        // The FilterCache.wasExternalLaunch flag is set by DefaultAppRegistry when
        // an external app is launched (tryOpenWithDefault, tryOpenDirectly,
        // onAppLaunchedFromDialog). When onResume detects this flag, it:
        // 1. Restarts the FileObserver (which was stopped in onPause)
        // 2. Updates j2 (lastRefreshTimestamp) to prevent future refreshes
        // 3. Returns immediately — the RecyclerView is untouched, scroll position is preserved!
        //
        // This hook is a no-op if the 'Open files externally' patch is not enabled,
        // because the wasExternalLaunch flag will never be set.
        //
        // FileObserver restart:
        // - onPause() stops the FileObserver: d2.i.stopWatching()
        // - Normally, L0() creates a new FileObserver during the loading process
        // - Since we skip L0(), we manually restart the existing FileObserver
        // - d2 is Lb2/d; (wrapper), d2.i is the FileObserver (as Object, cast to ec/f)
        // - We call startWatching() on it to resume monitoring directory changes
        // - Null checks: d2 might be null (cloud storage dirs), d2.i might be null
        //
        // Register usage: v0 (multi-purpose), v1 (multi-purpose)
        // These are safe to use — the original onResume uses v0-v4, and our code
        // either returns (preserving no state) or falls through to original code
        // which overwrites all registers anyway.

        OnResumeFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    # Check if returning from external app launch
                    invoke-static {}, $EXTENSION_CLASS->consumeExternalLaunch()Z
                    move-result v0
                    if-eqz v0, :proceed_normal_refresh

                    # === Returning from external app — skip refresh to preserve scroll position ===

                    # Restart FileObserver if it exists (was stopped in onPause)
                    # d2 = Lb2/d; wrapper, d2.i = Object (actually ec/f extends FileObserver)
                    iget-object v0, p0, Llf/s;->d2:Lb2/d;
                    if-eqz v0, :skip_fo_restart
                    iget-object v1, v0, Lb2/d;->i:Ljava/lang/Object;
                    if-eqz v1, :skip_fo_restart
                    check-cast v1, Landroid/os/FileObserver;
                    invoke-virtual {v1}, Landroid/os/FileObserver;->startWatching()V

                    :skip_fo_restart
                    # Update j2 (lastRefreshTimestamp) to current time
                    # This prevents future onResume from triggering a refresh
                    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
                    move-result-wide v0
                    iput-wide v0, p0, Llf/s;->j2:J

                    # Return without calling R0() — scroll position is preserved!
                    return-void

                    :proceed_normal_refresh
                    # Not returning from external app — original onResume code continues
                    nop
                """
            )
        }
    }
}
