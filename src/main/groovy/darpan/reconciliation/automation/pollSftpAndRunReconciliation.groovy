import darpan.reconciliation.automation.SftpAutomationSupport
import darpan.reconciliation.core.ReconciliationServices
import org.slf4j.LoggerFactory
import org.apache.spark.sql.Dataset

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

def logger = LoggerFactory.getLogger("darpan.reconciliation.automation.SftpReconciliation")

def normalizeHostParts = { String host ->
    def normalized = normalize(host)
    if (!normalized) return [hostPort: null, basePath: null]
    normalized = normalized.replaceFirst(/^[a-zA-Z]+:\\/\\//, "")
    while (normalized.startsWith("//")) normalized = normalized.substring(2)

    String basePath = null
    int slashIdx = normalized.indexOf("/")
    if (slashIdx >= 0) {
        basePath = normalized.substring(slashIdx)
        normalized = normalized.substring(0, slashIdx)
    }

    normalized = normalized.replaceAll(/\/+$/, "")
    if (basePath != null) {
        basePath = basePath.replaceAll(/\/+$/, "")
        if (!basePath) basePath = "/"
    }

    return [hostPort: normalized, basePath: basePath]
}
def normalizePath = { String path ->
    def cleaned = normalize(path)
    if (!cleaned) return null
    cleaned = cleaned.replaceAll(/\\+/, "/")
    cleaned = cleaned.replaceAll(/\/+$/, "")
    if (!cleaned) return "/"
    if (!cleaned.startsWith("/")) cleaned = "/" + cleaned
    return cleaned
}
def resolveHostAndPort = { String host, Object portVal, String label ->
    def hostParts = normalizeHostParts(host)
    def hostOnly = hostParts.hostPort
    def hostBasePath = normalizePath(hostParts.basePath)
    if (!hostOnly) {
        throw new IllegalArgumentException("Host required for ${label}")
    }

    Integer inlinePort = null
    int lastColon = hostOnly.lastIndexOf(":")
    if (lastColon > -1 && lastColon < hostOnly.length() - 1) {
        def portStr = hostOnly.substring(lastColon + 1)
        def hostCandidate = hostOnly.substring(0, lastColon)
        inlinePort = normalizeInt(portStr)
        if (inlinePort != null) {
            hostOnly = hostCandidate
        } else {
            throw new IllegalArgumentException("Invalid port '${portStr}' in host ${host} for ${label}")
        }
    }

    String portString = normalize(portVal) ?: "22"
    if (inlinePort != null) portString = inlinePort.toString()

    Integer portNum = normalizeInt(portString)
    if (portNum == null) throw new IllegalArgumentException("Invalid port '${portString}' for ${label} (host ${host})")

    return [host: hostOnly, port: portNum, basePath: hostBasePath]
}
def ensureDirectory = { rr ->
    if (rr == null) return rr
    if (rr.supportsExists() && rr.getExists() && rr.isDirectory()) return rr
    def dirFile = rr.getFile()
    if (dirFile != null && !dirFile.exists()) dirFile.mkdirs()
    return rr
}
def stageStream = { InputStream inputStream, stageDirRef, String label, String sourceName, String timestamp ->
    def safeName = sourceName ?: label
    safeName = safeName.replaceAll("[^A-Za-z0-9._-]", "_")
    def targetRef = stageDirRef.makeFile("${timestamp}-${label}-${safeName}")

    OutputStream outputStream
    try {
        outputStream = targetRef.getOutputStream()
        outputStream << inputStream
    } finally {
        try { outputStream?.close() } catch (Exception ignored) {}
    }

    def stagedLocation = targetRef.getFile()?.getAbsolutePath() ?: targetRef.getLocation()
    return [location: stagedLocation, name: safeName]
}

def safeToken = { String raw, String fallback ->
    String normalized = normalize(raw)?.replaceAll(/[^A-Za-z0-9_-]/, "-")
    return normalized ?: fallback
}
def selectSftpCandidateFile = { client, String basePath, String label, String archiveFolderName = null ->
    List<Map> entries = []
    boolean listedDirectory = true
    try {
        entries = client.ls(basePath)
    } catch (Exception e) {
        listedDirectory = false
    }

    if (!listedDirectory) {
        def name = basePath.tokenize("/")?.last() ?: basePath
        return [refPath: basePath, name: name]
    }

    entries = entries.findAll { entry ->
        def nameVal = entry.name ?: ""
        def pathVal = entry.path ?: ""

        if (archiveFolderName) {
            if (nameVal == archiveFolderName) return false
            if (pathVal.endsWith("/${archiveFolderName}")) return false
        }

        def isFileFlag = entry.isFile == true
        def isDirFlag = (entry.isDir == true) || (entry.isDirectory == true)
        if (!isFileFlag) return false
        if (isDirFlag) return false
        return true
    }

    if (!entries) {
        return [refPath: null, name: null, message: "No files found at ${basePath} for ${label}"]
    }

    entries.sort { a, b ->
        def aTs = (a.lastModified ?: 0L)
        def bTs = (b.lastModified ?: 0L)
        def tsCompare = bTs <=> aTs
        if (tsCompare != 0) return tsCompare
        return (b.name ?: "") <=> (a.name ?: "")
    }

    def chosen = entries[0]
    def path = chosen.path ?: (basePath.endsWith("/") ? basePath + chosen.name : basePath + "/" + chosen.name)
    return [refPath: path, name: chosen.name]
}

String ruleSetIdValue = normalize(ruleSetId)
String compareScopeIdValue = normalize(compareScopeId)
String mappingIdValue = normalize(reconciliationMappingId)
String requestedFile1SystemEnumId = normalize(file1SystemEnumId)
String requestedFile2SystemEnumId = normalize(file2SystemEnumId)
Map<String, Object> sftpRunScope = SftpAutomationSupport.resolveRunScope(ec, sftpRunScopeEnumId, runTenantUserGroupId, allowAdminSftp)

if (!ruleSetIdValue && !mappingIdValue) {
    throw new IllegalArgumentException("Either ruleSetId or reconciliationMappingId is required")
}
if (!ruleSetIdValue && (!requestedFile1SystemEnumId || !requestedFile2SystemEnumId)) {
    throw new IllegalArgumentException("file1SystemEnumId and file2SystemEnumId are required when using reconciliationMappingId")
}
if (!file1SftpServerId || !file2SftpServerId) {
    throw new IllegalArgumentException("file1SftpServerId and file2SftpServerId are required")
}

Map compareScopeConfig = null
String effectiveFile1SystemEnumId = requestedFile1SystemEnumId
String effectiveFile2SystemEnumId = requestedFile2SystemEnumId
if (ruleSetIdValue) {
    compareScopeConfig = ReconciliationServices.resolveRuleSetCompareScopeConfig(
            ec,
            ruleSetIdValue,
            compareScopeIdValue,
            requestedFile1SystemEnumId,
            requestedFile2SystemEnumId
    )
    effectiveFile1SystemEnumId = normalize(compareScopeConfig.file1SystemEnumId)
    effectiveFile2SystemEnumId = normalize(compareScopeConfig.file2SystemEnumId)
}
if (!effectiveFile1SystemEnumId || !effectiveFile2SystemEnumId) {
    throw new IllegalArgumentException("file1SystemEnumId and file2SystemEnumId are required")
}

def stageDirRef = ensureDirectory(ec.resource.getLocationReference(stageLocation ?: "runtime://tmp/reconciliation/automation/input"))
if (stageDirRef == null) {
    throw new IllegalStateException("Unable to resolve stage directory ${stageLocation}")
}
if (!stageDirRef.supportsWrite()) {
    throw new IllegalStateException("Stage directory ${stageDirRef.getLocation()} is not writable")
}
def stageFileObj = stageDirRef.getFile()
if (stageFileObj != null && !stageFileObj.exists()) {
    stageFileObj.mkdirs()
}

def timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmssSSS")

processingWarnings = []
validationErrors = []
def messages = new StringBuilder()
def logMsg = { String msg ->
    logger.info(msg)
    messages.append(ec.l10n.format(ec.user.nowTimestamp, "HH:mm:ss")).append(" ").append(msg).append("\n")
    ec.message.addMessage(msg)
}

def remoteUploadPath = null
String defaultRunToken = ruleSetIdValue ?: mappingIdValue ?: reconciliationRunId
def defaultOutputPath = SftpAutomationSupport.resolveDefaultOutputLocation(ec, defaultRunToken, timestamp)
def defaultRemoteUploadPath = SftpAutomationSupport.remotePathForRuntimeLocation(defaultOutputPath)
def outputPathToUse = outputLocation ?: defaultOutputPath
def runtimeRoot = ec.factory?.runtimePath
if (outputPathToUse?.startsWith("/") && runtimeRoot && !outputPathToUse.startsWith(runtimeRoot)) {
    remoteUploadPath = normalizePath(outputPathToUse)
    processingWarnings << "Output location ${outputPathToUse} is treated as remote; writing locally and uploading to ${remoteUploadPath}"
    logMsg("Output location ${outputPathToUse} is treated as remote; writing locally and uploading to ${remoteUploadPath}")
    outputPathToUse = defaultOutputPath
}
def outputDirRef = ensureDirectory(ec.resource.getLocationReference(outputPathToUse))
if (outputDirRef == null || !outputDirRef.supportsWrite()) {
    processingWarnings << "Output location ${outputPathToUse} is not writable; falling back to ${defaultOutputPath}"
    logMsg("Output location ${outputPathToUse} not writable; falling back to ${defaultOutputPath}")
    outputPathToUse = defaultOutputPath
    outputDirRef = ensureDirectory(ec.resource.getLocationReference(outputPathToUse))
}
if (outputDirRef == null) {
    throw new IllegalStateException("Unable to resolve output directory ${outputPathToUse}")
}
def outputDirFile = outputDirRef.getFile()
if (outputDirFile != null && !outputDirFile.exists()) {
    if (!outputDirFile.mkdirs() && !outputDirFile.exists()) {
        processingWarnings << "Unable to create output directory ${outputDirFile.getAbsolutePath()}; using default ${defaultOutputPath}"
        logMsg("Unable to create output directory ${outputDirFile.getAbsolutePath()}; using default ${defaultOutputPath}")
        outputPathToUse = defaultOutputPath
        outputDirRef = ensureDirectory(ec.resource.getLocationReference(outputPathToUse))
        outputDirFile = outputDirRef?.getFile()
        if (outputDirFile != null && !outputDirFile.exists()) outputDirFile.mkdirs()
    }
}
if (!outputDirRef.supportsWrite()) {
    throw new IllegalStateException("Output directory ${outputDirRef.getLocation()} is not writable")
}
def outputLoc = outputDirRef.getLocation()
if (ruleSetIdValue) {
    logMsg("Starting SFTP poll for RuleSet ${ruleSetIdValue} compareScope ${compareScopeConfig.compareScopeId}")
} else {
    logMsg("Starting SFTP poll for mapping ${mappingIdValue}")
}

/** A connected client for one server, held to its saved host key (DAR-BE-082). */
def connectTo = { server, String host, int port ->
    def client = SftpAutomationSupport.createClient(host, normalize(server.username), port)
    String password = normalize(server.password)
    if (password) client.password(password)
    if (server.privateKey) client.privateKey((String) server.privateKey)
    client.preserveAttributes(server.remoteAttributes == "Y")
    SftpAutomationSupport.connectPinned(ec, client, server)
    return client
}

/** Finds and stages one side's newest file. Nothing moves on the server: a side is archived only once both are staged. */
def stageSide = { String label, String systemEnumId, String sftpServerId, String overridePath ->
    def server = SftpAutomationSupport.loadSftpServerForRun(ec, sftpServerId, sftpRunScope, label)
    def hostInfo = resolveHostAndPort(server.host, server.port, "Server ${sftpServerId}")
    if (!hostInfo.host || !normalize(server.username)) {
        throw new IllegalArgumentException("Host and Username required for ${label} (Server ${sftpServerId})")
    }
    if (!normalize(server.password) && !server.privateKey) {
        throw new IllegalArgumentException("Password or Private Key required for ${label} (Server ${sftpServerId})")
    }
    String basePath = normalizePath(overridePath) ?: hostInfo.basePath ?: "/"
    String source = "sftp://${hostInfo.host}:${hostInfo.port}${basePath}".toString()
    logMsg("Connecting to SFTP: ${hostInfo.host}:${hostInfo.port} as ${normalize(server.username)} (path: ${basePath})")

    def client = connectTo(server, (String) hostInfo.host, (int) hostInfo.port)
    try {
        def selection = selectSftpCandidateFile(client, basePath, label, archiveSubdir)
        if (!selection.refPath) {
            String failMsg = "No file found at ${basePath} matching criteria."
            logMsg(failMsg)
            return [found: false, message: failMsg, source: source]
        }
        logMsg("Found file: ${selection.name} (${selection.refPath})")
        def staged
        InputStream inputStream = client.openStream((String) selection.refPath)
        try {
            staged = stageStream(inputStream, stageDirRef, label, selection.name, timestamp)
            logMsg("Staged file to: ${staged.location}")
        } finally {
            try { inputStream?.close() } catch (Exception ignored) {}
        }
        return [found         : true, label: label, server: server, host: hostInfo.host, port: hostInfo.port, source: source,
                refPath       : selection.refPath, selectedName: selection.name ?: selection.refPath, stagedLocation: staged.location,
                systemLabel   : ReconciliationServices.resolveEnumLabel(ec, systemEnumId, label), basePathUsed: basePath]
    } finally {
        try { client.close() } catch (Exception ignored) {}
    }
}

/** Moves one staged side's file into its archive folder (rename, else copy and remove); answers where it went, or throws. */
def archiveSide = { Map prep ->
    String refPath = prep.refPath as String
    String baseDir = refPath.contains("/") ? refPath.substring(0, refPath.lastIndexOf("/")) : "/"
    String archiveDir = (baseDir.endsWith("/") ? baseDir : baseDir + "/") + (archiveSubdir ?: "archive")
    String fileName = refPath.tokenize("/")?.last() ?: refPath
    String archiveDest = archiveDir + "/" + fileName
    logMsg("Archiving remote file to ${archiveDest}")
    def client = connectTo(prep.server, (String) prep.host, (int) prep.port)
    try {
        try { client.mkdirs(archiveDir) } catch (Exception ignored) { }   // an existing folder answers with an error on some servers
        try {
            client.rename(refPath, archiveDest)
        } catch (Exception renameFailed) {
            InputStream srcStream = client.openStream(refPath)
            try {
                client.put(archiveDir, fileName, srcStream)
            } finally {
                try { srcStream?.close() } catch (Exception ignored) {}
            }
            client.rm(refPath)
        }
    } finally {
        try { client.close() } catch (Exception ignored) {}
    }
    return archiveDest
}

def file1Prep
def file2Prep
try {
    file1Prep = stageSide("file1", effectiveFile1SystemEnumId, file1SftpServerId, file1RemotePath)
    file2Prep = stageSide("file2", effectiveFile2SystemEnumId, file2SftpServerId, file2RemotePath)
} catch (IllegalArgumentException e) {
    ec.message.addError(e.message)
    dataAvailable = false
    statusMessage = messages.toString() + "\nResult: " + e.message
    return
}

file1Source = file1Prep.source
file2Source = file2Prep.source
file1SelectedName = file1Prep.selectedName
file2SelectedName = file2Prep.selectedName
def file1BasePathUsed = file1Prep.basePathUsed

if (!file1Prep.found || !file2Prep.found) {
    // OC116: a side found while the other has not arrived stays where it is, so a later poll still reconciles the pair.
    dataAvailable = false
    def msg = (file1Prep.message ?: "") + (file1Prep.message && file2Prep.message ? "; " : "") + (file2Prep.message ?: "")
    if (!msg) msg = "No files found to reconcile"
    statusMessage = messages.toString() + "\nResult: " + msg
    logger.info("SFTP Poll finished: ${msg}")
    return
}

// OC116: both sides are archived before anything is reconciled. A side that cannot be archived would be read again by the
// next poll and reconciled twice, so the poll fails here with nothing reconciled and says what to move back.
List<Map> archivedSides = []
for (Map prep in [file1Prep, file2Prep]) {
    try {
        archivedSides << [prep: prep, dest: archiveSide(prep)]
    } catch (Exception e) {
        logger.error("Unable to archive SFTP file ${prep.refPath} (${prep.source}): ${e.message}", e)
        String msg = "Could not archive ${prep.label} ${prep.refPath} (${prep.source}): ${e.message}. Nothing was reconciled." +
                (archivedSides ? archivedSides.collect { Map done ->
                    " ${done.prep.label} ${done.prep.refPath} was already archived to ${done.dest}: move it back to " +
                            "${done.prep.refPath} so the next poll reconciles the pair."
                }.join("") : " The next poll reads it again once its archive folder takes the file.")
        ec.message.addError(msg)
        dataAvailable = false
        statusMessage = messages.toString() + "\nResult: " + msg
        return
    }
}

file1StagedLocation = file1Prep.stagedLocation
file2StagedLocation = file2Prep.stagedLocation
dataAvailable = true

// Security (HIGH gaps 3,5): IGNORE any caller-supplied sparkMaster. Resolve server-side only so an
// authenticated API caller cannot redirect the tenant's Spark job to an attacker cluster. The PWA
// never sends this field; operators set spark.master via the server property.
def sparkMasterToUse = (ec.resource.properties['spark.master'] ?: "local[*]")

ruleExecutionFailed = false

if (ruleSetIdValue) {
  // Self-review #16: unpersist the Spark Datasets persisted by reconcile#RuleSetCompareScope on every
  // exit path of this branch (after the output write, or on failure).
  List ruleSetPersistedSources = []
  try {
    Map reconcileResult = ec.service.sync()
            .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
            .parameters([
                    ruleSetId          : ruleSetIdValue,
                    compareScopeId     : compareScopeConfig.compareScopeId,
                    file1Location      : file1StagedLocation,
                    file2Location      : file2StagedLocation,
                    file1Name          : file1SelectedName,
                    file2Name          : file2SelectedName,
                    file1Label         : compareScopeConfig.file1Label,
                    file2Label         : compareScopeConfig.file2Label,
                    file1FileTypeEnumId: file1FileTypeEnumId,
                    file2FileTypeEnumId: file2FileTypeEnumId,
                    file1SchemaFileName: file1SchemaFileName,
                    file2SchemaFileName: file2SchemaFileName,
                    hasHeader          : hasHeader,
                    sparkMaster        : sparkMasterToUse,
                    sparkAppName       : sparkAppName
            ])
            .call()
    ruleSetPersistedSources = (reconcileResult.persistedSources ?: []) as List
    // Self-review #4: surface a rule build/eval failure so the SFTP automation records FAILED.
    ruleExecutionFailed = reconcileResult.ruleExecutionFailed == true

    file1Type = reconcileResult.file1Type
    file2Type = reconcileResult.file2Type
    reconciliationType = ReconciliationServices.determineReconciliationType(file1Type as String, file2Type as String)
    differenceCount = reconcileResult.differenceCount
    onlyInFile1Count = reconcileResult.missingInFile2Count
    onlyInFile2Count = reconcileResult.missingInFile1Count
    validationErrors = (reconcileResult.validationErrors ?: []) as List
    processingWarnings = ((processingWarnings ?: []) + (reconcileResult.processingWarnings ?: [])) as List

    String compareScopeNameToken = safeToken(compareScopeConfig.compareScopeDescription as String,
            safeToken(compareScopeConfig.compareScopeId as String, safeToken(ruleSetIdValue, 'ruleset')))
    String defaultBaseName = "${compareScopeNameToken}-diff-${timestamp}.json"
    Map outputMetadata = [
            timestamp          : ReconciliationServices.formatMetadataTimestamp(ec.user.nowTimestamp),
            reconciliationRunId: normalize(reconciliationRunId),
            file1Label         : compareScopeConfig.file1Label,
            file2Label         : compareScopeConfig.file2Label,
            file1Type          : file1Type,
            file2Type          : file2Type,
            reconciliation     : reconciliationType,
            ruleSetId          : ruleSetIdValue,
            compareScopeId     : compareScopeConfig.compareScopeId,
            compareScopeDescription: compareScopeConfig.compareScopeDescription,
            objectType         : reconcileResult.objectType,
            file1Source        : file1Source,
            file2Source        : file2Source
    ]
    Map outputSummary = [
            totalDifferences            : differenceCount,
            onlyInFile1Count            : onlyInFile1Count,
            onlyInFile2Count            : onlyInFile2Count,
            missingObjectDifferenceCount: reconcileResult.missingObjectDifferenceCount,
            ruleDifferenceCount         : reconcileResult.ruleDifferenceCount
    ]
    Map outputInfo = ReconciliationServices.writeDiffDatasetOutput(
            ec,
            (Dataset) reconcileResult.diffDf,
            outputLoc,
            null,
            defaultBaseName,
            outputMetadata,
            outputSummary,
            validationErrors,
            processingWarnings
    )

    diffLocation = outputInfo.diffLocation
    diffFileName = outputInfo.diffFileName
  } finally {
    ReconciliationServices.unpersistDatasets(ruleSetPersistedSources)
  }
} else {
    Map reconcileResult = ec.service.sync()
            .name("reconciliation.ReconciliationCoreServices.reconcile#FilesByMapping")
            .parameters([
                    reconciliationMappingId: mappingIdValue,
                    file1Location          : file1StagedLocation,
                    file2Location          : file2StagedLocation,
                    file1Name              : file1SelectedName,
                    file2Name              : file2SelectedName,
                    file1SystemEnumId      : effectiveFile1SystemEnumId,
                    file2SystemEnumId      : effectiveFile2SystemEnumId,
                    file1FileTypeEnumId    : file1FileTypeEnumId,
                    file2FileTypeEnumId    : file2FileTypeEnumId,
                    file1SchemaFileName    : file1SchemaFileName,
                    file2SchemaFileName    : file2SchemaFileName,
                    file1Label             : file1Prep.systemLabel ?: ReconciliationServices.resolveEnumLabel(ec, effectiveFile1SystemEnumId, "File 1"),
                    file2Label             : file2Prep.systemLabel ?: ReconciliationServices.resolveEnumLabel(ec, effectiveFile2SystemEnumId, "File 2"),
                    hasHeader              : hasHeader,
                    outputLocation         : outputLoc,
                    sparkMaster            : sparkMasterToUse,
                    sparkAppName           : sparkAppName
            ])
            .call()

    file1Type = reconcileResult.file1Type
    file2Type = reconcileResult.file2Type
    reconciliationType = reconcileResult.reconciliationType ?: ReconciliationServices.determineReconciliationType(file1Type as String, file2Type as String)
    diffLocation = reconcileResult.diffLocation
    diffFileName = reconcileResult.diffFileName
    differenceCount = reconcileResult.differenceCount
    onlyInFile1Count = reconcileResult.onlyInFile1Count
    onlyInFile2Count = reconcileResult.onlyInFile2Count
    validationErrors = reconcileResult.validationErrors ?: []
    processingWarnings = (processingWarnings ?: []) + (reconcileResult.processingWarnings ?: [])
}
statusMessage = "${reconciliationType ?: 'Reconciliation'} complete"

logger.info("SFTP reconciliation done: run=${reconciliationRunId ?: 'ad-hoc'} type=${reconciliationType} diff=${diffFileName} dataAvailable=${dataAvailable}")
logMsg("Reconciliation complete. Diff: ${diffFileName}. Counts: Only1=${onlyInFile1Count}, Only2=${onlyInFile2Count}, Diff=${differenceCount}")

// Upload Result to SFTP (Server 1)
try {
    def resultFile = null
    if (diffLocation) {
        resultFile = ec.resource.getLocationReference(diffLocation)
    } else {
         resultFile = ec.resource.getLocationReference(outputLoc + "/" + diffFileName)
    }

    if (resultFile && (!resultFile.supportsExists() || resultFile.getExists())) {
        def server = file1Prep.server ?: SftpAutomationSupport.loadSftpServerForRun(ec, file1SftpServerId, sftpRunScope, "result upload")
        if (server) {
            def hostInfo = resolveHostAndPort(server.host, server.port, "Server ${file1SftpServerId}")
            def cleanHost = hostInfo.host
            def cleanPort = hostInfo.port
            def remoteResultDir = normalizePath(remoteUploadPath ?: (outputLocation ? (file1BasePathUsed ?: hostInfo.basePath) : defaultRemoteUploadPath)) ?: "/"
            def cleanUser = normalize(server.username)
            def cleanPass = normalize(server.password)
            
            def sftpClient = SftpAutomationSupport.createClient((String) cleanHost, (String) cleanUser, (int) cleanPort)
            
            if (cleanPass) sftpClient.password((String) cleanPass)
            if (server.privateKey) sftpClient.privateKey((String) server.privateKey)
            
            logMsg("Uploading result ${diffFileName} to SFTP Server 1 (${cleanHost}:${cleanPort}) at ${remoteResultDir} ...")
            try {
                SftpAutomationSupport.connectPinned(ec, sftpClient, server)
                try { sftpClient.mkdirs(remoteResultDir) } catch (Exception ignored) {}
                String remoteResultPath = (remoteResultDir.endsWith("/") ? remoteResultDir + diffFileName : remoteResultDir + "/" + diffFileName)
                
                // Read local file stream
                InputStream resultStream = resultFile.openStream()
                try {
                    sftpClient.put(remoteResultDir, diffFileName, resultStream)
                    logMsg("Result uploaded successfully to ${remoteResultPath}")
                } finally {
                    try { resultStream?.close() } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                logMsg("Error uploading result to SFTP: ${e.message}")
                logger.error("SFTP Upload Error", e)
            } finally {
                try { sftpClient.close() } catch (Exception ignored) {}
            }
        }
    } else {
        logMsg("Result file ${diffFileName} not found at ${outputLoc}, skipping upload.")
    }
} catch (Exception e) {
    logMsg("Failed to initiate result upload: ${e.message}")
}

statusMessage = messages.toString()
