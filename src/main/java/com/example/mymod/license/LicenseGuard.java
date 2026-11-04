package com.example.mymod.license;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sunlicense.SunLicense;

import com.example.mymod.license.LicenseStatus.State;

/**
 * Checks this server's license with SunLicense. Plain Java (no Minecraft code), so it can be tested and reused.
 *
 * <ol>
 *   <li>Online: {@code POST /api/v2/license/validate} through the official SDK, with this server's id as
 *       {@code host}, so each server takes one of the license's machine slots.</li>
 *   <li>A 400 is SunLicense saying no: {@link State#REJECTED} with its code.</li>
 *   <li>No answer (network, 5xx, 429, the mod's API key refused): the signed offline license file, if
 *       there is one and a public key was built in; otherwise {@link State#UNREACHABLE}, and the mod
 *       decides (see {@code MyMod.ALLOW_WHEN_UNREACHABLE}).</li>
 * </ol>
 *
 * <p>It also feeds {@link #protection()}: a valid answer carries the core's unlock (kept and cached for 7 days on
 * this machine, see {@link SealedCore}), a rejection forgets it, and each check sends the latest integrity result.
 */
public final class LicenseGuard {
    /** Built into the jar from gradle.properties (sunlicense.properties). */
    public record Settings(String url, String apiKey, String publicKey, String product) {
        public Settings(String url, String apiKey, String publicKey) {
            this(url, apiKey, publicKey, "");
        }

        public static Settings load(InputStream in) throws IOException {
            Properties p = new Properties();
            if (in != null) {
                try (in) {
                    p.load(in);
                }
            }
            return new Settings(p.getProperty("url", "https://api.sunlicense.com").trim(), p.getProperty("apiKey", "").trim(),
                    p.getProperty("publicKey", "").trim(), p.getProperty("product", "").trim());
        }
    }

    private final Settings settings;
    private final SunLicense client;
    private final Path offlineFile;
    private final Clock clock;
    private final SunLicenseProtection protection;

    public LicenseGuard(Settings settings, Path offlineFile) {
        this(settings, offlineFile, SunLicenseProtection.builder());
    }

    /** With the ownership notice's names: {@code builder.product("MyProduct", "You").terms("https://…")}. */
    public LicenseGuard(Settings settings, Path offlineFile, SunLicenseProtection.Builder notice) {
        this(settings, offlineFile, Clock.systemUTC(), notice);
    }

    LicenseGuard(Settings settings, Path offlineFile, Clock clock) {
        this(settings, offlineFile, clock, SunLicenseProtection.builder());
    }

    LicenseGuard(Settings settings, Path offlineFile, Clock clock, SunLicenseProtection.Builder protection) {
        this.settings = settings;
        this.offlineFile = offlineFile;
        this.clock = clock;
        this.client = new SunLicense.Builder().baseUrl(settings.url()).token(settings.apiKey())
                .timeout(Duration.ofSeconds(10)).build();
        // The unlock cache lives next to the offline license file.
        this.protection = protection.clock(clock).baseUrl(settings.url()).apiKey(settings.apiKey()).productCode(settings.product())
                .cacheDirectory(offlineFile == null ? null : offlineFile.toAbsolutePath().getParent()).build();
    }

    /** Core unlock, integrity, the ownership notice and protected files for this license. */
    public SunLicenseProtection protection() {
        return protection;
    }

    /**
     * The key a marketplace built into the jar, else the one from config/mymod.properties. A placeholder the store
     * didn't replace (a build that wasn't downloaded from the store) counts as no key.
     */
    public static String pickKey(String builtIn, String fromConfig) {
        if (builtIn != null && !isPlaceholder(builtIn) && !builtIn.isBlank()) {
            return builtIn.trim();
        }
        return fromConfig == null ? "" : fromConfig.trim();
    }

    /** True for an unreplaced "%%__NAME__%%". Built from parts so the store doesn't replace this check too. */
    static boolean isPlaceholder(String s) {
        String t = s.trim();
        return t.startsWith("%%" + "__") && t.endsWith("__" + "%%");
    }

    /** Blocks for up to the timeout: call it off the main thread. */
    public LicenseStatus check(String licenseKey, String serverId) {
        if (licenseKey == null || licenseKey.isBlank()) {
            return new LicenseStatus(State.NOT_CONFIGURED, null,
                    "No license key. Put yours in config/mymod.properties (license-key), then run /mymod recheck.");
        }
        if (settings.apiKey().isBlank()) {
            return new LicenseStatus(State.UNREACHABLE, null,
                    "This build has no SunLicense API key (sunlicenseApiKey in gradle.properties); the license can't be checked.");
        }
        LicenseStatus offline = null;
        protection.license(licenseKey, serverId);
        try {
            Map<String, String> context = new LinkedHashMap<>();
            context.put("host", serverId);
            context.putAll(protection.validateFields());   // integrity: OK / MODIFIED, once the core was unsealed
            SunLicense.Result r = client.validate(licenseKey.trim(), context);
            if (r.isValid()) {
                protection.accept(r.getBody());   // the core's unlock, when the product has a sealed core
                return new LicenseStatus(State.VALID, "VALID", Messages.forCode("VALID"));
            }
            if (r.getStatus() == 400) {
                protection.rejected();
                String code = code(r.getBody());
                return new LicenseStatus(State.REJECTED, code, Messages.forCode(code));
            }
            String why = r.getStatus() == 401 || r.getStatus() == 403
                    ? "SunLicense refused this build's API key (HTTP " + r.getStatus() + "): the mod author needs to update it."
                    : "SunLicense didn't answer (HTTP " + r.getStatus() + ").";
            offline = fallback(licenseKey, why);
        } catch (SunLicense.SunLicenseException e) {
            offline = fallback(licenseKey, "SunLicense can't be reached (" + rootMessage(e) + ").");
        }
        return offline;
    }

    private LicenseStatus fallback(String licenseKey, String why) {
        if (!settings.publicKey().isBlank() && offlineFile != null && Files.isRegularFile(offlineFile)) {
            String file;
            try {
                file = Files.readString(offlineFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new LicenseStatus(State.UNREACHABLE, null, why + " The offline license file can't be read: " + e.getMessage());
            }
            String problem = OfflineLicense.problem(file, settings.publicKey(), licenseKey, clock.instant());
            if (problem == null) {
                protection.acceptOffline(file, settings.publicKey());   // the file carries the core's unlock too
                return new LicenseStatus(State.VALID_OFFLINE, "VALID", why + " Using the offline license file.");
            }
            return new LicenseStatus(State.UNREACHABLE, null, why + " The offline license file isn't usable: " + problem + ".");
        }
        return new LicenseStatus(State.UNREACHABLE, null, why);
    }

    /** The code from a rejection body like {"valid":false,"status":400,"code":"REVOKED",...}. */
    static String code(String body) {
        try {
            JsonElement code = JsonParser.parseString(body).getAsJsonObject().get("code");
            return code == null || code.isJsonNull() ? null : code.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
