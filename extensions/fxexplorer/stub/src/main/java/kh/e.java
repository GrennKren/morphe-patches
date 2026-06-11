/*
 * Stub for FX Explorer's file item interface (Lkh/e;).
 * Extends kh/j (which has getName() and getPath()).
 * Used only at compile time. At runtime, the real class from the APK is used.
 *
 * CRITICAL: w() is declared in kh/e, NOT in kh/j!
 * APK analysis confirmed: kh/j has getName() and getPath(), but w() is ONLY in kh/e.
 * If w() is mistakenly placed in kh/j, R8 compiles invoke-interface Lkh/j;->w()
 * but the real DEX has no such method on kh/j → NoSuchMethodError at runtime.
 * This was the root cause of tryOpenDirectly() always returning false!
 *
 * kh/e also declares: C0(Context,long)OutputStream, f(Context)InputStream, getSize()J
 * But we only need w() for our extension code.
 */
package kh;

@SuppressWarnings("unused")
public interface e extends j {
    /**
     * Returns the MIME type of this file item.
     * MUST be declared here in kh/e, NOT in kh/j!
     */
    String w();
}
