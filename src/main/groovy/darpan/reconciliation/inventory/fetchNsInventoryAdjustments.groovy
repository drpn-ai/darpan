import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import org.slf4j.LoggerFactory
import darpan.reconciliation.inventory.InventoryWarningSupport

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.time.Duration
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Field Map<String, Map> NS_TOKEN_CACHE = new ConcurrentHashMap<>()

def logger = LoggerFactory.getLogger("darpan.reconciliation.inventory.fetchNsInventoryAdjustments")

def normalize = { Object value -> value?.toString()?.trim() }
def unquote = { String value ->
    String raw = normalize(value)
    if (!raw || raw.length() < 2) return raw
    if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
        return raw.substring(1, raw.length() - 1).trim()
    }
    return raw
}
def warningList = []

String nsConfigId = normalize(nsRestletConfigId)
String itemIdStr = normalize(itemId)
String locationIdStr = normalize(locationId)
String fromDate = normalize(from)
String toDate = normalize(to)

if (!nsConfigId) throw new IllegalArgumentException("nsRestletConfigId is required")
if (!itemIdStr) throw new IllegalArgumentException("itemId is required")
if (!locationIdStr) throw new IllegalArgumentException("locationId is required")
if (!fromDate) throw new IllegalArgumentException("from is required in yyyy-MM-dd format")
if (!toDate) throw new IllegalArgumentException("to is required in yyyy-MM-dd format")
if (!(fromDate ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("from must be yyyy-MM-dd")
if (!(toDate ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("to must be yyyy-MM-dd")

def nsConfig = ec.entity.find("darpan.reconciliation.NsRestletConfig")
        .condition("nsRestletConfigId", nsConfigId)
        .useCache(false)
        .one()
if (!nsConfig) throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} not found")
if ((normalize(nsConfig.isActive) ?: "Y").equalsIgnoreCase("N")) {
    throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} is inactive")
}

String linkedAuthConfigId = normalize(nsConfig.nsAuthConfigId)
def nsAuthConfig = null
if (linkedAuthConfigId) {
    nsAuthConfig = ec.entity.find("darpan.reconciliation.NsAuthConfig")
            .condition("nsAuthConfigId", linkedAuthConfigId)
            .useCache(false)
            .one()
    if (!nsAuthConfig) {
        throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} references missing NsAuthConfig ${linkedAuthConfigId}")
    }
    if ((normalize(nsAuthConfig.isActive) ?: "Y").equalsIgnoreCase("N")) {
        throw new IllegalArgumentException("NsAuthConfig ${linkedAuthConfigId} is inactive")
    }
}
def authSource = nsAuthConfig ?: nsConfig

String endpointUrl = normalize(nsConfig.endpointUrl)
if (!endpointUrl) throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} is missing endpointUrl")

String httpMethod = (normalize(nsConfig.httpMethod) ?: "POST").toUpperCase()
if (!["POST", "PUT", "GET"].contains(httpMethod)) {
    throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} httpMethod must be POST, PUT, or GET")
}

String authType = (normalize(authSource.authType) ?: "NONE").toUpperCase()
if (!["NONE", "BASIC", "BEARER", "OAUTH2_M2M_JWT"].contains(authType)) {
    throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} authType must be NONE, BASIC, BEARER, or OAUTH2_M2M_JWT")
}

int connectTimeoutSeconds = (nsConfig.connectTimeoutSeconds ?: 30) as int
int readTimeoutSeconds = (nsConfig.readTimeoutSeconds ?: 60) as int
if (connectTimeoutSeconds < 1) connectTimeoutSeconds = 30
if (readTimeoutSeconds < 1) readTimeoutSeconds = 60

Map headersMap = [:]
String headersJson = normalize(nsConfig.headersJson)
if (headersJson) {
    def parsedHeaders = new JsonSlurper().parseText(headersJson)
    if (!(parsedHeaders instanceof Map)) {
        throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} headersJson must be a JSON object")
    }
    parsedHeaders.each { key, value ->
        if (key != null && value != null) headersMap[key.toString()] = value.toString()
    }
}

