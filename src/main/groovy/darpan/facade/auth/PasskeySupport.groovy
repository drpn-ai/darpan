package darpan.facade.auth

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationData
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.MaliciousCounterValueException
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonOutput
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-270 — passkeys and security keys (WebAuthn) as a second factor, beside DAR-BE-264's authenticator app. Spec:
 * wrapper docs/superpowers/specs/2026-11-13-dar-be-270-passkeys-design.md. The only class that touches webauthn4j.
 *
 * <p>A passkey is a TwoFactorFactor row (DrpnTfPkey) plus its PasskeyCredential. Every ceremony starts with a
 * SignInChallenge row holding a fresh webauthnChallenge: begin#PasskeySignIn sets it on the sign-in row, begin#PasskeyProof
 * and begin#PasskeyRegistration make a row whose token is the challenge itself (SignInChallenges.create with webauthn), so
 * the clientDataJSON the browser signs names its row. Any attempt against a row uses its challenge up.
 *
 * <p>Checked on every assertion (webauthn4j): origin in the allowed set, rpId hash, the stored challenge, the signature
 * with the stored COSE key, user present and user verified, the credential among the person's live passkeys, and the
 * signature counter. Never logged: assertions, attestation objects, challenges.
 */
class PasskeySupport {
    private static final Logger logger = LoggerFactory.getLogger(PasskeySupport.class)

    static final String CREDENTIAL = "darpan.auth.PasskeyCredential"
    /** DAR-BE-301: the rows of a sign-in with a passkey alone (begin/finish#PasskeyFirstSignIn). */
    static final String FIRST_SIGN_IN_CHALLENGE = "darpan.auth.PasskeySignInChallenge"
    static final int FIRST_SIGN_IN_BEGINS_PER_MINUTE = 10
    static final int USERNAME_HINT_MAX_LENGTH = 255
    private static final String ACCOUNT = "moqui.security.UserAccount"
    /** A decoy descriptor's transports: what a platform passkey reports most often. */
    static final String DECOY_TRANSPORTS = "internal,hybrid"
    static final String AUTH_SECRET = "darpan.auth.AuthSecret"
    static final String DECOY_SECRET_ID = "PASSKEY_DECOY"
    /** [factory, key]: the decoy key as read for one framework instance (tests boot several in one JVM). */
    private static volatile Map decoyKeyCache

    /** Test-only: forgets the cached decoy key. Idempotent, never throws. */
    static void resetForTests() {
        try { decoyKeyCache = null } catch (Throwable ignored) { }
    }

    static final int MAX_PASSKEYS = 10
    static final int NAME_MAX_LENGTH = 60
    static final int CEREMONY_TIMEOUT_MILLIS = 120_000
    static final String DEFAULT_NAME = "Passkey"

    static final String TEMPLATE_PASSKEY_ADDED = "DARPAN_2FA_PASSKEY_ADDED"

    /** ES256 first (every platform authenticator), then EdDSA and RS256 (Windows Hello, some security keys). */
    static final List<COSEAlgorithmIdentifier> ALGORITHMS = [COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA,
            COSEAlgorithmIdentifier.RS256].asImmutable()

    private static final ObjectConverter objectConverter = new ObjectConverter()
    /** attestation "none" is asked for; the non-strict manager also accepts a format an authenticator sends anyway, without
     *  trusting it (no FIDO metadata: spec open question 4). */
    private static final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    /** A credential is stored as webauthn4j's own attested-credential-data bytes (AAGUID, credential id, COSE key). */
    private static final AttestedCredentialDataConverter attestedConverter = new AttestedCredentialDataConverter(objectConverter)

    // ---- relying party (per instance) ----

    /** This instance's relying party: its id and the origins a ceremony may come from. Built once per call. */
    static final class RelyingParty {
        final String id
        final Set<String> origins

        RelyingParty(String id, Set<String> origins) {
            this.id = id
            this.origins = origins.asImmutable()
        }

        ServerProperty serverProperty(String challenge) {
            return ServerProperty.builder().origins(origins.collect { new Origin(it) } as Set<Origin>).rpId(id)
                    .challenge(new DefaultChallenge(challenge)).build()
        }
    }

    /** The relying party, or null when passkeys are unavailable here (no rpId, or no allowed origin on it). */
    static RelyingParty relyingParty(def ec) {
        String id = rpId(ec)
        if (!id) return null
        Set<String> allowed = originsFor(id, originCandidates(ec))
        return allowed ? new RelyingParty(id, allowed) : null
    }

