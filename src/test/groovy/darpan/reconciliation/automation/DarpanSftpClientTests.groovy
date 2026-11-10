package darpan.reconciliation.automation

import net.schmizz.sshj.common.Buffer
import org.junit.jupiter.api.Test

import java.security.PublicKey

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-082: an SFTP server is trusted on first use and must present the same key after (OpenSSH SHA256 fingerprints). */
class DarpanSftpClientTests {
    // Generated with ssh-keygen; fingerprints are `ssh-keygen -lf` output.
    static final String ED25519 = "AAAAC3NzaC1lZDI1NTE5AAAAIDCuvbo+9kvDyieG2xL153CaRT76xhByGQy8dqz7OmlX"
    static final String ED25519_FP = "SHA256:rYoGcYjo510rKgfEDgEQauoIa4rWkmPrCTGVak7Xyg8"
    static final String RSA = "AAAAB3NzaC1yc2EAAAADAQABAAABAQCyQC84LpttTAhsGKQr+ITmpuMazJRF+oDfhqtsYEtTMy8Sb7xJORA2HnI93wbZRO8gMozoLUT+H5pqg+Ad9qV+INywolhagUvs6JC1hFk6B6qoZB8SevWuufqVG5vC+O/rZyVVEwntZnAMlDAhzv09AP4FObGWyvBYH05G0lW4s+3gVWoQjiuyDSKwMZVuCJWXG/UNWSxOAkBKStUHhrovgJJrhyvNAh6dWXIs9ZWGXF0OPN7w4njdIUIOFRl30RXnPf9TBkI+BSdjuGFnk2BCm8VVkfE4tkovYsYa/XLjqsEQ4F3HEd3Mh1Fg0Dy/yD7C3a6LyX2CZneXtD4JC58v"
    static final String RSA_FP = "SHA256:NSscVDSqFp4dn3swvUiJ8zTS57kzwAKrPzxyaciddHw"

    static PublicKey key(String b64) { new Buffer.PlainBuffer(Base64.decoder.decode(b64)).readPublicKey() }

    @Test
    void fingerprintsAreOpenSshs() {
        assertEquals(ED25519_FP, DarpanSftpClient.fingerprint(key(ED25519)))
        assertEquals(RSA_FP, DarpanSftpClient.fingerprint(key(RSA)))
    }

    @Test
    void noSavedKeyTrustsTheFirstAndRecordsIt() {
        def v = new DarpanSftpClient.PinningVerifier(null)
        assertTrue(v.verify("sftp.example", 22, key(ED25519)))
        assertEquals(ED25519_FP, v.seen)
    }

    @Test
    void aSavedKeyAcceptsOnlyItself() {
        def v = new DarpanSftpClient.PinningVerifier(ED25519_FP)
        assertTrue(v.verify("sftp.example", 22, key(ED25519)))
        def other = new DarpanSftpClient.PinningVerifier(ED25519_FP)
        assertFalse(other.verify("sftp.example", 22, key(RSA)), "another key is refused before any password is sent")
        assertEquals(RSA_FP, other.seen)
    }

    @Test
    void theMismatchMessageSaysWhatToDo() {
        String m = DarpanSftpClient.mismatchMessage("sftp.example", 22, ED25519_FP, RSA_FP)
        assertTrue(m.startsWith("The SFTP server sftp.example:22 presented a different key"), m)
        assertTrue(m.contains(RSA_FP) && m.contains(ED25519_FP) && m.contains("reset its saved key"), m)
    }
}