def toTypedNumber = { String rawValue ->
    if (!rawValue) return null
    if (rawValue ==~ /-?\d+/) {
        try {
            return Long.valueOf(rawValue)
        } catch (Exception ignored) {
            return rawValue
        }
    }
    if (rawValue ==~ /-?\d+\.\d+/) {
        try {
            return new BigDecimal(rawValue)
        } catch (Exception ignored) {
            return rawValue
        }
    }
    return rawValue
}

def payloadMap = [
        itemId    : toTypedNumber(itemIdStr),
        locationId: toTypedNumber(locationIdStr),
        from      : fromDate,
        to        : toDate
]
String payloadJson = JsonOutput.toJson(payloadMap)

HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .build()

def base64UrlEncode = { byte[] raw ->
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
}

def randomJti = { int len ->
    String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    SecureRandom rng = new SecureRandom()
    StringBuilder sb = new StringBuilder(len)
    (0..<len).each { sb.append(chars.charAt(rng.nextInt(chars.length()))) }
    return sb.toString()
}

def removeLeadingZeros = { byte[] src ->
    int idx = 0
    while (idx < src.length - 1 && src[idx] == 0) idx++
    return Arrays.copyOfRange(src, idx, src.length)
}

def toFixedLength = { byte[] raw, int length ->
    byte[] trimmed = removeLeadingZeros(raw)
    if (trimmed.length > length) {
        throw new IllegalArgumentException("ECDSA coordinate length ${trimmed.length} exceeds expected ${length}")
    }
    byte[] out = new byte[length]
    System.arraycopy(trimmed, 0, out, length - trimmed.length, trimmed.length)
    return out
}

def derToJoseEcdsa = { byte[] derSignature, int outputLength ->
    if (!derSignature || derSignature.length < 8 || derSignature[0] != (byte) 0x30) {
        throw new IllegalArgumentException("Invalid DER signature format for ECDSA JWT")
    }
    int offset = 1
    int seqLen = derSignature[offset++] & 0xFF
    if ((seqLen & 0x80) != 0) {
        int sizeBytes = seqLen & 0x7F
        seqLen = 0
        (0..<sizeBytes).each { seqLen = (seqLen << 8) | (derSignature[offset++] & 0xFF) }
    }
    if (offset >= derSignature.length || derSignature[offset++] != (byte) 0x02) {
        throw new IllegalArgumentException("Invalid DER signature R marker for ECDSA JWT")
    }
    int rLen = derSignature[offset++] & 0xFF
    byte[] rBytes = Arrays.copyOfRange(derSignature, offset, offset + rLen)
    offset += rLen
    if (offset >= derSignature.length || derSignature[offset++] != (byte) 0x02) {
        throw new IllegalArgumentException("Invalid DER signature S marker for ECDSA JWT")
    }
    int sLen = derSignature[offset++] & 0xFF
    byte[] sBytes = Arrays.copyOfRange(derSignature, offset, offset + sLen)

    int partLen = outputLength / 2
    byte[] jose = new byte[outputLength]
    byte[] rFixed = toFixedLength(rBytes, partLen)
    byte[] sFixed = toFixedLength(sBytes, partLen)
    System.arraycopy(rFixed, 0, jose, 0, partLen)
    System.arraycopy(sFixed, 0, jose, partLen, partLen)
    return jose
}