    static boolean available(def ec) {
        return relyingParty(ec) != null
    }

    /**
     * The relying party id: DARPAN_WEBAUTHN_RP_ID / darpan.webauthn.rpId, else the host of the app origin
     * (DARPAN_APP_BASE_URL, else the first non-* webapp_allow_origins entry). The UI's host, never the API's: the browser
     * binds a passkey to the page that made it. Null = passkeys unavailable here.
     */
    private static String rpId(def ec) {
        String configured = normalize(System.getenv("DARPAN_WEBAUTHN_RP_ID")) ?: normalize(System.getProperty("darpan.webauthn.rpId"))
        if (configured) return configured.toLowerCase()
        return hostOf(TenantNotificationSupport.resolveAppBaseUrl(ec))
    }

    /** DARPAN_WEBAUTHN_ORIGINS / darpan.webauthn.origins (comma list), else the webapp_allow_origins entries plus the app
     *  origin. originsFor keeps only the ones on the rpId. */
    private static List<String> originCandidates(def ec) {
        String configured = normalize(System.getenv("DARPAN_WEBAUTHN_ORIGINS")) ?: normalize(System.getProperty("darpan.webauthn.origins"))
        if (configured) return configured.split(",").collect { it.trim() }
        // default-property values live in System properties (see TenantNotificationSupport.resolveFirstAllowedOrigin)
        String allowed = normalize((ec?.resource?.properties?.get("webapp_allow_origins") ?: System.getProperty("webapp_allow_origins"))?.toString())
        return (allowed ? allowed.split(",").collect { it.trim() } : []) + [TenantNotificationSupport.resolveAppBaseUrl(ec)]
    }

    /** Pure, for tests: the candidates that are well-formed origins on rp (or under it), https or http://localhost. */
    static Set<String> originsFor(String rp, List<String> candidates) {
        Set<String> out = new LinkedHashSet<>()
        candidates.each { String raw ->
            String value = normalize(raw)?.replaceAll(/\/+$/, "")
            if (!value || value == "*") return
            try {
                URI uri = URI.create(value)
                String host = uri.host?.toLowerCase()
                if (!host || uri.path || uri.query) return
                if (!(host == rp || host.endsWith("." + rp))) return
                boolean local = host == "localhost"
                if (!(uri.scheme == "https" || (uri.scheme == "http" && local))) return
                out << "${uri.scheme}://${host}${uri.port > 0 ? ':' + uri.port : ''}".toString()
            } catch (Exception ignored) {
                // not an origin
            }
        }
        return out
    }

    private static String hostOf(String url) {
        if (!url) return null
        try {
            return URI.create(url).host?.toLowerCase()
        } catch (Exception ignored) {
            return null
        }
    }

    // ---- reads ----

