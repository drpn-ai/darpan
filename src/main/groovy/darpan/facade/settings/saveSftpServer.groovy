import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

String sftpServerIdValue = FacadeSupport.normalize(sftpServerId)
String descriptionValue = FacadeSupport.normalize(description)
String hostValue = FacadeSupport.normalize(host)
String usernameValue = FacadeSupport.normalize(username)
String passwordValue = FacadeSupport.normalize(password)
String privateKeyValue = FacadeSupport.normalize(privateKey)
Integer portValue = FacadeSupport.normalizeInt(port, 22)
String remoteAttributesValue = FacadeSupport.normalizeBool(remoteAttributes, true) ? "Y" : "N"

PilotAccessSupport.requireSuperAdmin(ec, "Pilot settings are restricted to super-admin users.")

if (!sftpServerIdValue) ec.message.addError("Server ID is required.")
if (!hostValue) ec.message.addError("Host is required.")
if (!usernameValue) ec.message.addError("Username is required for SFTP access.")
if (portValue <= 0) ec.message.addError("Port must be greater than 0.")

if (!ec.message.hasError()) {
    def existing = ec.entity.find("darpan.reconciliation.SftpServer")
        .condition("sftpServerId", sftpServerIdValue)
        .useCache(false)
        .one()

    Map serverMap = [
        sftpServerId: sftpServerIdValue,
        description: descriptionValue,
        host: hostValue,
        port: portValue,
        username: usernameValue,
        remoteAttributes: remoteAttributesValue,
    ]

    if (passwordValue) serverMap.password = passwordValue
    else if (existing?.password) serverMap.password = existing.password

    if (privateKeyValue) serverMap.privateKey = privateKeyValue
    else if (existing?.privateKey) serverMap.privateKey = existing.privateKey

    ec.service.sync().name("store#darpan.reconciliation.SftpServer").parameters(serverMap).call()

    savedServer = [
        sftpServerId: sftpServerIdValue,
        description: descriptionValue,
        host: hostValue,
        port: portValue,
        username: usernameValue,
        remoteAttributes: remoteAttributesValue,
        hasPassword: !!FacadeSupport.normalize(serverMap.password),
        hasPrivateKey: !!FacadeSupport.normalize(serverMap.privateKey),
    ]

    if (!FacadeSupport.normalize(serverMap.password) && !FacadeSupport.normalize(serverMap.privateKey)) {
        ec.message.addMessage("Saved server without credentials. Add password or key before running automation.")
    } else if (!ec.message.hasError()) {
        ec.message.addMessage("Saved SFTP server ${sftpServerIdValue}.")
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