def parsePkcs8PrivateKey = { String pemText ->
    String normalizedPem = pemText?.replace("\\n", "\n")?.trim()
    if (!normalizedPem) throw new IllegalArgumentException("Private key PEM is required")

    String b64 = normalizedPem
            .replaceAll("-----BEGIN [A-Z ]+-----", "")
            .replaceAll("-----END [A-Z ]+-----", "")
            .replaceAll("\\s", "")
    byte[] keyBytes
    try {
        keyBytes = Base64.getDecoder().decode(b64)
    } catch (Exception e) {
        throw new IllegalArgumentException("Invalid PEM format for private key: ${e.message}")
    }

    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes)
    try {
        PrivateKey rsaKey = KeyFactory.getInstance("RSA").generatePrivate(spec)
        return [privateKey: rsaKey, alg: "PS256"]
    } catch (Exception ignored) {}
    try {
        PrivateKey ecKey = KeyFactory.getInstance("EC").generatePrivate(spec)
        return [privateKey: ecKey, alg: "ES256"]
    } catch (Exception ignored) {}

    throw new IllegalArgumentException("Private key must be PKCS#8 RSA or EC PEM")
}

def signClientAssertionJwt = { String tokenUrl, String clientId, String certId, String privateKeyPem, String scope ->
    long nowSec = (System.currentTimeMillis() / 1000L) as long
    Map keyInfo = parsePkcs8PrivateKey(privateKeyPem)
    PrivateKey privateKey = (PrivateKey) keyInfo.privateKey
    String alg = keyInfo.alg as String

    Map jwtHeader = [alg: alg, typ: "JWT", kid: certId]
    Map jwtPayload = [
            iss  : clientId,
            aud  : tokenUrl,
            iat  : nowSec,
            exp  : nowSec + 300L,
            jti  : randomJti(24),
            scope: scope
    ]

    String encodedHeader = base64UrlEncode(JsonOutput.toJson(jwtHeader).getBytes(StandardCharsets.UTF_8))
    String encodedPayload = base64UrlEncode(JsonOutput.toJson(jwtPayload).getBytes(StandardCharsets.UTF_8))
    String signingInput = "${encodedHeader}.${encodedPayload}"

    byte[] signatureBytes
    if ("PS256".equals(alg)) {
        Signature sig = Signature.getInstance("RSASSA-PSS")
        sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        sig.initSign(privateKey)
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8))
        signatureBytes = sig.sign()
    } else if ("ES256".equals(alg)) {
        Signature sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8))
        signatureBytes = derToJoseEcdsa(sig.sign(), 64)
    } else {
        throw new IllegalArgumentException("Unsupported JWT alg ${alg}")
    }

    return [jwt: "${signingInput}.${base64UrlEncode(signatureBytes)}", alg: alg]
}

def fetchOauthAccessToken = { String tokenUrl, String clientAssertion, int timeoutSeconds ->
    String formBody = "grant_type=${URLEncoder.encode('client_credentials', StandardCharsets.UTF_8)}" +
            "&client_assertion_type=${URLEncoder.encode('urn:ietf:params:oauth:client-assertion-type:jwt-bearer', StandardCharsets.UTF_8)}" +
            "&client_assertion=${URLEncoder.encode(clientAssertion, StandardCharsets.UTF_8)}"

    HttpRequest tokenReq = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build()

    HttpResponse<String> tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString())
    int tokenStatus = tokenResp.statusCode()
    if (tokenStatus < 200 || tokenStatus > 299) {
        String err = normalize(tokenResp.body())
        if (err && err.length() > 400) err = err.substring(0, 400) + "..."
        throw new IllegalArgumentException("Token request failed with HTTP ${tokenStatus}${err ? ': ' + err : ''}")
    }

    def tokenParsed = new JsonSlurper().parseText(tokenResp.body() ?: "{}")
    if (!(tokenParsed instanceof Map)) throw new IllegalArgumentException("Token response is not a JSON object")
    String accessToken = normalize(tokenParsed.access_token)
    if (!accessToken) throw new IllegalArgumentException("Token response missing access_token")

    long expiresIn = 3600L
    Object expiresRaw = tokenParsed.expires_in
    if (expiresRaw != null) {
        try {
            expiresIn = (expiresRaw as BigDecimal).longValue()
        } catch (Exception ignored) {}
    }
    if (expiresIn < 60L) expiresIn = 60L
    long expiresAtSec = (System.currentTimeMillis() / 1000L) as long
    expiresAtSec += (expiresIn - 30L)

    return [accessToken: accessToken, expiresAtSec: expiresAtSec]
}

