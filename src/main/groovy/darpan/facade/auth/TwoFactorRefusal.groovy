package darpan.facade.auth

import groovy.transform.CompileStatic

/**
 * DAR-BE-264/270 — every refusal a two-factor or passkey step can give: the message the person reads and, for the sign-in
 * and setup steps that answer with a refusal envelope (TwoFactorFacadeSupport.refused), the reason code the UIs switch on
 * (spec DAR-BE-270, "API as built"). The reason is chosen with the refusal where it is raised, never read back from the
 * message. A refusal with no reason only ever travels as a JSON-RPC error (an envelope would say REFUSED).
 * toString() is the message, so service XML can put a refusal straight into an error.
 */
@CompileStatic
enum TwoFactorRefusal {
    CODE("That code didn't work.", "CODE"),
    PASSKEY("That passkey didn't work.", "PASSKEY"),
    CODE_NEEDED("Enter the code from your authenticator app.", "PROOF_NEEDED"),
    PROOF_NEEDED("Confirm with your passkey or a code from your authenticator app.", "PROOF_NEEDED"),
    PASSWORD_NEEDED("Current password is required.", "PASSWORD"),
    PASSWORD_WRONG("Current password is incorrect.", "PASSWORD"),
    CHALLENGE_ENDED("This sign-in has expired. Enter your password again.", "CHALLENGE_ENDED"),
    SETUP_EXPIRED("Setup has expired. Start again.", "SETUP_EXPIRED"),
    PASSKEY_SETUP_EXPIRED("This passkey setup has expired. Start again.", "SETUP_EXPIRED"),
    PASSKEYS_UNAVAILABLE("Passkeys are not available on this Darpan instance.", "PASSKEYS_UNAVAILABLE"),
    PASSKEY_LIMIT("You have ${PasskeySupport.MAX_PASSKEYS} passkeys already. Remove one first.".toString(), "PASSKEY_LIMIT"),
    PASSKEY_EXISTS("That passkey is already registered.", "PASSKEY_EXISTS"),
    CANNOT_SIGN_IN("This account can no longer sign in.", "ACCOUNT_CANNOT_SIGN_IN"),
    SIGN_IN_FIRST("Sign in first.", "SIGN_IN_FIRST"),

    NOT_ENROLLED("Two-factor is not on for this account.", null),
    APP_NOT_ON("The authenticator app is not on for this account.", null),
    NO_PASSKEY("You have no passkey on this account.", null),
    PASSKEY_NOT_FOUND("That passkey was not found.", null),
    NAME_REQUIRED("A name is required.", null),
    LAST_FACTOR_REQUIRED("Two-factor is required for your role, so your last way to sign in cannot be removed.", null),
    REQUIRED_CANNOT_TURN_OFF("Two-factor is required for your role, so it cannot be turned off.", null)

    final String message
    /** The envelope's reason code; null for a refusal that is only ever a JSON-RPC error. */
    final String reason

    private TwoFactorRefusal(String message, String reason) {
        this.message = message
        this.reason = reason
    }

    @Override
    String toString() {
        return message
    }
}
