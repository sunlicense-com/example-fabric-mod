package com.example.mymod.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sealed-core pattern: release builds load the core only with a valid unlock, dev builds as usual. */
class SealedCoreTest {
    static final byte[] PRODUCT_KEY = random(32);

    @TempDir
    Path dir;

    /** Stands in for core/CoreFeatures. */
    public static final class FakeCore implements Supplier<String> {
        @Override
        public String get() {
            return "core";
        }
    }

    SunLicenseProtection protection() {
        return SunLicenseProtection.builder().licenseKey("AX8P-K7QX-M2PD-9HWT").fingerprint("paper-1")
                .cacheDirectory(null).build();
    }

    @Test
    void developmentBuildsLoadTheCoreStraightFromTheJar() throws Exception {
        SunLicenseProtection p = protection();
        Supplier<?> core = SealedCore.load(p, getClass().getClassLoader(), FakeCore.class.getName(), Supplier.class);
        assertEquals("core", core.get());
        assertFalse(SealedCore.sealed(getClass().getClassLoader()));
        assertTrue(p.validateFields().isEmpty(), "nothing to report without sealed digests");
    }

    @Test
    void releaseBuildsNeedTheUnlockAndReportTheJarsIntegrity() throws Exception {
        // A "release jar" laid out as a directory: one plain class and the sealed core.
        Path jar = dir.resolve("jar");
        Files.createDirectories(jar.resolve("sunlicense"));
        Files.createDirectories(jar.resolve("com/example"));
        Files.write(jar.resolve("com/example/Main.class"), new byte[] {1, 2, 3});
        Files.writeString(jar.resolve(SealedCore.RESOURCE), seal(coreJar(), Map.of("com/example/Main.class", sha(new byte[] {1, 2, 3}))));

        SunLicenseProtection p = protection();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            assertTrue(SealedCore.sealed(loader));
            assertThrows(SunLicenseProtection.Locked.class, () -> SealedCore.load(p, loader, FakeCore.class.getName(), Supplier.class));

            p.accept("{\"valid\":true,\"code\":\"VALID\",\"unlock\":\"" + Base64.getEncoder().encodeToString(PRODUCT_KEY) + "\"}");
            Supplier<?> core = SealedCore.load(p, loader, FakeCore.class.getName(), Supplier.class);
            assertEquals("core", core.get());
            assertNotSame(getClass().getClassLoader(), core.getClass().getClassLoader());
            assertEquals("OK", p.validateFields().get("integrity"));

            Files.write(jar.resolve("com/example/Main.class"), new byte[] {9});   // someone patched the jar
            SealedCore.checkEarly(p, loader);
            assertEquals("MODIFIED", p.validateFields().get("integrity"));
        }
    }

    byte[] coreJar() throws Exception {
        String name = FakeCore.class.getName().replace('.', '/') + ".class";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = FakeCore.class.getClassLoader().getResourceAsStream(name);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(in.readAllBytes());
        }
        return out.toByteArray();
    }

    /** Seals like SunLicense's seal endpoint (format: docs.sunlicense.com, spec 203). */
    static String seal(byte[] data, Map<String, String> digests) throws Exception {
        StringBuilder json = new StringBuilder("{\"v\":1,\"data\":\"").append(Base64.getEncoder().encodeToString(data)).append("\",\"digests\":{");
        int i = 0;
        for (Map.Entry<String, String> d : digests.entrySet()) {
            json.append(i++ == 0 ? "" : ",").append('"').append(d.getKey()).append("\":\"").append(d.getValue()).append('"');
        }
        json.append("}}");
        byte[] nonce = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(PRODUCT_KEY, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD("SLS1".getBytes(StandardCharsets.US_ASCII));
        byte[] ct = c.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return "SLS1." + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    static String sha(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) {
            s.append(String.format("%02x", x));
        }
        return s.toString();
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