    /** The person's live passkeys: [factor, credential] pairs, oldest first. */
    static List<Map> activePasskeys(def ec, String userId) {
        if (!userId) return []
        return withCredentials(ec, TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "self-scoped auth: the person's live passkeys")
                .condition("userId", userId).condition("factorTypeEnumId", TwoFactorRecords.TYPE_PASSKEY)
                .condition("statusId", TwoFactorRecords.ACTIVE).orderBy("confirmedDate").useCache(false).list())
    }

    /** For get#TwoFactorStatus, from the person's active factors already read: never the key or the credential id. */
    static List<Map> describe(def ec, List activeFactors) {
        List passkeyFactors = activeFactors.findAll { it.factorTypeEnumId == TwoFactorRecords.TYPE_PASSKEY }
                .sort { it.confirmedDate }
        return withCredentials(ec, passkeyFactors).collect { Map p ->
            [passkeyId   : p.factor.factorId, name: p.credential.name, createdDate: p.factor.confirmedDate,
             lastUsedDate: p.factor.lastUsedDate, transports: splitTransports(p.credential.transports),
             backedUp    : p.credential.backedUp == "Y"]
        }
    }

    /** [factor, credential] pairs for passkey factor rows, in their order; one read of the credentials. */
    private static List<Map> withCredentials(def ec, List factors) {
        if (!factors) return []
        Map<String, Object> credentials = TwoFactorRecords.find(ec, CREDENTIAL, "self-scoped auth: the person's passkey credentials")
                .condition("factorId", EntityCondition.IN, factors*.factorId).useCache(false).list()
                .collectEntries { [(it.factorId): it] }
        return factors.findAll { credentials[it.factorId] != null }.collect { [factor: it, credential: credentials[it.factorId]] }
    }

    // ---- ceremony options ----

    /** begin#PasskeySignIn: a fresh challenge on a live sign-in row and request options for the person's passkeys.
     *  Returns [publicKeyOptions], [challengeEnded: true] or [refusal]. */
    static Map<String, Object> signInOptions(def ec, Object challengeToken) {
        RelyingParty rp = relyingParty(ec)
        if (rp == null) return [refusal: TwoFactorRefusal.PASSKEYS_UNAVAILABLE]
        return (Map<String, Object>) ec.transaction.runRequireNew(null, "Could not start a passkey sign-in", {
            def row = SignInChallenges.live(ec, challengeToken, true)
            if (row == null || row.purposeEnumId != SignInChallenges.PURPOSE_CODE) return [challengeEnded: true]
            List<Map> passkeys = activePasskeys(ec, (String) row.userId)
            if (!passkeys) return [refusal: TwoFactorRefusal.PASSKEY]
            String challenge = AuthPrimitives.newToken()
            row.webauthnChallenge = challenge
            row.update()
            return [publicKeyOptions: requestOptionsJson(rp, challenge, passkeys)]
        })
    }

    /** begin#PasskeyProof: request options for the signed-in person, for one fresh-proof action. [publicKeyOptions,
     *  expiresInSeconds] or [refusal]. */
    static Map<String, Object> proofOptions(def ec) {
        String userId = normalize(ec.user.userId)
        if (!userId) return [refusal: TwoFactorRefusal.SIGN_IN_FIRST]
        RelyingParty rp = relyingParty(ec)
        if (rp == null) return [refusal: TwoFactorRefusal.PASSKEYS_UNAVAILABLE]
        List<Map> passkeys = activePasskeys(ec, userId)
        if (!passkeys) return [refusal: TwoFactorRefusal.NO_PASSKEY]
        String challenge = SignInChallenges.create(ec, userId, SignInChallenges.PURPOSE_PROOF, true)
        return [publicKeyOptions: requestOptionsJson(rp, challenge, passkeys), expiresInSeconds: SignInChallenges.LIFETIME_SECONDS]
    }

    /** begin#PasskeyRegistration (the caller has checked the fresh proof): creation options for the person.
     *  [publicKeyOptions, expiresInSeconds] or [refusal]. */
    static Map<String, Object> registrationOptions(def ec, String userId) {
        RelyingParty rp = relyingParty(ec)
        if (rp == null) return [refusal: TwoFactorRefusal.PASSKEYS_UNAVAILABLE]
        List<Map> passkeys = activePasskeys(ec, userId)
        if (passkeys.size() >= MAX_PASSKEYS) return [refusal: TwoFactorRefusal.PASSKEY_LIMIT]
        def account = TwoFactorRecords.find(ec, "moqui.security.UserAccount", "self-scoped auth: the account adding a passkey")
                .condition("userId", userId).useCache(false).one()
        String challenge = SignInChallenges.create(ec, userId, SignInChallenges.PURPOSE_ADD, true)
        String username = (String) account?.username ?: userId
        Map options = [
                rp                    : [id: rp.id, name: TotpSupport.issuer()],
                user                  : [id: userHandle(userId), name: username, displayName: normalize(account?.userFullName) ?: username],
                challenge             : challenge,
                pubKeyCredParams      : ALGORITHMS.collect { [type: "public-key", alg: it.value] },
                timeout               : CEREMONY_TIMEOUT_MILLIS,
                excludeCredentials    : passkeys.collect { descriptor(it.credential) },
                authenticatorSelection: [residentKey: "preferred", requireResidentKey: false, userVerification: "required"],
                attestation           : "none",
        ]
        return [publicKeyOptions: JsonOutput.toJson(options), expiresInSeconds: SignInChallenges.LIFETIME_SECONDS]
    }

    private static String requestOptionsJson(RelyingParty rp, String challenge, List<Map> passkeys) {
        return JsonOutput.toJson([challenge       : challenge, rpId: rp.id, timeout: CEREMONY_TIMEOUT_MILLIS,
                                  allowCredentials: passkeys.collect { descriptor(it.credential) }, userVerification: "required"])
    }

    private static Map descriptor(def credential) {
        Map d = [type: "public-key", id: credential.credentialId]
        List<String> transports = splitTransports(credential.transports)
        if (transports) d.transports = transports
        return d
    }

    // ---- registration ----

    /**
     * finish#PasskeyRegistration's check and store (call inside a transaction): the registration row named by the
     * credential's clientDataJSON must be live, of this person, and is used up whatever happens; then webauthn4j checks
     * the attestation (origin, rpId hash, challenge, user present + verified, algorithm) and no live passkey anywhere may
     * have the credential id. Returns [factorId, name] or [refusal].
     */
    static Map<String, Object> register(def ec, String userId, Object credentialJson, Object name) {
        RelyingParty rp = relyingParty(ec)
        if (rp == null) return [refusal: TwoFactorRefusal.PASSKEYS_UNAVAILABLE]
        String json = normalize(credentialJson)
        if (!json) return [refusal: TwoFactorRefusal.PASSKEY]
        RegistrationData parsed
        try {
            parsed = manager.parseRegistrationResponseJSON(json)
        } catch (Exception e) {
            logger.info("Passkey registration refused for userId {}: unreadable credential ({})", userId, e.class.simpleName)
            return [refusal: TwoFactorRefusal.PASSKEY]
        }
        String challenge = presentedChallenge(parsed.collectedClientData)
        if (SignInChallenges.claimCeremony(ec, challenge, SignInChallenges.PURPOSE_ADD, userId) == null) {
            return [refusal: TwoFactorRefusal.PASSKEY_SETUP_EXPIRED]
        }
        if (activePasskeys(ec, userId).size() >= MAX_PASSKEYS) return [refusal: TwoFactorRefusal.PASSKEY_LIMIT]

        RegistrationData verified
        try {
            List<PublicKeyCredentialParameters> params = ALGORITHMS.collect { new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) }
            verified = manager.verify(parsed, new RegistrationParameters(rp.serverProperty(challenge), params, true, true))
        } catch (Exception e) {
            logger.info("Passkey registration refused for userId {}: {}", userId, e.class.simpleName)
            TwoFactorRecords.event(ec, userId, "PASSKEY_FAILED")
            return [refusal: TwoFactorRefusal.PASSKEY]
        }
        AuthenticatorData authData = verified.attestationObject.authenticatorData
        AttestedCredentialData attested = authData.attestedCredentialData
        byte[] credentialIdBytes = attested.credentialId
        String credentialIdHash = AuthPrimitives.sha256Hex(credentialIdBytes)
        if (liveOwnerOf(ec, credentialIdHash) != null) {
            logger.info("Passkey registration refused for userId {}: credential already registered", userId)
            return [refusal: TwoFactorRefusal.PASSKEY_EXISTS]
        }

        Timestamp now = ec.user.nowTimestamp
        String label = cleanName(name)
        def factor = ec.entity.makeValue(TwoFactorRecords.FACTOR).setAll([userId: userId, factorTypeEnumId: TwoFactorRecords.TYPE_PASSKEY,
                statusId: TwoFactorRecords.ACTIVE, createdDate: now, confirmedDate: now]).setSequencedIdPrimary()
        factor.create()
        ec.entity.makeValue(CREDENTIAL).setAll([factorId: factor.factorId, userId: userId, credentialId: AuthPrimitives.b64url(credentialIdBytes),
                credentialIdHash: credentialIdHash, attestedCredentialData: AuthPrimitives.b64url(attestedConverter.convert(attested)),
                signCount: authData.signCount, transports: (verified.transports ?: [])*.value.join(","), name: label,
                backupEligible: authData.flagBE ? "Y" : "N", backedUp: authData.flagBS ? "Y" : "N",
                uvInitialized: authData.flagUV ? "Y" : "N"]).create()
        TwoFactorRecords.event(ec, userId, "PASSKEY_ADDED")
        TwoFactorRecords.notify(ec, userId, TEMPLATE_PASSKEY_ADDED, [passkeyName: label])
        return [factorId: factor.factorId, name: label]
    }

    private static def liveOwnerOf(def ec, String credentialIdHash) {
        List rows = TwoFactorRecords.find(ec, CREDENTIAL, "global: is this credential id already a live passkey of anyone")
                .condition("credentialIdHash", credentialIdHash).useCache(false).list()
        if (!rows) return null
        return TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "global: the factors behind a credential id")
                .condition("factorId", EntityCondition.IN, rows*.factorId)
                .condition("statusId", TwoFactorRecords.ACTIVE).useCache(false).list()?.find { true }
    }

    // ---- assertions ----

    /**
     * The assertion check (call inside a transaction; it locks the passkey's factor row). expectedChallenge null means
     * "the row the clientDataJSON names" (a proof): that row must be a live DrpnScProof row of this person and is used up.
     * With expectedChallenge (a sign-in) the caller has claimed the row. True when the assertion passes.
     */
    static boolean verifyAssertion(def ec, String userId, Object assertionJson, String expectedChallenge) {
        RelyingParty rp = relyingParty(ec)
        String json = normalize(assertionJson)
        if (rp == null || !userId || !json) return false
        AuthenticationData parsed
        try {
            parsed = manager.parseAuthenticationResponseJSON(json)
        } catch (Exception e) {
            logger.info("Passkey assertion refused for userId {}: unreadable ({})", userId, e.class.simpleName)
            return refuse(ec, userId)
        }
        String challenge = expectedChallenge
        if (challenge == null) {
            challenge = presentedChallenge(parsed.collectedClientData)
            if (SignInChallenges.claimCeremony(ec, challenge, SignInChallenges.PURPOSE_PROOF, userId) == null) return refuse(ec, userId)
        }
        byte[] credentialId = parsed.credentialId
        List<Map> passkeys = activePasskeys(ec, userId)
        Map match = credentialId ? passkeys.find { it.credential.credentialIdHash == AuthPrimitives.sha256Hex(credentialId) } : null
        byte[] handle = parsed.userHandle
        if (match == null || (handle != null && handle.length > 0 && AuthPrimitives.b64url(handle) != userHandle(userId))) {
            logger.info("Passkey assertion refused for userId {}: not one of their live passkeys", userId)
            return refuse(ec, userId)
        }
        def factor = TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "self-scoped auth: lock the passkey being used")
                .condition("factorId", match.factor.factorId).forUpdate(true).useCache(false).one()
        def credential = TwoFactorRecords.find(ec, CREDENTIAL, "self-scoped auth: the passkey being used")
                .condition("factorId", match.factor.factorId).forUpdate(true).useCache(false).one()
        if (factor?.statusId != TwoFactorRecords.ACTIVE || credential == null) return refuse(ec, userId)
        try {
            List<byte[]> allowed = passkeys.collect { AuthPrimitives.b64urlDecode((String) it.credential.credentialId) }
            manager.verify(parsed, new AuthenticationParameters(rp.serverProperty(challenge), credentialRecord(credential), allowed, true, true))
        } catch (MaliciousCounterValueException e) {
            logger.warn("Passkey {} of userId {} presented a signature counter that did not move forward (stored {}): refused; a cloned authenticator?",
                    factor.factorId, userId, credential.signCount)
            TwoFactorRecords.event(ec, userId, "PASSKEY_COUNTER_REGRESSED")
            return false
        } catch (Exception e) {
            logger.info("Passkey assertion refused for userId {}: {}", userId, e.class.simpleName)
            return refuse(ec, userId)
        }
        AuthenticatorData authData = parsed.authenticatorData
        credential.signCount = authData.signCount
        credential.backedUp = authData.flagBS ? "Y" : "N"
        credential.update()
        factor.lastUsedDate = ec.user.nowTimestamp
        factor.update()
        TwoFactorRecords.event(ec, userId, "PASSKEY_OK")
        return true
    }

    // ---- a passkey alone (DAR-BE-301) ----

    /**
     * begin#PasskeyFirstSignIn: a new PasskeySignInChallenge row (the token's hash, a fresh WebAuthn challenge, the username
     * as typed) and request options with userVerification required, so one gesture is the passkey and the person's
     * biometric or PIN. With no username allowCredentials is empty (the browser offers its own discoverable passkeys). With
     * one, it lists the live passkeys of the account the username names; for a username that names nobody, or a person with
     * no live passkey, it holds one decoy descriptor (decoyCredentialId: the same for the same username, unlike any real
     * id), so the answer never says whether the account exists or has a passkey. Returns [challengeToken,
     * publicKeyOptions, expiresInSeconds] or [refusal]: PASSKEYS_UNAVAILABLE, or TOO_MANY_ATTEMPTS once
     * FIRST_SIGN_IN_BEGINS_PER_MINUTE begins came from this address in the last minute (counted from the stored rows, as
     * CliAuthSupport.overStartLimit, so a restart does not reset it).
     *
     * <p>Every write is a short transaction of its own (the sweep of rows a day past their end, by the indexed
     * expiresDate; the new row), so no lock is held while the caller holds the answer to the timing floor.
     */
    static Map<String, Object> firstSignInOptions(def ec, Object username) {
        RelyingParty rp = relyingParty(ec)
        if (rp == null) return [refusal: TwoFactorRefusal.PASSKEYS_UNAVAILABLE]
        Timestamp now = ec.user.nowTimestamp
        String address = CliAuthSupport.requestAddress(ec)
        long recent = TwoFactorRecords.find(ec, FIRST_SIGN_IN_CHALLENGE, "rate limit: passkey-first sign-in begins per client address")
                .condition("requestIp", address)
                .condition("createdDate", EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, new Timestamp(now.time - 60_000L))
                .useCache(false).count()
        if (recent >= FIRST_SIGN_IN_BEGINS_PER_MINUTE) return [refusal: TwoFactorRefusal.TOO_MANY_ATTEMPTS]
        ec.transaction.runRequireNew(null, "Could not sweep passkey sign-in challenges", {
            TwoFactorRecords.find(ec, FIRST_SIGN_IN_CHALLENGE, "housekeeping: passkey-first sign-in challenges a day past their end")
                    .condition("expiresDate", EntityCondition.ComparisonOperator.LESS_THAN, new Timestamp(now.time - SignInChallenges.EXPIRED_KEEP_MILLIS))
                    .deleteAll()
        })

        String hint = usernameHint(username)
        List<Map> passkeys = hint ? activePasskeys(ec, (String) accountNamed(ec, hint)?.userId) : []
        if (hint && !passkeys) passkeys = [[credential: [credentialId: decoyCredentialId(ec, hint), transports: DECOY_TRANSPORTS]]]
        String token = AuthPrimitives.newToken()
        String challenge = AuthPrimitives.newToken()
        ec.transaction.runRequireNew(null, "Could not start a passkey sign-in", {
            ec.entity.makeValue(FIRST_SIGN_IN_CHALLENGE).setAll([tokenHash: AuthPrimitives.sha256Hex(token), webauthnChallenge: challenge,
                    usernameHint: hint, createdDate: now, expiresDate: new Timestamp(now.time + SignInChallenges.LIFETIME_SECONDS * 1000L),
                    requestIp: address]).setSequencedIdPrimary().create()
        })
        return [challengeToken: token, publicKeyOptions: requestOptionsJson(rp, challenge, passkeys),
                expiresInSeconds: SignInChallenges.LIFETIME_SECONDS]
    }

    /**
     * The decoy credential id begin#PasskeyFirstSignIn lists for a username with no live passkey: HMAC-SHA256 of the
     * username (trimmed, lower case, as the case-insensitive lookup treats it) under decoyKey, base64url: 32 bytes, as
     * many authenticators' ids are. Stable for a username (two begins, two nodes, a restart look alike), different per
     * username, and not a credential anyone holds, so a finish with it is refused like any unknown passkey.
     */
    static String decoyCredentialId(def ec, String username) {
        Mac mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(decoyKey(ec), "HmacSHA256"))
        return AuthPrimitives.b64url(mac.doFinal(username.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)))
    }

    /**
     * The decoy key: one random 32-byte secret, made once and stored in AuthSecret (DECOY_SECRET_ID), so every node and
     * every restart uses the same one, and nothing public (not the crypt-pass, which an instance may leave at a default)
     * can compute it. Read once per framework and kept in memory. Created race-safely: the PK is fixed, so a second node
     * creating it at the same moment fails its insert and reads the first one's row.
     */
    static byte[] decoyKey(def ec) {
        Object factory = ec.factory
        Map cached = decoyKeyCache
        if (cached != null && cached.factory.is(factory)) return (byte[]) cached.key
        synchronized (PasskeySupport) {
            cached = decoyKeyCache
            if (cached == null || !cached.factory.is(factory)) {
                cached = [factory: factory, key: loadOrCreateDecoyKey(ec)]
                decoyKeyCache = cached
            }
            return (byte[]) cached.key
        }
    }

    /** Tests: forget the in-memory decoy key, so the next begin reads it from the database again. */
    static void forgetDecoyKey() {
        decoyKeyCache = null
    }

    private static byte[] loadOrCreateDecoyKey(def ec) {
        String value = storedDecoySecret(ec)
        if (!value) {
            String fresh = AuthPrimitives.newToken()
            try {
                ec.transaction.runRequireNew(null, "Could not store the passkey decoy key", {
                    ec.entity.makeValue(AUTH_SECRET).setAll([secretId: DECOY_SECRET_ID, secretValue: fresh,
                            createdDate: ec.user.nowTimestamp]).create()
                })
            } catch (Exception e) {
                logger.info("Passkey decoy key: another node stored it first ({}); reading theirs", e.class.simpleName)
            }
            value = storedDecoySecret(ec)
        }
        byte[] key = value ? AuthPrimitives.b64urlDecode(value) : null
        if (key == null || key.length < 32) throw new IllegalStateException("The passkey decoy key could not be read")
        return key
    }

    /** Read in a transaction of its own, so a row another node committed after this request's transaction began is seen. */
    private static String storedDecoySecret(def ec) {
        return (String) ec.transaction.runRequireNew(null, "Could not read the passkey decoy key", {
            TwoFactorRecords.find(ec, AUTH_SECRET, "global: the instance's passkey decoy key")
                    .condition("secretId", DECOY_SECRET_ID).useCache(false).one()?.secretValue
        })
    }

    /**
     * finish#PasskeyFirstSignIn's check: the person the assertion signs in, or null. The token's row is claimed first, in a
     * transaction of its own: locked, live (unused, unexpired) and used up there, so every attempt spends the token even
     * when what follows refuses or rolls back. Then, in another: the assertion's credential must be a live passkey (its
     * owner is who it is checked for), of the account the row's usernameHint names when there is one, and verifyAssertion
     * must pass against the row's challenge (user verified, counter, PASSKEY_OK / PASSKEY_FAILED events).
     */
    static String claimFirstSignIn(def ec, Object token, Object assertionJson) {
        String value = normalize(token)
        if (!value || relyingParty(ec) == null) return null
        Map claimed = (Map) ec.transaction.runRequireNew(null, "Could not claim a passkey sign-in", {
            def row = TwoFactorRecords.find(ec, FIRST_SIGN_IN_CHALLENGE, "pre-auth: passkey-first sign-in challenge by token hash, no session yet")
                    .condition("tokenHash", AuthPrimitives.sha256Hex(value)).forUpdate(true).useCache(false).one()
            if (row == null || row.usedDate != null || !((Timestamp) row.expiresDate)?.after(ec.user.nowTimestamp)) return null
            row.usedDate = ec.user.nowTimestamp
            row.update()
            return [challenge: row.webauthnChallenge, usernameHint: row.usernameHint]
        })
        if (claimed == null || !claimed.challenge) return null
        return (String) ec.transaction.runRequireNew(null, "Could not check a passkey sign-in", {
            String ownerId = firstSignInOwner(ec, assertionJson)
            if (ownerId == null) return null
            if (claimed.usernameHint && accountNamed(ec, (String) claimed.usernameHint)?.userId != ownerId) {
                logger.info("Passkey-first sign-in refused for userId {}: not the account the username named", ownerId)
                refuse(ec, ownerId)
                return null
            }
            return verifyAssertion(ec, ownerId, assertionJson, (String) claimed.challenge) ? ownerId : null
        })
    }

    /** Who an assertion claims to be: the owner of the live passkey its credential id names (one live owner per credential
     *  id), or null. Not yet verified: verifyAssertion checks the signature against that person's passkeys. */
    static String firstSignInOwner(def ec, Object assertionJson) {
        String json = normalize(assertionJson)
        if (!json) return null
        byte[] credentialId
        try {
            credentialId = manager.parseAuthenticationResponseJSON(json).credentialId
        } catch (Exception e) {
            logger.info("Passkey-first sign-in refused: unreadable assertion ({})", e.class.simpleName)
            return null
        }
        return credentialId ? (String) liveOwnerOf(ec, AuthPrimitives.sha256Hex(credentialId))?.userId : null
    }

    /**
     * The account a typed username names, found as the framework's password sign-in finds it
     * (MoquiShiroRealm.loginPrePassword): the username, else the username ignoring case, else the email address ignoring
     * case. Null when nothing matches, or more than one account does.
     */
    static def accountNamed(def ec, String username) {
        if (!username) return null
        def exact = TwoFactorRecords.find(ec, ACCOUNT, "pre-auth: the account a typed username names")
                .condition("username", username).useCache(false).one()
        if (exact != null) return exact
        for (String field in ["username", "emailAddress"]) {
            List found = TwoFactorRecords.find(ec, ACCOUNT, "pre-auth: the account a typed username names, ignoring case")
                    .condition(ec.entity.conditionFactory.makeCondition(field, EntityCondition.ComparisonOperator.EQUALS, username).ignoreCase())
                    .useCache(false).limit(2).list()
            if (found) return found.size() == 1 ? found[0] : null
        }
        return null
    }

    /** The username as typed, trimmed, at most USERNAME_HINT_MAX_LENGTH characters (the stored column; a username or
     *  email is never longer), or null. */
    static String usernameHint(Object username) {
        String value = normalize(username)
        return value && value.length() > USERNAME_HINT_MAX_LENGTH ? value.substring(0, USERNAME_HINT_MAX_LENGTH) : value
    }

    /** A refused assertion: the PASSKEY_FAILED event, and false. */
    private static boolean refuse(def ec, String userId) {
        TwoFactorRecords.event(ec, userId, "PASSKEY_FAILED")
        return false
    }

    private static String presentedChallenge(def collectedClientData) {
        byte[] value = collectedClientData?.challenge?.value
        return value ? AuthPrimitives.b64url(value) : null
    }

    private static CredentialRecordImpl credentialRecord(def credential) {
        AttestedCredentialData attested = attestedConverter.convert(AuthPrimitives.b64urlDecode((String) credential.attestedCredentialData))
        Set<AuthenticatorTransport> transports = splitTransports(credential.transports).collect { AuthenticatorTransport.create(it) } as Set
        return new CredentialRecordImpl(null, credential.uvInitialized == "Y", credential.backupEligible == "Y",
                credential.backedUp == "Y", (credential.signCount ?: 0L) as long, attested, null, null, null, transports)
    }

    // ---- managing ----

    /** rename#Passkey: the person's own live passkey only. The refusal, or null. */
    static TwoFactorRefusal rename(def ec, Object passkeyId, Object name) {
        String userId = normalize(ec.user.userId)
        def credential = ownLive(ec, userId, passkeyId)
        if (credential == null) return TwoFactorRefusal.PASSKEY_NOT_FOUND
        if (!normalize(name)) return TwoFactorRefusal.NAME_REQUIRED
        credential.name = cleanName(name)
        credential.update()
        TwoFactorRecords.event(ec, userId, "PASSKEY_RENAMED")
        return null
    }

    /** The person's own credential row whose factor is ACTIVE, or null. */
    static def ownLive(def ec, String userId, Object passkeyId) {
        String id = normalize(passkeyId)
        if (!userId || !id) return null
        // Read by the primary key, then checked here: Moqui's one() ignores conditions beside a full primary key.
        def factor = TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "self-scoped auth: the person's passkey by id")
                .condition("factorId", id).useCache(false).one()
        if (factor == null || factor.userId != userId || factor.factorTypeEnumId != TwoFactorRecords.TYPE_PASSKEY
                || factor.statusId != TwoFactorRecords.ACTIVE) return null
        return TwoFactorRecords.find(ec, CREDENTIAL, "self-scoped auth: the person's passkey credential").condition("factorId", id)
                .useCache(false).one()
    }

    // ---- small helpers ----

    /** The opaque WebAuthn user handle: the userId's bytes, base64url (no PII; stable, so a re-registration on the same
     *  authenticator replaces its resident key). */
    static String userHandle(String userId) {
        return AuthPrimitives.b64url(userId.getBytes(StandardCharsets.UTF_8))
    }

    static String cleanName(Object name) {
        String value = normalize(name)?.replaceAll(/\p{Cntrl}/, "")?.trim()
        if (!value) return DEFAULT_NAME
        return value.length() > NAME_MAX_LENGTH ? value.substring(0, NAME_MAX_LENGTH) : value
    }

    private static List<String> splitTransports(Object value) {
        return value ? value.toString().split(",").collect { it.trim() }.findAll { it } : []
    }
}
