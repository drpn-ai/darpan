package darpan.idindex

import java.util.regex.Pattern

/**
 * DAR-BE-374: the one normalisation the ID index applies when it writes a value and when it searches for one, so every
 * spelling a person pastes ("#GOR123", "gor123", "gid://shopify/Order/123") finds the same row.
 */
class IdNormalizer {

    static final int MAX_LENGTH = 255
    private static final Pattern SHOPIFY_GID = Pattern.compile('(?i)^gid://shopify/[A-Za-z]+/([^?/]+)(\\?.*)?$')

    /** Trimmed, one leading # dropped, a Shopify gid reduced to its id, uppercased; null for blank or longer than 255. */
    static String normalize(Object raw) {
        if (raw == null) return null
        String v = raw.toString().trim()
        def gid = SHOPIFY_GID.matcher(v)
        if (gid.matches()) v = gid.group(1)
        if (v.startsWith("#")) v = v.substring(1).trim()
        if (!v || v.length() > MAX_LENGTH) return null
        return v.toUpperCase(Locale.ROOT)
    }
}
