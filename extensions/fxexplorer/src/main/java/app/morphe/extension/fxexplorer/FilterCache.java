/*
 * Copyright 2026 GrennKren.
 * https://github.com/GrennKren/morphe-patches
 */
package app.morphe.extension.fxexplorer;

import java.util.HashMap;

/**
 * Per-directory filter state cache for FX Explorer.
 *
 * Stores the active filter text keyed by directory path string.
 * Also tracks the last directory path (lastPath) to correctly associate
 * filter state with the directory it was active in.
 *
 * Key insight: When L0() (directory refresh) runs, the fragment has already
 * switched to the new directory, so getPathText() returns the NEW path.
 * But the filter text (g2) belongs to the OLD directory. By tracking lastPath,
 * we can save the filter for the correct (old) directory before clearing it.
 *
 * All methods are static and thread-safe (synchronized on the internal map).
 */
@SuppressWarnings("unused")
public class FilterCache {

    private static final HashMap<String, String> filterMap = new HashMap<>();
    private static String lastPath = null;

    /**
     * Save the filter text for a directory path.
     * If filterText is null or empty, removes the cache entry for that path.
     * If path is null, does nothing (can't save without a path key).
     *
     * @param path The directory path (used as cache key)
     * @param filterText The active filter text to save, or null to remove the entry
     */
    public static synchronized void saveFilter(String path, String filterText) {
        if (path == null) return; // Can't save without a path
        if (filterText != null && !filterText.isEmpty()) {
            filterMap.put(path, filterText);
        } else {
            filterMap.remove(path);
        }
    }

    /**
     * Get the cached filter text for a directory path (without removing).
     * The cache entry persists so the filter can be restored multiple times
     * when navigating back to the same directory.
     *
     * @param path The directory path to look up
     * @return The cached filter text, or null if no filter was cached for this path
     */
    public static synchronized String getFilter(String path) {
        if (path == null) return null;
        return filterMap.get(path);
    }

    /**
     * Get the last directory path where L0() was called.
     * Used to correctly associate filter state with the directory it was active in.
     *
     * @return The last directory path, or null on first call
     */
    public static synchronized String getLastPath() {
        return lastPath;
    }

    /**
     * Set the last directory path where L0() was called.
     * Called at the beginning of L0() so the save logic knows the previous directory.
     *
     * @param path The current directory path
     */
    public static synchronized void setLastPath(String path) {
        lastPath = path;
    }

    /**
     * Remove the cached filter for a directory path.
     * Called when the user explicitly clears the filter.
     *
     * @param path The directory path
     */
    public static synchronized void removeFilter(String path) {
        if (path != null) {
            filterMap.remove(path);
        }
    }

    /**
     * Clear all cached filters and reset lastPath.
     */
    public static synchronized void clearAll() {
        filterMap.clear();
        lastPath = null;
    }
}
