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
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.MaliciousCounterValueException
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonOutput
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-270 — passkeys and security keys (WebAuthn) as a second factor, beside DAR-BE-264's authenticator app. Spec:
 * wrapper docs/superpowers/specs/2026-11-13-dar-be-270-passkeys-design.md. The only class that touches webauthn4j.
 *
 * <p>A passkey is a TwoFactorFactor row (DrpnTfPkey) plus its PasskeyCredential. Every ceremony starts with a
 * SignInChallenge row holding a fresh webauthnChallenge: begin#PasskeySignIn sets it on the sign-in row, begin#PasskeyProof
 * and begin#PasskeyRegistration make a row whose tokenHash is the challenge's own hash, so the clientDataJSON the browser
 * signs names its row. Any attempt against a row uses its challenge up.
 *
 * <p>Checked on every assertion (webauthn4j): origin in the allowed set, rpId hash, the stored challenge, the signature
 * with the stored COSE key, user present and user verified, the credential among the person's live passkeys, and the
 * signature counter. Never logged: assertions, attestation objects, challenges.
 */
class PasskeySupport {
    private static final Logger logger = LoggerFactory.getLogger(PasskeySupport.class)

    static final String CREDENTIAL = "darpan.auth.PasskeyCredential"
    static final String TYPE_PASSKEY = "DrpnTfPkey"
    static final String PURPOSE_PROOF = "DrpnScProof"
    static final String PURPOSE_ADD = "DrpnScPkAdd"

    static final String METHOD_APP = "app"
    static final String METHOD_PASSKEY = "passkey"

    static final int MAX_PASSKEYS = 10
    static final int NAME_MAX_LENGTH = 60
    static final int CEREMONY_TIMEOUT_MILLIS = 120_000
    static final String DEFAULT_NAME = "Passkey"

    static final String UNAVAILABLE = "Passkeys are not available on this Darpan instance."
    static final String PASSKEY_REFUSAL = "That passkey didn't work."
    static final String TOO_MANY = "You have ${MAX_PASSKEYS} passkeys already. Remove one first.".toString()
    static final String NOT_FOUND = "That passkey was not found."

    static final String TEMPLATE_PASSKEY_ADDED = "DARPAN_2FA_PASSKEY_ADDED"

    /** ES256 first (every platform authenticator), then EdDSA and RS256 (Windows Hello, some security keys). */
    static final List<COSEAlgorithmIdentifier> ALGORITHMS = [COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA,
            COSEAlgorithmIdentifier.RS256].asImmutable()