String authCacheKey = linkedAuthConfigId ?: nsConfigId
if (!authCacheKey) throw new IllegalArgumentException("Auth cache key is missing for ${nsConfigId}")
Map<String, Map> tokenCache = NS_TOKEN_CACHE
if (tokenCache == null) {
    tokenCache = new ConcurrentHashMap<>()
    NS_TOKEN_CACHE = tokenCache
}
def resolveOauthToken = { ->
    String tokenUrl = normalize(authSource.tokenUrl)
    String clientId = normalize(authSource.clientId)
    String certId = normalize(authSource.certId)
    String privateKeyPem = authSource.privateKeyPem?.toString()
    String scope = unquote(authSource.scope?.toString()) ?: "restlets rest_webservices"

    if (!tokenUrl) throw new IllegalArgumentException("Auth config ${authCacheKey} requires tokenUrl for OAUTH2_M2M_JWT")
    if (!clientId) throw new IllegalArgumentException("Auth config ${authCacheKey} requires clientId for OAUTH2_M2M_JWT")
    if (!certId) throw new IllegalArgumentException("Auth config ${authCacheKey} requires certId for OAUTH2_M2M_JWT")
    if (!privateKeyPem) throw new IllegalArgumentException("Auth config ${authCacheKey} requires privateKeyPem for OAUTH2_M2M_JWT")

    long nowSec = (System.currentTimeMillis() / 1000L) as long
    Map cached = tokenCache[authCacheKey]
    if (cached && cached.tokenUrl == tokenUrl && cached.expiresAtSec instanceof Number &&
            ((cached.expiresAtSec as Number).longValue() > nowSec)) {
        return cached.accessToken as String
    }

    synchronized (tokenCache) {
        nowSec = (System.currentTimeMillis() / 1000L) as long
        cached = tokenCache[authCacheKey]
        if (cached && cached.tokenUrl == tokenUrl && cached.expiresAtSec instanceof Number &&
                ((cached.expiresAtSec as Number).longValue() > nowSec)) {
            return cached.accessToken as String
        }

        Map assertionOut = signClientAssertionJwt(tokenUrl, clientId, certId, privateKeyPem, scope)
        warningList.add("NS OAuth2 JWT assertion generated using ${assertionOut.alg}.")
        Map tokenOut = fetchOauthAccessToken(tokenUrl, assertionOut.jwt as String, readTimeoutSeconds)
        tokenCache[authCacheKey] = [
                tokenUrl    : tokenUrl,
                accessToken : tokenOut.accessToken,
                expiresAtSec: tokenOut.expiresAtSec
        ]
        return tokenOut.accessToken as String
    }
}

HttpRequest.Builder requestBuilder
if (httpMethod == "GET") {
    String separator = endpointUrl.contains("?") ? "&" : "?"
    String query = "itemId=${URLEncoder.encode(itemIdStr, StandardCharsets.UTF_8)}" +
            "&locationId=${URLEncoder.encode(locationIdStr, StandardCharsets.UTF_8)}" +
            "&from=${URLEncoder.encode(fromDate, StandardCharsets.UTF_8)}" +
            "&to=${URLEncoder.encode(toDate, StandardCharsets.UTF_8)}"
    requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl + separator + query))
            .timeout(Duration.ofSeconds(readTimeoutSeconds))
            .GET()
} else {
    requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl))
            .timeout(Duration.ofSeconds(readTimeoutSeconds))
            .header("Content-Type", "application/json")
    if (httpMethod == "PUT") requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(payloadJson))
    else requestBuilder.POST(HttpRequest.BodyPublishers.ofString(payloadJson))
}

