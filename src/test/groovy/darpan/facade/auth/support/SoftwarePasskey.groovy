package darpan.facade.auth.support

import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.AuthenticatorDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * DAR-BE-270 tests — a software authenticator: a P-256 key pair from the JDK and webauthn4j-core's own CBOR converters,
 * answering the server's options JSON the way navigator.credentials.create/get + toJSON() would (attestation "none",
 * user present and verified). Each assertion moves the signature counter on by one unless told otherwise. Test scope only.
 */
class SoftwarePasskey {
    private static final ObjectConverter converter = new ObjectConverter()
    private static final SecureRandom random = new SecureRandom()

    final KeyPair keyPair
    final byte[] credentialId
    long counter = 0
    String userHandle

    SoftwarePasskey() {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(new ECGenParameterSpec("secp256r1"))
        keyPair = generator.generateKeyPair()
        credentialId = new byte[32]
        random.nextBytes(credentialId)
    }

    String getId() { return b64(credentialId) }

    /** The credential JSON for begin#PasskeyRegistration's publicKeyOptions. overrides: origin, rpId, userVerified. */
    String register(String optionsJson, Map overrides = [:]) {
        Map options = (Map) new JsonSlurper().parseText(optionsJson)
        if (!userHandle) userHandle = options.user?.id // the first registration's person (a resident key is theirs)
        String rpId = overrides.rpId ?: options.rp.id
        byte[] clientData = clientData("webauthn.create", (String) options.challenge, (String) (overrides.origin ?: "http://localhost:5173"))
        byte flags = (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_AT |
                (overrides.userVerified == false ? 0 : AuthenticatorData.BIT_UV))
        AttestedCredentialData attested = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create((ECPublicKey) keyPair.public, COSEAlgorithmIdentifier.ES256))
        AuthenticatorData authData = new AuthenticatorData(sha256(rpId.getBytes(StandardCharsets.UTF_8)), flags, counter, attested)
        byte[] attestationObject = new AttestationObjectConverter(converter)
                .convertToBytes(new AttestationObject(authData, new NoneAttestationStatement()))
        return JsonOutput.toJson([id: id, rawId: id, type: "public-key", authenticatorAttachment: "platform",
                response: [clientDataJSON: b64(clientData), attestationObject: b64(attestationObject), transports: ["internal", "hybrid"],
                           publicKeyAlgorithm: -7], clientExtensionResults: [:]])
    }

    /** The assertion JSON for request options (begin#PasskeySignIn / begin#PasskeyProof). overrides: origin, rpId,
     *  counter (sent as is), challenge. */
    String assertion(String optionsJson, Map overrides = [:]) {
        Map options = (Map) new JsonSlurper().parseText(optionsJson)
        String rpId = overrides.rpId ?: options.rpId
        if (overrides.containsKey("counter")) counter = overrides.counter as long
        else counter++
        byte[] clientData = clientData("webauthn.get", (String) (overrides.challenge ?: options.challenge),
                (String) (overrides.origin ?: "http://localhost:5173"))
        byte flags = (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV)
        byte[] authData = new AuthenticatorDataConverter(converter)
                .convert(new AuthenticatorData(sha256(rpId.getBytes(StandardCharsets.UTF_8)), flags, counter, (AttestedCredentialData) null))
        Signature signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(authData)
        signer.update(sha256(clientData))
        Map response = [clientDataJSON: b64(clientData), authenticatorData: b64(authData), signature: b64(signer.sign())]
        if (userHandle) response.userHandle = userHandle
        return JsonOutput.toJson([id: id, rawId: id, type: "public-key", authenticatorAttachment: "platform",
                response: response, clientExtensionResults: [:]])
    }

    private static byte[] clientData(String type, String challenge, String origin) {
        return JsonOutput.toJson([type: type, challenge: challenge, origin: origin, crossOrigin: false]).getBytes(StandardCharsets.UTF_8)
    }

    private static byte[] sha256(byte[] value) {
        return MessageDigest.getInstance("SHA-256").digest(value)
    }

    static String b64(byte[] bytes) {
        return Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }
}
