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

    /**
     * Badges that earn a GIF (first earn per automation only) and every side quest. The keys are the stored badge and
     * quest keys; titles and files follow the DAR-BE-138 astronomy names. Source: tools/streak-gifs in the wrapper repo.
     */
    static final Map<String, Map<String, String>> CATALOG = [
            MONTH_DOZE           : [file: "darpan-lunar-lap.gif", title: "Lunar Lap",
                                    altText: "The Moon runs a lap round Tara's head through every phase, then parks and winks. Milestone: 30 days clean, Lunar Lap."],
            QUARTER_CLEAN        : [file: "darpan-solstice.gif", title: "Solstice",
                                    altText: "The sun sets and rises again, Tara squints, and a pair of sunglasses lowers onto her face. Milestone: 90 days clean, Solstice."],
            HALF_YEAR_HIBERNATION: [file: "darpan-far-side.gif", title: "Far Side of the Sun",
                                    altText: "Tara, in a space helmet, orbits halfway round the Sun and peeks out from the far side. Milestone: 180 days clean, Far Side of the Sun."],
            GLORIOUSLY_BORING    : [file: "darpan-full-orbit.gif", title: "Full Orbit",
                                    altText: "Tara races one full lap round the Sun, crosses a chequered flag and gets a crown. Milestone: 365 days clean, Full Orbit."],
            BIG_HAUL             : [file: "darpan-meteor-shower.gif", title: "Meteor Shower",
                                    altText: "Shooting stars streak past, Tara makes a wish, and the last one lands on her head as a star. Side quest: Meteor Shower."],
            BLACK_FRIDAY_SURVIVOR: [file: "darpan-black-hole.gif", title: "Black Hole Survivor",
                                    altText: "A black hole swallows a stream of boxes and stretches Tara toward it, then pops, and a SURVIVED stamp slams in. Side quest: Black Hole Survivor."],
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
