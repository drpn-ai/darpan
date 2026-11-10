package darpan.facade.common

/**
 * DAR-BE-080. A connection's stored secret (password, token, key) is reused on save only while the place it is sent to
 * is unchanged. Otherwise anyone who may edit the connection (a peer of a shared config, too) could point it at their
 * own server and have Darpan send them the secret on the next test or run. The save paths call changedFields and refuse
 * with refusal(...) when it is not empty and no new secret was given.
 */
class SecretCarryOver {
    /** The fields whose value moved, compared without case, surrounding space, a trailing slash, or text-vs-number. */
    static List<String> changedFields(def stored, Map incoming, List<String> fields) {
        if (stored == null) return []
        return fields.findAll { String f -> norm(stored[f]) != norm(incoming[f]) }
    }

    static String refusal(List<String> changed, String secretLabel) {
        String what = changed.size() == 1 ? changed[0] : changed[0..-2].join(", ") + " and " + changed[-1]
        return "Enter the ${secretLabel} again: the ${what} changed, so the saved ${secretLabel} is not sent to the new address.".toString()
    }

    private static String norm(def value) {
        String s = value == null ? "" : value.toString().trim().toLowerCase()
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1)
        return s
    }
}
