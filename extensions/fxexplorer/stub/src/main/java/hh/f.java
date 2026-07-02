/*
 * Stub for FX Explorer's path class (Lhh/f;).
 * Used only at compile time so extension code can reference APK classes.
 * At runtime, the real class from the APK is used instead.
 *
 * IMPORTANT: The real hh/f.toString() returns the path as a "/" separated
 * string (e.g. "/storage/emulated/0/some/file.txt"). Our extension code
 * calls fileItem.getPath().toString() to get the file path string.
 *
 * The return type of getPath() in kh/j MUST be hh/f (NOT Object),
 * because the real APK's kh/j interface declares getPath()Lhh/f;.
 * Using Object as return type causes a descriptor mismatch at runtime:
 * the DEX calls getPath:()Ljava/lang/Object; but the real method is
 * getPath:()Lhh/f; — these are different descriptors → NoSuchMethodError.
 */
package hh;

@SuppressWarnings("unused")
public class f {
    /**
     * Returns the path as a "/" separated string.
     * The real implementation joins path components with "/".
     */
    @Override
    public String toString() {
        return "";
    }
}
