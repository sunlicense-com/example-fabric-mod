package com.example.mymod.license;

/**
 * The answer to "may this server use the mod?".
 *
 * @param state   what to do
 * @param code    SunLicense's result code (VALID, EXPIRED, REVOKED, SUSPENDED, TOO_MANY_MACHINES,
 *                NOT_FOUND, BLACKLISTED), or null when SunLicense didn't answer
 * @param message a sentence for the server owner's console
 */
public record LicenseStatus(State state, String code, String message) {

    public enum State {
        /** SunLicense said yes. */
        VALID,
        /** SunLicense couldn't be reached, but a signed offline license file says yes. */
        VALID_OFFLINE,
        /** SunLicense said no: stop the mod. */
        REJECTED,
        /** No answer (outage, network, rate limit, the mod's API key refused): retry later. */
        UNREACHABLE,
        /** No license key to check. */
        NOT_CONFIGURED
    }

    public boolean allowed() {
        return state == State.VALID || state == State.VALID_OFFLINE;
    }
}
