package com.example.mymod.license;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the plugin's core (the {@code core} package) the way the build shipped it.
 *
 * <ul>
 *   <li><b>Release build</b> ({@code ./gradlew build -PsunlicenseProduct=<code>}): the core's classes aren't in the
 *       jar as classes. They're inside {@value #RESOURCE}, encrypted by {@code sunlicense seal}, and only a valid
 *       license check (or its cached unlock, or an offline license file) can decrypt them. A copy whose license
 *       check was cut out has no core to run.</li>
 *   <li><b>Development build</b> (no product code): the core is in the jar like any other class.</li>
 * </ul>
 *
 * <p>Unsealing also checks the rest of the jar against the digests sealed with it; a modified jar is reported with
 * the next license check (it still runs: SunLicense decides what happens to the license).
 */
public final class SealedCore {
    /** Written into release jars by the build's sealCore task. */
    public static final String RESOURCE = "sunlicense/core.sealed";

    private SealedCore() {
    }

    /** True in release builds. */
    public static boolean sealed(ClassLoader loader) {
        return loader.getResource(RESOURCE) != null;
    }

    /**
     * Creates the core's entry class ({@code className}, a public no-argument constructor implementing {@code type}).
     * Throws {@link SunLicenseProtection.Locked} in a release build without a valid unlock.
     */
    public static <T> T load(SunLicenseProtection protection, ClassLoader loader, String className, Class<T> type)
            throws SunLicenseProtection.Locked, IOException, ReflectiveOperationException {
        ClassLoader from = loader;
        InputStream sealed = loader.getResourceAsStream(RESOURCE);
        if (sealed != null) {
            byte[] core = protection.unseal(sealed);
            protection.integrity(loader);
            from = SunLicenseProtection.classLoader(core, loader);
        }
        return type.cast(Class.forName(className, true, from).getDeclaredConstructor().newInstance());
    }

    /**
     * At start-up, with an unlock still cached from an earlier run: checks the jar now, so the very first license
     * check already reports it. Does nothing in development builds or without an unlock.
     */
    public static void checkEarly(SunLicenseProtection protection, ClassLoader loader) {
        if (!protection.unlocked()) {
            return;
        }
        try {
            InputStream sealed = loader.getResourceAsStream(RESOURCE);
            if (sealed != null) {
                protection.unseal(sealed);
                protection.integrity(loader);
            }
        } catch (SunLicenseProtection.Locked | IOException | RuntimeException e) {
            // checked again when the core loads
        }
    }
}
