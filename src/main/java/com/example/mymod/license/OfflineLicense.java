package com.example.mymod.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checks a SunLicense offline license file ({@code license.lic}, downloaded from the license's page in
 * the dashboard) with your organization's public key, without any network access.
 *
 * <p>The file is {@code base64url(payload JSON) + "." + base64url(Ed25519 signature)}; the signature
 * is checked over the raw payload bytes before anything in it is trusted. Only you can sign one,
 * because only SunLicense holds your private key, so a server owner can't forge it.
 */
public final class OfflineLicense {
    /** Clock difference tolerated between SunLicense and this server. */
    private static final Duration SKEW = Duration.ofMinutes(10);

    private OfflineLicense() {
    }

    /** Null when the file is good for {@code licenseKey} right now, otherwise why not. */
    public static String problem(String file, String publicKeyBase64, String licenseKey, Instant now) {
        if (file == null || publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            return "no offline license";
        }
        String[] parts = file.trim().split("\\.");
        if (parts.length != 2) {
            return "not a SunLicense offline license file";
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[1]))) {
                return "signature doesn't match (tampered, or signed for another organization)";
            }
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return "unreadable: " + e.getMessage();
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return "signed content isn't JSON";
        }
        if (!licenseKey.trim().equalsIgnoreCase(text(json, "licenseKey"))) {
            return "it's for another license key";
        }
        Instant issued = instant(text(json, "issuedAt"));
        if (issued != null && issued.isAfter(now.plus(SKEW))) {
            return "issued in the future (is this server's clock right?)";
        }
        JsonElement policy = json.get("policy");
        Instant expires = policy != null && policy.isJsonObject() ? instant(text(policy.getAsJsonObject(), "expiresAt")) : null;
        if (expires != null && !now.isBefore(expires)) {
            return "the license expired at " + expires;
        }
        Instant refresh = instant(text(json, "refreshAfter"));
        if (refresh != null && !now.isBefore(refresh)) {
            return "older than its refresh date (" + refresh + "); download a new one";
        }
        return null;
    }

    private static String text(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            try {   // epoch seconds with a fraction, as Jackson can write them
                return Instant.ofEpochMilli((long) (Double.parseDouble(s) * 1000));
            } catch (NumberFormatException n) {
                return null;
            }
        }
    }
}
