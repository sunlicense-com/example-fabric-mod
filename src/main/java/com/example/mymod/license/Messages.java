package com.example.mymod.license;

/** What to tell the server owner for each SunLicense result code. Edit the wording freely. */
public final class Messages {
    private Messages() {
    }

    public static String forCode(String code) {
        if (code == null) {
            return "The license was not accepted.";
        }
        return switch (code) {
            case "VALID" -> "License is valid.";
            case "NOT_FOUND" -> "This license key doesn't exist. Check license-key in config/mymod.properties (no spaces), "
                    + "or download the mod again from the store you bought it on.";
            case "EXPIRED" -> "This license has expired. Renew it where you bought the mod.";
            case "REVOKED" -> "This license has been revoked. Contact the mod author if you think this is a mistake.";
            case "SUSPENDED" -> "This license is suspended for now. Contact the mod author.";
            case "TOO_MANY_MACHINES" -> "This license is already used on the maximum number of servers. "
                    + "Free a slot by removing the mod from another server, or ask the author to reset your servers.";
            case "BLACKLISTED" -> "This server or network has been blocked by the mod author. Contact them for help.";
            default -> "The license was not accepted (" + code + ").";
        };
    }

    /** "AX8P-XXXX-XXXX-XXXX": never print a whole key to a console others may read. */
    public static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "(none)";
        }
        String[] groups = key.trim().split("-");
        if (groups.length == 1) {   // a key without dashes, e.g. one a store issued
            String k = groups[0];
            return k.substring(0, Math.min(4, k.length())) + "X".repeat(Math.max(0, k.length() - 4));
        }
        StringBuilder out = new StringBuilder(groups[0]);
        for (int i = 1; i < groups.length; i++) {
            out.append('-').append("X".repeat(groups[i].length()));
        }
        return out.toString();
    }
}
