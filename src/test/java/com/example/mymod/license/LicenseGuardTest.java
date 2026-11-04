package com.example.mymod.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.mymod.license.LicenseStatus.State;

/** The license check against a stub of SunLicense's validation API (no network, no real keys). */
class LicenseGuardTest {
    static final String KEY = "AX8P-K7QX-M2PD-9HWT";
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    HttpServer server;
    final AtomicReference<String> request = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    volatile int status = 200;
    volatile String answer = "{\"valid\":true,\"status\":200,\"code\":\"VALID\"}";

    @TempDir
    Path dir;
    KeyPair keys;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/license/validate", ex -> {
            request.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String publicKey() {
        return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }

    LicenseGuard guard(String url) {
        return new LicenseGuard(new LicenseGuard.Settings(url, "slk_test", publicKey()), dir.resolve("license.lic"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** An offline file signed like SunLicense signs them: base64url(payload) "." base64url(Ed25519). */
    void offlineFile(String licenseKey, String expiresAt, String refreshAfter, KeyPair signer) throws Exception {
        offlineFile(licenseKey, expiresAt, refreshAfter, signer, null);
    }

    void offlineFile(String licenseKey, String expiresAt, String refreshAfter, KeyPair signer, String unlock) throws Exception {
        String payload = "{\"format\":\"sunlicense-offline-1\",\"licenseKey\":\"" + licenseKey + "\",\"policy\":{\"name\":\"Pro\",\"expiresAt\":"
                + (expiresAt == null ? "null" : "\"" + expiresAt + "\"") + "},\"issuedAt\":\"2026-10-01T00:00:00Z\",\"refreshAfter\":\""
                + refreshAfter + "\"" + (unlock == null ? "" : ",\"unlock\":\"" + unlock + "\"") + "}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(signer.getPrivate());
        s.update(bytes);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        Files.writeString(dir.resolve("license.lic"), b64.encodeToString(bytes) + "." + b64.encodeToString(s.sign()));
    }

    @Test
    void aValidLicenseSendsTheKeyServerIdAndApiKey() {
        LicenseStatus s = guard(url()).check(" " + KEY + " ", "paper-1234");
        assertEquals(State.VALID, s.state());
        assertTrue(s.allowed());
        assertEquals("{\"license\":\"" + KEY + "\",\"host\":\"paper-1234\"}", request.get());
        assertEquals("Bearer slk_test", auth.get());
    }

    @Test
    void aValidAnswerUnlocksTheCoreAndTheNextCheckReportsIntegrity() {
        String unlock = Base64.getEncoder().encodeToString(new byte[32]);
        answer = "{\"valid\":true,\"status\":200,\"code\":\"VALID\",\"unlock\":\"" + unlock + "\"}";
        LicenseGuard guard = guard(url());
        assertEquals(State.VALID, guard.check(KEY, "paper-1").state());
        assertTrue(guard.protection().unlocked());
        // Kept for 7 days in the plugin's folder (encrypted), so an outage after a restart doesn't lock the core.
        LicenseGuard restarted = guard("http://127.0.0.1:1");
        restarted.protection().license(KEY, "paper-1");
        assertTrue(restarted.protection().unlocked(), "cached unlock");

        guard.protection().integrity(name -> null, java.util.Map.of("com/example/mymod/MyPlugin.class", "00"));
        guard.check(KEY, "paper-1");
        assertTrue(request.get().contains("\"integrity\":\"MODIFIED\""), request.get());
        assertTrue(request.get().contains("\"integrityDetail\":\"1 changed: com/example/mymod/MyPlugin.class (missing)\""));

        status = 400;
        answer = "{\"valid\":false,\"status\":400,\"code\":\"SUSPENDED\"}";
        assertEquals(State.REJECTED, guard.check(KEY, "paper-1").state());
        assertFalse(guard.protection().unlocked(), "a rejected license gets no unlock");
        LicenseGuard again = guard("http://127.0.0.1:1");
        again.protection().license(KEY, "paper-1");
        assertFalse(again.protection().unlocked(), "and the cache is gone");
    }

    @Test
    void anOfflineFileCarriesTheUnlock() throws Exception {
        status = 503;
        answer = "{}";
        offlineFile(KEY, "2027-01-01T00:00:00Z", "2026-11-01T00:00:00Z", keys,
                Base64.getEncoder().encodeToString(new byte[32]));
        LicenseGuard guard = guard(url());
        assertEquals(State.VALID_OFFLINE, guard.check(KEY, "paper-1").state());
        assertTrue(guard.protection().unlocked());
    }

    @Test
    void everyRejectionCodeGetsItsOwnMessage() {
        status = 400;
        for (String code : new String[] {"EXPIRED", "REVOKED", "SUSPENDED", "TOO_MANY_MACHINES", "NOT_FOUND", "BLACKLISTED"}) {
            answer = "{\"valid\":false,\"status\":400,\"message\":\"x\",\"code\":\"" + code + "\"}";
            LicenseStatus s = guard(url()).check(KEY, "paper-1");
            assertEquals(State.REJECTED, s.state(), code);
            assertEquals(code, s.code());
            assertEquals(Messages.forCode(code), s.message());
        }
    }

    @Test
    void anOutageFallsBackToTheOfflineFile() throws Exception {
        status = 503;
        answer = "{}";
        assertEquals(State.UNREACHABLE, guard(url()).check(KEY, "paper-1").state(), "no file");

        offlineFile(KEY, "2027-01-01T00:00:00Z", "2026-11-01T00:00:00Z", keys);
        LicenseStatus s = guard(url()).check(KEY, "paper-1");
        assertEquals(State.VALID_OFFLINE, s.state(), s.message());

        // A server that can't be reached at all, same answer.
        assertEquals(State.VALID_OFFLINE, guard("http://127.0.0.1:1").check(KEY, "paper-1").state());
    }

    @Test
    void offlineFilesMustBeSignedByYouForThisKeyAndCurrent() throws Exception {
        status = 503;
        offlineFile(KEY, null, "2026-11-01T00:00:00Z", KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertTrue(guard(url()).check(KEY, "p").message().contains("signature"), "signed by someone else");

        offlineFile("OTHER-KEY", null, "2026-11-01T00:00:00Z", keys);
        assertTrue(guard(url()).check(KEY, "p").message().contains("another license key"));

        offlineFile(KEY, "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", keys);
        assertTrue(guard(url()).check(KEY, "p").message().contains("expired"));

        offlineFile(KEY, null, "2026-10-15T00:00:00Z", keys);
        assertTrue(guard(url()).check(KEY, "p").message().contains("refresh"));

        offlineFile(KEY, null, "2026-11-01T00:00:00Z", keys);
        String tampered = Files.readString(dir.resolve("license.lic"));
        Files.writeString(dir.resolve("license.lic"), "x" + tampered.substring(1));
        assertEquals(State.UNREACHABLE, guard(url()).check(KEY, "p").state());
    }

    @Test
    void aRefusedApiKeyIsTheAuthorsProblemNotTheServerOwners() {
        status = 401;
        answer = "{\"title\":\"Unauthorized\"}";
        LicenseStatus s = guard(url()).check(KEY, "p");
        assertEquals(State.UNREACHABLE, s.state());
        assertTrue(s.message().contains("API key"));
    }

    @Test
    void noKeyMeansNotConfigured() {
        assertEquals(State.NOT_CONFIGURED, guard(url()).check("  ", "p").state());
        assertNull(request.get(), "nothing is sent without a key");
    }

    @Test
    void theStoreKeyWinsUnlessTheStoreDidntReplaceThePlaceholder() {
        assertEquals("K7QX-M2PD", LicenseGuard.pickKey("K7QX-M2PD", "FROM-CONFIG"));
        assertEquals("FROM-CONFIG", LicenseGuard.pickKey("%%" + "__SUNLICENSE__" + "%%", " FROM-CONFIG "));
        assertEquals("", LicenseGuard.pickKey("%%" + "__SUNLICENSE__" + "%%", null));
    }

    @Test
    void keysAreMaskedInLogs() {
        assertEquals("AX8P-XXXX-XXXX-XXXX", Messages.mask(KEY));
        assertEquals("ABCDXXXXXXXX", Messages.mask("ABCDEFGHIJKL"));
        assertEquals("(none)", Messages.mask(""));
    }

    @Test
    void settingsComeFromTheBuiltInProperties() throws IOException {
        var s = LicenseGuard.Settings.load(new java.io.ByteArrayInputStream("url=http://localhost\napiKey=slk_x\npublicKey=\n".getBytes()));
        assertEquals("http://localhost", s.url());
        assertEquals("slk_x", s.apiKey());
        assertEquals("", s.publicKey());
    }
}