headersMap.each { key, value ->
    requestBuilder.header(key, value)
}

String username = normalize(authSource.username)
String password = authSource.password?.toString()
String apiToken = authSource.apiToken?.toString() ?: password
if (authType == "BASIC") {
    if (!username || password == null) {
        throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} requires username and password for BASIC auth")
    }
    String token = Base64.getEncoder().encodeToString("${username}:${password}".getBytes(StandardCharsets.UTF_8))
    requestBuilder.setHeader("Authorization", "Basic ${token}")
}
if (authType == "BEARER") {
    if (!apiToken) throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} requires apiToken (or password) for BEARER auth")
    requestBuilder.setHeader("Authorization", "Bearer ${apiToken}")
}
if (authType == "OAUTH2_M2M_JWT") {
    String accessToken = resolveOauthToken()
    requestBuilder.setHeader("Authorization", "Bearer ${accessToken}")
}

logger.info("Calling NetSuite restlet endpointConfig={} authConfig={} itemId={} locationId={} from={} to={} method={} authType={}",
        nsConfigId, (linkedAuthConfigId ?: "LEGACY_EMBEDDED"), itemIdStr, locationIdStr, fromDate, toDate, httpMethod, authType)

HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
statusCode = response.statusCode()
responseBody = response.body()
if (statusCode < 200 || statusCode > 299) {
    String errorBody = normalize(responseBody)
    if (errorBody && errorBody.length() > 400) errorBody = errorBody.substring(0, 400) + "..."
    throw new IllegalArgumentException("NetSuite restlet call failed for ${nsConfigId} with HTTP ${statusCode}${errorBody ? ': ' + errorBody : ''}")
}

Object parsedBody = null
if (normalize(responseBody)) {
    try {
        parsedBody = new JsonSlurper().parseText(responseBody)
    } catch (Exception parseEx) {
        warningList.add("NS response was not JSON for itemId=${itemIdStr}, locationId=${locationIdStr}; returning raw body only.")
    }
}

def extractRecords
extractRecords = { Object parsed ->
    if (parsed == null) return []
    if (parsed instanceof List) return (List) parsed
    if (parsed instanceof Map) {
        Map parsedMap = (Map) parsed
        if (parsedMap.containsKey("ok") && !(parsedMap.ok in [true, "true", "Y", "y", 1])) {
            String nsError = normalize(parsedMap.error ?: parsedMap.message ?: parsedMap.status ?: "Restlet returned ok=false")
            throw new IllegalArgumentException("NetSuite restlet response error for ${nsConfigId}: ${nsError}")
        }
        if (parsedMap.transactions instanceof List) {
            List transactions = (List) parsedMap.transactions
            if (!transactions) return []
            return transactions.collect { tx ->
                [
                        itemId    : parsedMap.itemId,
                        locationId: parsedMap.locationId,
                        from      : parsedMap.from,
                        to        : parsedMap.to,
                        asOf      : parsedMap.asOf,
                        balance   : parsedMap.balance,
                        page      : parsedMap.page,
                        transaction: tx
                ]
            }
        }
        if (parsedMap.data instanceof List) return (List) parsedMap.data
        if (parsedMap.results instanceof List) return (List) parsedMap.results
        if (parsedMap.items instanceof List) return (List) parsedMap.items
        def firstListValue = parsedMap.values().find { it instanceof List }
        if (firstListValue instanceof List) return (List) firstListValue
        return [parsedMap]
    }
    return [[value: parsed]]
}

List rawRecords = extractRecords(parsedBody)
records = rawRecords.collect { Object rec ->
    [record: rec]
}
recordCount = records.size()
processingWarnings = InventoryWarningSupport.normalizeWarningTexts(warningList)

logger.info("NetSuite restlet success config={} itemId={} locationId={} records={}",
        nsConfigId, itemIdStr, locationIdStr, recordCount)
