package com.example.mymod.license;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
 */
public final class LicenseGuard {
    /** Built into the jar from gradle.properties (sunlicense.properties). */
    public record Settings(String url, String apiKey, String publicKey) {
        public static Settings load(InputStream in) throws IOException {
            Properties p = new Properties();
            if (in != null) {
                try (in) {
                    p.load(in);
                }
            }
            return new Settings(p.getProperty("url", "https://api.sunlicense.com").trim(), p.getProperty("apiKey", "").trim(),
                    p.getProperty("publicKey", "").trim());
        }
    }

    private final Settings settings;
    private final SunLicense client;
    private final Path offlineFile;
    private final Clock clock;

    public LicenseGuard(Settings settings, Path offlineFile) {
        this(settings, offlineFile, Clock.systemUTC());
    }

    LicenseGuard(Settings settings, Path offlineFile, Clock clock) {
        this.settings = settings;
        this.offlineFile = offlineFile;
        this.clock = clock;
        this.client = new SunLicense.Builder().baseUrl(settings.url()).token(settings.apiKey())
                .timeout(Duration.ofSeconds(10)).build();
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
        try {
            SunLicense.Result r = client.validate(licenseKey.trim(), Map.of("host", serverId));
            if (r.isValid()) {
                return new LicenseStatus(State.VALID, "VALID", Messages.forCode("VALID"));
            }
            if (r.getStatus() == 400) {
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
