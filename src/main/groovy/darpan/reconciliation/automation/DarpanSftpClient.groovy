package darpan.reconciliation.automation

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.xfer.InMemorySourceFile

import java.security.MessageDigest
import java.security.PublicKey

/**
 * DAR-BE-082. Darpan's SFTP client. moqui-sftp's SftpClient accepts any server key (PromiscuousVerifier), so a spoofed
 * or intercepted server would receive the stored password and could feed forged files into a reconciliation. This one
 * checks the server's key BEFORE authenticating: with no saved key it trusts the first and reports it (seenHostKey) so
 * the caller can save it; with a saved key it accepts only that key. It also has the two methods the poller calls that
 * moqui-sftp never had (privateKey(String), mkdirs). Fingerprints are OpenSSH's: "SHA256:" + base64 of the key blob's
 * SHA-256, no padding (what `ssh-keygen -lf` prints).
 */
class DarpanSftpClient implements Closeable {
    private final String host, username
    private final int port
    private String password, privateKeyPem
    private boolean preserveAttributes = true
    private String expectedHostKey
    private PinningVerifier verifier
    private SSHClient ssh
    private SFTPClient sftp

    DarpanSftpClient(String host, String username, int port = 22) {
        this.host = host; this.username = username; this.port = port
    }

    DarpanSftpClient password(String pw) { password = pw; return this }
    /** An OpenSSH or PEM private key, as stored on SftpServer.privateKey. */
    DarpanSftpClient privateKey(String pem) { privateKeyPem = pem; return this }
    DarpanSftpClient preserveAttributes(boolean pa) { preserveAttributes = pa; return this }
    /** The saved fingerprint the server must present; null trusts the first key seen. */
    DarpanSftpClient expectedHostKey(String fingerprint) { expectedHostKey = fingerprint ?: null; return this }
    /** The fingerprint the server presented on connect (to save on first use). */
    String getSeenHostKey() { return verifier?.seen }

    DarpanSftpClient connect() throws IOException {
        if (!host || !username) throw new IllegalStateException("Host or username not specified, cannot connect to ${username}@${host}:${port}")
        if (!password && !privateKeyPem) throw new IllegalStateException("No password or private key for ${username}@${host}:${port}")
        ssh = new SSHClient()
        verifier = new PinningVerifier(expectedHostKey)
        ssh.addHostKeyVerifier(verifier)
        try {
            ssh.connect(host, port)
        } catch (TransportException e) {
            closeQuietly()
            if (expectedHostKey && verifier.seen && verifier.seen != expectedHostKey) {
                throw new IllegalStateException(mismatchMessage(host, port, expectedHostKey, verifier.seen))
            }
            throw e
        }
        try {
            if (password) ssh.authPassword(username, password)
            else ssh.authPublickey(username, ssh.loadKeys(privateKeyPem, null, null))
            sftp = ssh.newSFTPClient()
            if (!preserveAttributes) sftp.getFileTransfer().setPreserveAttributes(false)
        } catch (Throwable t) {
            closeQuietly()
            throw t
        }
        return this
    }

    List<Map<String, Object>> ls(String path) {
        return connected().ls(path).findAll { RemoteResourceInfo r -> r.isRegularFile() || r.isDirectory() }.collect { RemoteResourceInfo r ->
            FileAttributes a = r.getAttributes()
            [name: r.getName(), path: r.getPath(), isFile: r.isRegularFile(), size: a.getSize(), lastAccess: a.getAtime(), lastModified: a.getMtime()] as Map<String, Object>
        }
    }

    List<String> lsFiles(String path) { return connected().ls(path).findAll { it.isRegularFile() }*.getName() }

    InputStream openStream(String path) {
        RemoteFile rf = connected().open(path, EnumSet.of(OpenMode.READ))
        InputStream base = RemoteFile.RemoteFileInputStream.newInstance(rf)
        return new FilterInputStream(base) {
            @Override void close() throws IOException { try { super.close() } finally { rf.close() } }
        }
    }

    DarpanSftpClient put(String path, String filename, InputStream is) {
        connected().put(new InMemorySourceFile() {
            @Override String getName() { return filename }
            @Override long getLength() { return 0L }
            @Override InputStream getInputStream() { return is }
        }, path)
        return this
    }

    void mkdirs(String path) { connected().mkdirs(path) }
    String rename(String from, String to) { connected().rename(from, to); return to }
    DarpanSftpClient rm(String path) { connected().rm(path); return this }

    @Override void close() throws IOException { if (ssh != null && ssh.isConnected()) ssh.close() }

    private SFTPClient connected() {
        if (sftp == null || !ssh?.isConnected()) throw new IllegalStateException("SFTP client not connected")
        return sftp
    }

    private void closeQuietly() { try { ssh?.close() } catch (Throwable ignored) {} }

    /** OpenSSH's SHA256 fingerprint of a public key. */
    static String fingerprint(PublicKey key) {
        byte[] blob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData()
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.encoder.withoutPadding().encodeToString(digest)
    }

    static String mismatchMessage(String host, int port, String saved, String seen) {
        return ("The SFTP server ${host}:${port} presented a different key (${seen}) from the one saved (${saved}), so nothing was sent " +
                "to it. If the server was replaced on purpose, reset its saved key in SFTP settings.").toString()
    }

    /** Accepts the saved key only; with none saved, the first one. Records what the server presented either way. */
    static class PinningVerifier implements HostKeyVerifier {
        final String expected
        String seen
        PinningVerifier(String expected) { this.expected = expected }
        @Override boolean verify(String hostname, int port, PublicKey key) {
            seen = fingerprint(key)
            return expected == null || expected == seen
        }
        @Override List<String> findExistingAlgorithms(String hostname, int port) { return Collections.emptyList() }
    }
}
