package darpan.reconciliation.notification

/**
 * DAR-BE-108. Turns a run notification that carries a streak celebration into each chat provider's
 * own message shape.
 *
 * <p>A notification payload stays provider-neutral until delivery: {@code [text: ..., celebration:
 * [imageUrl, altText, title]]}. One payload is built per run and handed to every destination, so the
 * provider split has to happen here, per destination, rather than where the payload is built.</p>
 *
 * <p>The {@code celebration} key itself is never posted. Google Chat validates the JSON body and
 * rejects a field it does not know with HTTP 400, which would cost the run its whole message, not
 * just the picture. A payload without a celebration is returned untouched, so every message Darpan
 * sends today goes out byte-for-byte as before.</p>
 *
 * <p>The GIF is the same file for every tenant: it shows the badge or quest, never the tenant, check
 * or day count, which stay in the text. Files are served by darpan-ui as public static assets under
 * {@code /streaks/}, from the same host the run links already point at.</p>
 */
class StreakCelebrationSupport {

    static final String CELEBRATION_KEY = "celebration"
    /** celebration.kind: a badge GIF is capped at one per space per day; a quest GIF is not. */
    static final String KIND_BADGE = "badge"
    static final String KIND_QUEST = "quest"
    static final String GIF_PATH = "/streaks/"

    /** Badges that earn a GIF (first earn per automation only) and every side quest. */
    static final Map<String, Map<String, String>> CATALOG = [
            MONTH_DOZE           : [file: "darpan-doze.gif", title: "Month Doze",
                                    altText: "Tara pulls her sleep mask down and dozes off. Milestone: 30 days clean, Month Doze."],
            QUARTER_CLEAN        : [file: "darpan-quarter.gif", title: "Quarter Clean",
                                    altText: "Tara's sunglasses pop off and drop back on as confetti falls. Milestone: 90 days clean, Quarter Clean."],
            HALF_YEAR_HIBERNATION: [file: "darpan-hibernation.gif", title: "Half-Year Hibernation",
                                    altText: "Tara curls up and hibernates under falling snow. Milestone: 180 days clean, Half-Year Hibernation."],
            GLORIOUSLY_BORING    : [file: "darpan-boring.gif", title: "Gloriously Boring",
                                    altText: "Tara's crown bounces and sparkles under a burst of confetti. Milestone: 365 days clean, Gloriously Boring."],
            BIG_HAUL             : [file: "darpan-haul.gif", title: "Big Haul",
                                    altText: "Parcels drop into a pile in front of Tara until it tops out. Side quest: Big Haul."],
            BLACK_FRIDAY_SURVIVOR: [file: "darpan-bfcm.gif", title: "Black Friday Survivor",
                                    altText: "Boxes rain down while Tara dodges in a hard hat, then a SURVIVED stamp slams in. Side quest: Black Friday Survivor."],
    ].asImmutable()

    /**
     * The celebration block for a catalog key, or null when there is nothing to show: an unknown key,
     * or no app base URL configured. With no base URL the run link is already suppressed; a GIF with
     * a relative URL would fail the same way, so it drops rather than posting a broken image.
     */
    static Map<String, Object> celebration(String appBaseUrl, String catalogKey) {
        Map<String, String> entry = catalogKey ? CATALOG.get(catalogKey) : null
        String base = ((appBaseUrl)?.toString()?.trim())?.replaceAll(/\/+$/, "")
        if (entry == null || !base) return null
        return [imageUrl: base + GIF_PATH + entry.file, altText: entry.altText, title: entry.title]
    }

    static boolean hasCelebration(Map<String, Object> payload) {
        Map celebration = payload?.get(CELEBRATION_KEY) as Map
        return celebration != null && ((celebration.imageUrl)?.toString()?.trim())
    }

    /** Google Chat: the text stays, the picture goes in a cardsV2 image widget. */
    static Map<String, Object> forGoogleChat(Map<String, Object> payload) {
        if (!hasCelebration(payload)) return stripped(payload)
        Map celebration = payload.get(CELEBRATION_KEY) as Map
        Map<String, Object> shaped = stripped(payload)
        shaped.cardsV2 = [[
                cardId: "darpan-streak",
                card  : [sections: [[widgets: [[image: [
                        imageUrl: celebration.imageUrl,
                        altText : celebration.altText,
                ]]]]]],
        ]]
        return shaped
    }

    /** Slack incoming webhook: JSON body, so blocks go in as a list. */
    static Map<String, Object> forSlackWebhook(Map<String, Object> payload) {
        if (!hasCelebration(payload)) return stripped(payload)
        Map<String, Object> shaped = stripped(payload)
        shaped.blocks = slackBlocks(payload)
        return shaped
    }

    /**
     * Slack blocks for the message, or null when there is no celebration. With blocks present Slack
     * shows the blocks, not {@code text}, so the text is repeated as the first block; {@code text}
     * still travels as the notification preview.
     */
    static List<Map<String, Object>> slackBlocks(Map<String, Object> payload) {
        if (!hasCelebration(payload)) return null
        Map celebration = payload.get(CELEBRATION_KEY) as Map
        String text = ((payload.text)?.toString()) ?: ""
        List<Map<String, Object>> blocks = []
        if (text) blocks << [type: "section", text: [type: "mrkdwn", text: text]]
        Map<String, Object> image = [type: "image", image_url: celebration.imageUrl, alt_text: celebration.altText]
        if (((celebration.title)?.toString()?.trim())) image.title = [type: "plain_text", text: celebration.title]
        blocks << image
        return blocks
    }

    static boolean isBadgeGif(Map<String, Object> payload) {
        return hasCelebration(payload) && (payload.get(CELEBRATION_KEY) as Map).kind == KIND_BADGE
    }

    /** The payload with its picture dropped and its text kept. */
    static Map<String, Object> stripped(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey(CELEBRATION_KEY)) return payload
        Map<String, Object> copy = new LinkedHashMap<>(payload)
        copy.remove(CELEBRATION_KEY)
        return copy
    }
}
