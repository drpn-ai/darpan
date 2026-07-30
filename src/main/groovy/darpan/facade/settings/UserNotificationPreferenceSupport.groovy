package darpan.facade.settings

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class UserNotificationPreferenceSupport {
    static final String PREFERENCE_KEY = "darpan.notification.defaultChatSpaceId"

    static String getDefaultChatSpaceId(def ec, String tenantId) {
        if (!tenantId) return null
        return ((parse(ec?.user?.getPreference(PREFERENCE_KEY))[tenantId])?.toString()?.trim()) ?: null
    }

    static void saveDefaultChatSpaceId(def ec, String tenantId, String chatSpaceId) {
        if (!tenantId) return
        Map preferenceMap = parse(ec?.user?.getPreference(PREFERENCE_KEY))
        String chatSpaceIdValue = ((chatSpaceId)?.toString()?.trim())
        if (chatSpaceIdValue) { preferenceMap[tenantId] = chatSpaceIdValue } else { preferenceMap.remove(tenantId) }
        ec.user.setPreference(PREFERENCE_KEY, JsonOutput.toJson(preferenceMap))
    }

    private static Map parse(Object rawPreference) {
        String text = ((rawPreference)?.toString()?.trim())
        if (!text) return [:]
        try {
            Object parsed = new JsonSlurper().parseText(text)
            return parsed instanceof Map ? (Map) parsed : [:]
        } catch (Exception ignored) { return [:] }
    }
}
