package darpan.reconciliation.inventory

class InventoryWarningSupport {
    static List<String> normalizeWarningTexts(Object rawWarnings) {
        if (rawWarnings == null) return []

        Collection warningValues = (rawWarnings instanceof Collection) ? (Collection) rawWarnings : [rawWarnings]
        LinkedHashSet<String> normalized = new LinkedHashSet<>()

        warningValues.each { Object warningValue ->
            String warningText = extractWarningText(warningValue)
            if (warningText) normalized.add(warningText)
        }

        return new ArrayList<>(normalized)
    }

    protected static String extractWarningText(Object warningValue) {
        if (warningValue == null) return null
        if (warningValue instanceof Map) return normalizeText(((Map) warningValue).get("warningMessage"))
        return normalizeText(warningValue)
    }

    protected static String normalizeText(Object value) {
        String raw = value?.toString()?.trim()
        return raw ?: null
    }
}
