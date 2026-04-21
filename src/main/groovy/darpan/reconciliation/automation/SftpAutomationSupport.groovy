package darpan.reconciliation.automation

import org.moqui.sftp.SftpClient

class SftpAutomationSupport {
    private static final Closure DEFAULT_CLIENT_FACTORY = { String host, String user, Integer port ->
        new SftpClient(host, user, port)
    }

    private static Closure clientFactory = DEFAULT_CLIENT_FACTORY

    static Object createClient(String host, String user, Integer port) {
        return clientFactory.call(host, user, port)
    }

    static void setClientFactory(Closure factory) {
        clientFactory = factory ?: DEFAULT_CLIENT_FACTORY
    }

    static void resetClientFactory() {
        clientFactory = DEFAULT_CLIENT_FACTORY
    }
}