    private static final SecureRandom random = new SecureRandom()
    private static final ObjectConverter objectConverter = new ObjectConverter()
    /** attestation "none" is asked for; the non-strict manager also accepts a format an authenticator sends anyway, without
     *  trusting it (no FIDO metadata: spec open question 4). */
    private static final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)

    // ---- relying party (per instance) ----

    /**
     * The relying party id: DARPAN_WEBAUTHN_RP_ID / darpan.webauthn.rpId, else the host of the app origin
     * (DARPAN_APP_BASE_URL, else the first non-* webapp_allow_origins entry). The UI's host, never the API's: the browser
     * binds a passkey to the page that made it. Null = passkeys unavailable here.
     */
    static String rpId(def ec) {
        String configured = normalize(System.getenv("DARPAN_WEBAUTHN_RP_ID")) ?: normalize(System.getProperty("darpan.webauthn.rpId"))
        if (configured) return configured.toLowerCase()
        return hostOf(TenantNotificationSupport.resolveAppBaseUrl(ec))
    }

    /** The origins a ceremony may come from: DARPAN_WEBAUTHN_ORIGINS / darpan.webauthn.origins (comma list), else the
     *  webapp_allow_origins entries plus the app origin. Either way only https origins on the rpId or under it (http only
     *  for localhost). */
    static Set<String> origins(def ec) {
        String rp = rpId(ec)
        if (!rp) return [] as Set
        String configured = normalize(System.getenv("DARPAN_WEBAUTHN_ORIGINS")) ?: normalize(System.getProperty("darpan.webauthn.origins"))
        List<String> candidates
        if (configured) {
            candidates = configured.split(",").collect { it.trim() }
        } else {
            // default-property values live in System properties (see TenantNotificationSupport.resolveFirstAllowedOrigin)
            String allowed = normalize((ec?.resource?.properties?.get("webapp_allow_origins") ?: System.getProperty("webapp_allow_origins"))?.toString())
            candidates = (allowed ? allowed.split(",").collect { it.trim() } : []) + [TenantNotificationSupport.resolveAppBaseUrl(ec)]
        }
        return originsFor(rp, candidates)
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

    static boolean available(def ec) {
        return rpId(ec) && !origins(ec).isEmpty()
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

    private static def find(def ec, String entityName, String reason) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, reason)
    }

    /** The person's live passkeys: [factor, credential] pairs, oldest first. */
    static List<Map> activePasskeys(def ec, String userId) {
        if (!userId) return []
        List factors = find(ec, TwoFactorSupport.FACTOR, "self-scoped auth: the person's live passkeys").condition("userId", userId)
                .condition("factorTypeEnumId", TYPE_PASSKEY).condition("statusId", TwoFactorSupport.ACTIVE)
                .orderBy("confirmedDate").useCache(false).list()
        if (!factors) return []
        Map<String, Object> credentials = find(ec, CREDENTIAL, "self-scoped auth: the person's passkey credentials")
                .condition("factorId", EntityCondition.IN, factors*.factorId).useCache(false).list()
                .collectEntries { [(it.factorId): it] }
        return factors.findAll { credentials[it.factorId] != null }.collect { [factor: it, credential: credentials[it.factorId]] }
    }

    static int activePasskeyCount(def ec, String userId) {
        if (!userId) return 0
        return (int) find(ec, TwoFactorSupport.FACTOR, "self-scoped auth: count the person's live passkeys").condition("userId", userId)
                .condition("factorTypeEnumId", TYPE_PASSKEY).condition("statusId", TwoFactorSupport.ACTIVE).useCache(false).count()
    }

    /** For get#TwoFactorStatus: never the key or the credential id. */
    static List<Map> describe(def ec, String userId) {
        return activePasskeys(ec, userId).collect { Map p ->
            [passkeyId   : p.factor.factorId, name: p.credential.name, createdDate: p.factor.confirmedDate,
             lastUsedDate: p.factor.lastUsedDate, transports: splitTransports(p.credential.transports),
             backedUp    : p.credential.backedUp == "Y"]
        }
    }

    /** What the person can answer a sign-in challenge with here: "app" and/or "passkey". */
    static List<String> methods(def ec, String userId) {
        List<String> out = []
        if (TwoFactorSupport.activeFactor(ec, userId) != null) out << METHOD_APP
        if (available(ec) && activePasskeyCount(ec, userId) > 0) out << METHOD_PASSKEY
        return out
    }

    // ---- ceremony options ----

    /** begin#PasskeySignIn: a fresh challenge on a live sign-in row and request options for the person's passkeys. */
    static Map<String, Object> signInOptions(def ec, Object challengeToken) {
        if (!available(ec)) return [error: UNAVAILABLE]
        return (Map<String, Object>) ec.transaction.runRequireNew(null, "Could not start a passkey sign-in", {
            def row = TwoFactorSupport.liveChallenge(ec, challengeToken, true)
            if (row == null || row.purposeEnumId != TwoFactorSupport.PURPOSE_CODE) return [challengeEnded: true]
            List<Map> passkeys = activePasskeys(ec, (String) row.userId)
            if (!passkeys) return [error: PASSKEY_REFUSAL]
            String challenge = newChallenge()
            row.webauthnChallenge = challenge
            row.update()
            return [publicKeyOptions: requestOptionsJson(ec, challenge, passkeys)]
        })
    }

    /** begin#PasskeyProof: request options for the signed-in person, for one fresh-proof action. */
    static Map<String, Object> proofOptions(def ec) {
        String userId = normalize(ec.user.userId)
        if (!userId) return [error: "Sign in first."]
        if (!available(ec)) return [error: UNAVAILABLE]
        List<Map> passkeys = activePasskeys(ec, userId)
        if (!passkeys) return [error: "You have no passkey on this account."]
        String challenge = newChallenge()
        createChallengeRow(ec, userId, PURPOSE_PROOF, challenge)
        return [publicKeyOptions: requestOptionsJson(ec, challenge, passkeys), expiresInSeconds: TwoFactorSupport.CHALLENGE_LIFETIME_SECONDS]
    }

    /** begin#PasskeyRegistration (the caller has checked the fresh proof): creation options for the person. */
    static Map<String, Object> registrationOptions(def ec, String userId) {
        if (!available(ec)) return [error: UNAVAILABLE]
        List<Map> passkeys = activePasskeys(ec, userId)
        if (passkeys.size() >= MAX_PASSKEYS) return [error: TOO_MANY]
        def account = find(ec, "moqui.security.UserAccount", "self-scoped auth: the account adding a passkey")
                .condition("userId", userId).useCache(false).one()
        String challenge = newChallenge()
        createChallengeRow(ec, userId, PURPOSE_ADD, challenge)
        String username = (String) account?.username ?: userId
        Map options = [
                rp                    : [id: rpId(ec), name: TwoFactorSupport.issuer()],
                user                  : [id: userHandle(userId), name: username, displayName: normalize(account?.userFullName) ?: username],
                challenge             : challenge,
                pubKeyCredParams      : ALGORITHMS.collect { [type: "public-key", alg: it.value] },
                timeout               : CEREMONY_TIMEOUT_MILLIS,
                excludeCredentials    : passkeys.collect { descriptor(it.credential) },
                authenticatorSelection: [residentKey: "preferred", requireResidentKey: false, userVerification: "required"],
                attestation           : "none",
        ]
        return [publicKeyOptions: JsonOutput.toJson(options), expiresInSeconds: TwoFactorSupport.CHALLENGE_LIFETIME_SECONDS]
    }

    private static String requestOptionsJson(def ec, String challenge, List<Map> passkeys) {
        return JsonOutput.toJson([challenge       : challenge, rpId: rpId(ec), timeout: CEREMONY_TIMEOUT_MILLIS,
                                  allowCredentials: passkeys.collect { descriptor(it.credential) }, userVerification: "required"])
    }

    private static Map descriptor(def credential) {
        Map d = [type: "public-key", id: credential.credentialId]
        List<String> transports = splitTransports(credential.transports)
        if (transports) d.transports = transports
        return d
    }

    private static void createChallengeRow(def ec, String userId, String purpose, String challenge) {
        TwoFactorSupport.sweepExpiredChallenges(ec, userId)
        Timestamp now = ec.user.nowTimestamp
        ec.entity.makeValue(TwoFactorSupport.CHALLENGE).setAll([userId: userId, tokenHash: TwoFactorSupport.sha256(challenge),
                purposeEnumId: purpose, webauthnChallenge: challenge, createdDate: now,
                expiresDate: new Timestamp(now.time + TwoFactorSupport.CHALLENGE_LIFETIME_SECONDS * 1000L), failedAttempts: 0,
                requestIp: TwoFactorSupport.recordedIp(ec)]).setSequencedIdPrimary().create()
    }

    // ---- registration ----

    /**
     * finish#PasskeyRegistration's check and store (call inside a transaction): the registration row named by the
     * credential's clientDataJSON must be live, of this person, and is used up whatever happens; then webauthn4j checks
     * the attestation (origin, rpId hash, challenge, user present + verified, algorithm) and no live passkey anywhere may
     * have the credential id. Returns [ok, factorId, name] or [ok: false, error].
     */
    static Map<String, Object> register(def ec, String userId, Object credentialJson, Object name) {
        if (!available(ec)) return [ok: false, error: UNAVAILABLE]
        String json = normalize(credentialJson)
        if (!json) return [ok: false, error: PASSKEY_REFUSAL]
        RegistrationData parsed
        try {
            parsed = manager.parseRegistrationResponseJSON(json)
        } catch (Exception e) {
            logger.info("Passkey registration refused for userId {}: unreadable credential ({})", userId, e.class.simpleName)
            return [ok: false, error: PASSKEY_REFUSAL]
        }
        String challenge = parsed.collectedClientData?.challenge?.value ? b64(parsed.collectedClientData.challenge.value) : null
        def row = challenge ? TwoFactorSupport.liveChallenge(ec, challenge, true) : null
        if (row == null || row.purposeEnumId != PURPOSE_ADD || row.userId != userId || row.webauthnChallenge != challenge) {
            return [ok: false, error: "This passkey setup has expired. Start again."]
        }
        row.usedDate = ec.user.nowTimestamp
        row.update()
        if (activePasskeys(ec, userId).size() >= MAX_PASSKEYS) return [ok: false, error: TOO_MANY]

        RegistrationData verified
        try {
            List<PublicKeyCredentialParameters> params = ALGORITHMS.collect { new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) }
            verified = manager.verify(parsed, new RegistrationParameters(serverProperty(ec, challenge), params, true, true))
        } catch (Exception e) {
            logger.info("Passkey registration refused for userId {}: {}", userId, e.class.simpleName)
            TwoFactorSupport.event(ec, userId, "PASSKEY_FAILED")
            return [ok: false, error: PASSKEY_REFUSAL]
        }
        AuthenticatorData authData = verified.attestationObject.authenticatorData
        AttestedCredentialData attested = authData.attestedCredentialData
        byte[] credentialIdBytes = attested.credentialId
        String credentialIdHash = TwoFactorSupport.sha256Bytes(credentialIdBytes)
        if (liveOwnerOf(ec, credentialIdHash) != null) {
            logger.info("Passkey registration refused for userId {}: credential already registered", userId)
            return [ok: false, error: "That passkey is already registered."]
        }

        Timestamp now = ec.user.nowTimestamp
        String label = cleanName(name)
        def factor = ec.entity.makeValue(TwoFactorSupport.FACTOR).setAll([userId: userId, factorTypeEnumId: TYPE_PASSKEY,
                statusId: TwoFactorSupport.ACTIVE, createdDate: now, confirmedDate: now, failedCodeCount: 0])
                .setSequencedIdPrimary()
        factor.create()
        byte[] coseBytes = objectConverter.cborConverter.writeValueAsBytes(attested.getCOSEKey())
        ec.entity.makeValue(CREDENTIAL).setAll([factorId: factor.factorId, userId: userId, credentialId: b64(credentialIdBytes),
                credentialIdHash: credentialIdHash, publicKeyCose: b64(coseBytes), signCount: authData.signCount,
                transports: (verified.transports ?: [])*.value.join(","), aaguid: attested.aaguid?.value?.toString(), name: label,
                backupEligible: authData.flagBE ? "Y" : "N", backedUp: authData.flagBS ? "Y" : "N",
                uvInitialized: authData.flagUV ? "Y" : "N", createdDate: now]).create()
        TwoFactorSupport.event(ec, userId, "PASSKEY_ADDED")
        TwoFactorSupport.notify(ec, userId, TEMPLATE_PASSKEY_ADDED, [passkeyName: label])
        return [ok: true, factorId: factor.factorId, name: label]
    }

    private static def liveOwnerOf(def ec, String credentialIdHash) {
        List rows = find(ec, CREDENTIAL, "global: is this credential id already a live passkey of anyone")
                .condition("credentialIdHash", credentialIdHash).useCache(false).list()
        if (!rows) return null
        return find(ec, TwoFactorSupport.FACTOR, "global: the factors behind a credential id").condition("factorId", EntityCondition.IN, rows*.factorId)
                .condition("statusId", TwoFactorSupport.ACTIVE).useCache(false).list()?.find { true }
    }

    // ---- assertions ----

    /**
     * The assertion check (call inside a transaction; it locks the passkey's factor row). expectedChallenge null means
     * "the row the clientDataJSON names" (a proof): that row must be a live DrpnScProof row of this person and is used up.
     * With expectedChallenge (a sign-in) the caller has claimed the row. Returns [ok, factorId] or [ok: false, reason]
     * where reason is COUNTER for a counter that did not move forward.
     */
    static Map<String, Object> verifyAssertion(def ec, String userId, Object assertionJson, String expectedChallenge) {
        if (!available(ec) || !userId) return [ok: false]
        String json = normalize(assertionJson)
        if (!json) return [ok: false]
        AuthenticationData parsed
        try {
            parsed = manager.parseAuthenticationResponseJSON(json)
        } catch (Exception e) {
            logger.info("Passkey assertion refused for userId {}: unreadable ({})", userId, e.class.simpleName)
            TwoFactorSupport.event(ec, userId, "PASSKEY_FAILED")
            return [ok: false]
        }
        String presented = parsed.collectedClientData?.challenge?.value ? b64(parsed.collectedClientData.challenge.value) : null
        String challenge = expectedChallenge
        if (challenge == null) {
            def row = presented ? TwoFactorSupport.liveChallenge(ec, presented, true) : null
            if (row == null || row.purposeEnumId != PURPOSE_PROOF || row.userId != userId || row.webauthnChallenge != presented) {
                TwoFactorSupport.event(ec, userId, "PASSKEY_FAILED")
                return [ok: false]
            }
            row.usedDate = ec.user.nowTimestamp
            row.update()
            challenge = presented
        }
        byte[] credentialId = parsed.credentialId
        List<Map> passkeys = activePasskeys(ec, userId)
        Map match = credentialId ? passkeys.find { it.credential.credentialIdHash == TwoFactorSupport.sha256Bytes(credentialId) } : null
        byte[] handle = parsed.userHandle
        if (match == null || (handle != null && handle.length > 0 && b64(handle) != userHandle(userId))) {
            logger.info("Passkey assertion refused for userId {}: not one of their live passkeys", userId)
            TwoFactorSupport.event(ec, userId, "PASSKEY_FAILED")
            return [ok: false]
        }
        def factor = find(ec, TwoFactorSupport.FACTOR, "self-scoped auth: lock the passkey being used")
                .condition("factorId", match.factor.factorId).forUpdate(true).useCache(false).one()
        def credential = find(ec, CREDENTIAL, "self-scoped auth: the passkey being used")
                .condition("factorId", match.factor.factorId).forUpdate(true).useCache(false).one()
        if (factor?.statusId != TwoFactorSupport.ACTIVE || credential == null) {
            TwoFactorSupport.event(ec, userId, "PASSKEY_FAILED")
            return [ok: false]
        }
        try {
            CredentialRecordImpl record = credentialRecord(credential)
            List<byte[]> allowed = passkeys.collect { b64Decode((String) it.credential.credentialId) }
            manager.verify(parsed, new AuthenticationParameters(serverProperty(ec, challenge), record, allowed, true, true))
        } catch (MaliciousCounterValueException e) {
            logger.warn("Passkey {} of userId {} presented a signature counter that did not move forward (stored {}): refused; a cloned authenticator?",
                    factor.factorId, userId, credential.signCount)
            TwoFactorSupport.event(ec, userId, "PASSKEY_COUNTER_REGRESSED")
            return [ok: false, reason: "COUNTER"]
        } catch (Exception e) {
            logger.info("Passkey assertion refused for userId {}: {}", userId, e.class.simpleName)
            TwoFactorSupport.event(ec, userId, "PASSKEY_FAILED")
            return [ok: false]
        }
        AuthenticatorData authData = parsed.authenticatorData
        credential.signCount = authData.signCount
        credential.backedUp = authData.flagBS ? "Y" : "N"
        credential.update()
        factor.lastUsedDate = ec.user.nowTimestamp
        factor.update()
        TwoFactorSupport.event(ec, userId, "PASSKEY_OK")
        return [ok: true, factorId: factor.factorId]
    }

    private static CredentialRecordImpl credentialRecord(def credential) {
        byte[] credentialId = b64Decode((String) credential.credentialId)
        byte[] cose = b64Decode((String) credential.publicKeyCose)
        String aaguidText = normalize(credential.aaguid)
        byte[] aaguid = aaguidText ? new AAGUID(aaguidText).bytes : new byte[16]
        // aaguid (16) | credential id length (2) | credential id | COSE key: the attested-credential-data layout.
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        out.write(aaguid)
        out.write([(credentialId.length >> 8) & 0xff, credentialId.length & 0xff] as byte[])
        out.write(credentialId)
        out.write(cose)
        AttestedCredentialData attested = new AttestedCredentialDataConverter(objectConverter).convert(out.toByteArray())
        Set<AuthenticatorTransport> transports = splitTransports(credential.transports).collect { AuthenticatorTransport.create(it) } as Set
        return new CredentialRecordImpl(null, credential.uvInitialized == "Y", credential.backupEligible == "Y",
                credential.backedUp == "Y", (credential.signCount ?: 0L) as long, attested, null, null, null, transports)
    }

    private static ServerProperty serverProperty(def ec, String challenge) {
        return ServerProperty.builder().origins(origins(ec).collect { new Origin(it) } as Set<Origin>).rpId(rpId(ec))
                .challenge(new DefaultChallenge(challenge)).build()
    }

    // ---- managing ----

    /** rename#Passkey: the person's own live passkey only. */
    static String rename(def ec, Object passkeyId, Object name) {
        String userId = normalize(ec.user.userId)
        def credential = ownLive(ec, userId, passkeyId)
        if (credential == null) return NOT_FOUND
        if (!normalize(name)) return "A name is required."
        credential.name = cleanName(name)
        credential.update()
        TwoFactorSupport.event(ec, userId, "PASSKEY_RENAMED")
        return null
    }

    /** The person's own credential row whose factor is ACTIVE, or null. */
    static def ownLive(def ec, String userId, Object passkeyId) {
        String id = normalize(passkeyId)
        if (!userId || !id) return null
        // Read by the primary key, then checked here: Moqui's one() ignores conditions beside a full primary key.
        def factor = find(ec, TwoFactorSupport.FACTOR, "self-scoped auth: the person's passkey by id").condition("factorId", id)
                .useCache(false).one()
        if (factor == null || factor.userId != userId || factor.factorTypeEnumId != TYPE_PASSKEY
                || factor.statusId != TwoFactorSupport.ACTIVE) return null
        return find(ec, CREDENTIAL, "self-scoped auth: the person's passkey credential").condition("factorId", id).useCache(false).one()
    }

    // ---- small helpers ----

    /** The opaque WebAuthn user handle: the userId's bytes, base64url (no PII; stable, so a re-registration on the same
     *  authenticator replaces its resident key). */
    static String userHandle(String userId) {
        return b64(userId.getBytes(StandardCharsets.UTF_8))
    }

    static String cleanName(Object name) {
        String value = normalize(name)?.replaceAll(/\p{Cntrl}/, "")?.trim()
        if (!value) return DEFAULT_NAME
        return value.length() > NAME_MAX_LENGTH ? value.substring(0, NAME_MAX_LENGTH) : value
    }

    private static List<String> splitTransports(Object value) {
        return value ? value.toString().split(",").collect { it.trim() }.findAll { it } : []
    }

    private static String newChallenge() {
        byte[] bytes = new byte[32]
        random.nextBytes(bytes)
        return b64(bytes)
    }

    static String b64(byte[] bytes) {
        return Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }

    static byte[] b64Decode(String value) {
        return Base64.urlDecoder.decode(value)
    }
}
