package darpan.reconciliation.notification

/**
 * DAR-BE-121. The chat lines for a stored streak (DAR-BE-107): the badge an automation just earned, and the streak line
 * once a streak runs past the 20-run lookback RunNotificationVoice can see. Pure, like RunNotificationVoice, and dealt
 * through its pickLine so every pool spends all its entries before one comes round again.
 *
 * AutomationStreakSupport.notificationFacts reads the stored streak and the badge or quest this run's slot earned, and a
 * badge's line rides on the run message that crossed the threshold (DAR-BE-108), in place of the streak line.
 *
 * Badges belong to an automation and count clean DAYS (DAR-UI-059). High Five and Fifteen Days of Fame are earned once
 * per automation, ever; from Month Doze up a later streak earns the badge again.
 */
class StreakMilestoneVoice {

    /** The ladder, lowest first. Keys match StreakCelebrationSupport.CATALOG where a badge has a GIF. */
    static final List<Map<String, Object>> BADGE_LADDER = [
            [key: "HIGH_FIVE", title: "High Five", days: 5, reEarnable: false],
            [key: "FIFTEEN_DAYS_OF_FAME", title: "Fifteen Days of Fame", days: 15, reEarnable: false],
            [key: "MONTH_DOZE", title: "Month Doze", days: 30, reEarnable: true],
            [key: "DOUBLE_DOZE", title: "Double Doze", days: 60, reEarnable: true],
            [key: "QUARTER_CLEAN", title: "Quarter Clean", days: 90, reEarnable: true],
            [key: "HALF_YEAR_HIBERNATION", title: "Half-Year Hibernation", days: 180, reEarnable: true],
            [key: "GLORIOUSLY_BORING", title: "Gloriously Boring", days: 365, reEarnable: true],
    ].asImmutable()

    /** Up to here RunNotificationVoice's own streak lines speak; past it, the lines below. */
    static final int LONG_STREAK_FROM = 21

    /**
     * The line on the run message whose run earned a badge, the first time this automation earns it. Each badge has its
     * own pool so the line can play on the badge (the doze, the hibernation, the crown) and state what it took; {badge}
     * is the title, filled in here so no line can misspell it. No line puts an article before {badge}: "a Fifteen Days
     * of Fame" and "another Gloriously Boring" do not read.
     */
    static final Map<String, List<String>> FIRST_EARN_LINES = [
            HIGH_FIVE: [
                    "Five matched days in a row, and that earns this automation its {badge}.",
                    "{badge} unlocked: five straight days with nothing out of line.",
                    "That's five days running without a single difference, which is worth a {badge}.",
                    "Five consecutive days of matched records puts {badge} on the board.",
                    "The automation just picked up its {badge} for five days in a row with nothing to chase.",
                    "Badge earned: {badge}, for five days where every run lined up.",
                    "Five days, every run matched, and the first badge goes up \u2014 {badge}.",
                    "Day five in a row lined up, so {badge} is now on this automation's record.",
                    "A {badge} for the automation, five days into a streak with no exceptions.",
                    "Five straight days and the records have not drifted once, so here is {badge}.",
                    "{badge} is in, after five days of both sides saying the same thing.",
                    "Five for five on matched days, which is exactly what {badge} asks for.",
            ].asImmutable(),
            FIFTEEN_DAYS_OF_FAME: [
                    "Fifteen days in a row without a difference, and the automation gets its {badge}.",
                    "{badge} earned, and it took fifteen straight days of every run lining up.",
                    "Fifteen consecutive matched days, and this automation's {badge} has officially begun.",
                    "The automation is briefly famous: fifteen straight matched days and {badge} to show for it.",
                    "Fifteen days without an exception puts {badge} on this automation's record.",
                    "Badge earned for fifteen consecutive matched days \u2014 {badge}, and the records are not letting it go to their heads.",
                    "Fifteen days of nothing to report, which is the only kind of fame that earns {badge}.",
                    "Every run for fifteen days lined up, so {badge} is now official.",
                    "The automation takes {badge} for fifteen days straight with nothing to chase.",
                    "Fifteen in a row, no differences, and {badge} is the quiet reward.",
                    "{badge} is in: fifteen days and the data has not missed a cue.",
                    "Half a month of matched runs, fifteen days exactly, and that is {badge} earned.",
            ].asImmutable(),
            MONTH_DOZE: [
                    "Thirty days in a row matched, and the automation has earned its {badge}.",
                    "{badge} arrives after thirty straight days with nothing to wake anyone for.",
                    "Thirty consecutive matched days, which is long enough for the sleep mask to come out \u2014 {badge}.",
                    "Thirty days of runs that all lined up adds up to a month, and a month adds up to {badge}.",
                    "Thirty days without a single difference, so {badge} it is, and nobody needs to set an alarm.",
                    "The records have lined up for thirty days running, and the automation can take its {badge} lying down.",
                    "Badge earned for thirty matched days in a row \u2014 {badge}, best enjoyed with eyes closed.",
                    "Thirty straight days of nothing to chase is exactly how a {badge} is earned.",
                    "Thirty days, every run matched, and the streak is now restful enough to call {badge}.",
                    "A full thirty days of matched runs puts {badge} on this automation's record, and it is safe to doze.",
                    "{badge} is in after thirty days of data so steady it is practically a lullaby.",
                    "The data has not stirred once in thirty days, so here is {badge}.",
            ].asImmutable(),
            DOUBLE_DOZE: [
                    "Sixty days of matched runs back to back, and that is a {badge}.",
                    "{badge} earned: sixty straight days and the data has not so much as rolled over.",
                    "Sixty consecutive matched days means the first nap went so well it got a sequel \u2014 {badge}.",
                    "Two months of runs that all lined up, sixty days straight, earns a {badge}.",
                    "Sixty days without a single difference, so the sleep mask stays on for {badge}.",
                    "The records have lined up for sixty days running, which calls for {badge} and a longer lie-in.",
                    "Badge earned for sixty matched days in a row \u2014 {badge}, twice as restful as the last one.",
                    "Sixty straight days of nothing to chase, and {badge} is what that looks like on the record.",
                    "Every run for sixty days has matched, so the automation has dozed its way into {badge}.",
                    "A second month of uninterrupted matching brings the streak to sixty days and the badge to {badge}.",
                    "{badge} is in after sixty days of data quiet enough to sleep through.",
                    "Sixty days and not one exception, so the automation turns over and collects {badge}.",
            ].asImmutable(),
            QUARTER_CLEAN: [
                    "Ninety days in a row matched, and the sunglasses come out for {badge}.",
                    "{badge} goes to this automation for ninety straight days, a full quarter, without a single exception.",
                    "A whole quarter of matched runs, ninety days without a break, earns this automation {badge}.",
                    "Ninety consecutive days lined up, which is worth a little confetti and a {badge}.",
                    "Ninety days of matched records end to end, so {badge} goes up with the confetti.",
                    "Badge earned for ninety days in a row \u2014 {badge}, with nothing from the whole stretch needing a second look.",
                    "Three months of runs without a single difference adds up to ninety days and one {badge}.",
                    "Ninety straight days with nothing to chase is the kind of quarter that earns {badge}.",
                    "Every run for ninety days has lined up, so put the sunglasses on for {badge}.",
                    "The streak just crossed ninety matched days, and {badge} arrives with confetti.",
                    "{badge} is in after ninety days of records that never once drifted apart.",
                    "Ninety days, one quarter, zero exceptions, and a {badge} to show for it.",
            ].asImmutable(),
            HALF_YEAR_HIBERNATION: [
                    "A hundred and eighty matched days in a row, and the automation settles in under the snow for {badge}.",
                    "{badge} begins now, after a hundred and eighty straight days of nothing worth waking for.",
                    "Half a year of matched runs, a hundred and eighty days without a break, earns {badge}.",
                    "Six months, 180 days straight with every run lined up, so the snow can fall on {badge}.",
                    "The streak reached 180 matched days, which is long enough to hibernate through and call it {badge}.",
                    "Badge earned for 180 days in a row \u2014 {badge}, and the data stayed put through one season and the next.",
                    "A hundred and eighty days without a single difference is the kind of quiet that earns {badge}.",
                    "With 180 matched days behind it, the automation curls up for the long sleep that is {badge}.",
                    "Six months of nothing to chase, 180 days end to end, and {badge} is now on the record.",
                    "The records have lined up for 180 consecutive days, which is a whole winter's sleep and a {badge}.",
                    "{badge} is in, and 180 days of steady data means nothing will wake it before spring.",
                    "Half a year, 180 days, and not a single exception, so {badge} goes on the record and the automation does not stir.",
            ].asImmutable(),
            GLORIOUSLY_BORING: [
                    "The crown goes on for {badge}, three hundred and sixty-five matched days in a row.",
                    "{badge} is official: a full year, 365 straight days, and not one thing out of line.",
                    "It took 365 days without a break, every run lined up, to earn this automation {badge}.",
                    "Runs have matched for 365 consecutive days, which is as boring as it gets and exactly why this is {badge}.",
                    "One full year, 365 days without a single difference, so the confetti is out and the crown is on \u2014 {badge}.",
                    "Badge earned for 365 days in a row \u2014 {badge}, the highest honour available to something that never makes the news.",
                    "Every one of 365 days matched, which is the dullest possible achievement and the most valuable, so here is {badge}.",
                    "The streak has reached 365 days, and {badge} is the crown for a year of nothing to chase.",
                    "Twelve months of runs with zero exceptions, all 365 days of them, crowns this automation with {badge}.",
                    "Three hundred and sixty-five days of data lining up without being asked twice has earned {badge}.",
                    "{badge} is in: one year, all 365 days matched, and not one surprise in the whole stretch.",
                    "Nothing has drifted in 365 days, which makes this automation the proud, unremarkable holder of {badge}.",
            ].asImmutable(),
    ].asImmutable()

    /** A badge from Month Doze up, earned again on a later streak; {ordinal} is "2nd", "3rd" and on. No day count: shared by every badge. */
    static final List<String> RE_EARN_LINES = [
            "{badge} is back, earned for the {ordinal} time on this automation.",
            "That makes {badge} for the {ordinal} time, and the streak behind it was just as quiet as the first.",
            "The automation has earned {badge} for the {ordinal} time, which is starting to look like a habit.",
            "New streak, same badge: {badge}, the {ordinal} on the shelf.",
            "{badge}, {ordinal} time around, and the data did it the same uneventful way.",
            "This automation now holds {badge} for the {ordinal} time.",
            "For the {ordinal} time, this automation has lined up long enough to earn {badge}.",
            "{badge} again, the {ordinal} on this automation, and still nothing to chase.",
            "{badge} for the {ordinal} time, because the records kept doing the boring thing.",
            "The streak earned {badge} once more, making this the {ordinal} time.",
            "Back on the board: {badge}, earned for the {ordinal} time.",
            "Here is {badge} for the {ordinal} time, and it has not gotten any less dull to earn.",
            "Same badge, new streak: {badge} for the {ordinal} time.",
            "Earned the way the others were, by nothing happening: {badge}, for the {ordinal} time.",
            "Once more the runs lined up long enough, and {badge} lands for the {ordinal} time.",
            "{badge} returns for the {ordinal} time, with the same unbroken run of matches behind it.",
            "This is the {ordinal} time the automation has earned {badge}, and the method has not changed.",
            "On the record again: {badge}, the {ordinal} for this automation.",
            "Repeat performance: {badge}, for the {ordinal} time.",
            "The data has done it again, and {badge} goes on the record for the {ordinal} time.",
            "{badge} has now been earned for the {ordinal} time, which is the opposite of news.",
            "The streak that led here never wobbled, which is how {badge} came round for the {ordinal} time.",
            "Nothing broke the streak, so {badge} is in for the {ordinal} time.",
            "One more for the collection: {badge}, the {ordinal} time on this automation.",
            "The automation picked up {badge} for the {ordinal} time without anyone lifting a finger.",
            "The streak reached {badge} territory for the {ordinal} time, and the records did not even seem to notice.",
            "{badge} goes up for the {ordinal} time, and the shelf is getting crowded.",
            "A familiar badge, a bigger number: {badge}, the {ordinal} time.",
            "Same quiet result, same reward, and {badge} counts its {ordinal} win on this automation.",
            "This is the {ordinal} streak on this automation to reach {badge}, and it got there without a single exception.",
    ].asImmutable()

    /**
     * A stored streak past the lookback, counted in DAYS (DAR-BE-107), so no line says "runs". Read for any count from 21
     * into the thousands; no badge is named, so these still read once every badge is earned.
     */
    static final List<String> LONG_STREAK_LINES = [
            "Nothing has slipped across {n} days.",
            "That makes {n} days without a gap.",
            "{n} unbroken days now.",
            "The streak is {n} days long.",
            "Another day on the pile, which now stands at {n}.",
            "Day {n} looks exactly like the day before it.",
            "{n} days straight, and the data has not put a foot wrong.",
            "The calendar says {n} days, and every one of them lined up.",
            "Counting days: {n}.",
            "{n} days in a row, and the records keep turning up where they should.",
            "Still nothing to chase, {n} days on.",
            "Not a gap in {n} days.",
            "{n} days, and the exceptions queue has forgotten what it is for.",
            "The streak has now lasted {n} days.",
            "{n} consecutive days with nothing left over.",
            "Put day {n} on the board.",
            "The automation has stuck to the script for {n} days.",
            "For {n} days now, both sides have told the same story.",
            "Another day, and the tally reaches {n}.",
            "{n} days without anything to tidy up.",
            "The streak counter reads {n} days.",
            "Not one loose end in {n} days.",
            "{n} days in, and the routine has not missed a beat.",
            "The unbroken stretch is {n} days.",
            "{n} days of the data minding its own business.",
            "Both systems are still in step, {n} days along.",
            "That's {n} days in a row with nothing to report but the count.",
            "Stretch so far: {n} days.",
            "{n} days back to back, and the pattern is not budging.",
            "The records have kept their appointments for {n} days.",
            "Into the book it goes, {n} days and all of them alike.",
            "{n} days of the same tidy outcome.",
            "The streak rolls on to {n} days.",
            "Every day for {n} days, the totals have lined up.",
            "{n} days straight, and the automation is showing no interest in drama.",
            "Steady as ever on day {n}.",
            "This is day {n} without a stray record.",
            "{n} days, and the diff has stayed empty throughout.",
            "The habit is {n} days old.",
            "On day {n}, the answer is the same as ever.",
            "{n} days on end, and nothing has needed a second look.",
            "The quiet has now held for {n} days.",
            "The data continues to behave after {n} days.",
            "A {n}-day streak, and the follow-up list is still blank.",
            "{n} days, and not one record has gone walkabout.",
            "The automation has kept this up for {n} days.",
            "One more link in a chain that is now {n} days long.",
            "{n} days, and the reconciliation has turned into a formality.",
            "Still going, {n} days in.",
            "Nothing has needed fixing for {n} days.",
            "{n} straight days with the alerts channel left alone.",
            "The reconciler has very little to say after {n} days.",
            "Across {n} days, neither side has drifted from the other.",
            "{n} days of nothing out of place.",
            "The count of uneventful days is {n}.",
            "Filed alongside the rest: day {n}.",
            "{n} days, and the books still balance.",
            "A streak of {n} days, held without fuss.",
            "The automation is {n} days into a very dull success.",
            "{n} straight days, and the data is starting to look rehearsed.",
            "Even the awkward records have behaved for {n} days.",
            "Over {n} days, the comparison has found nothing to point at.",
            "The streak has put on another day and sits at {n}.",
            "{n} days of being comfortably uneventful.",
            "Not a single exception in {n} days.",
            "This quiet patch is {n} days deep.",
            "{n} days, and the outcome has never varied.",
            "No stragglers on day {n}.",
            "{n} days, and the day count is the only thing that changes.",
            "That is {n} days of matching totals.",
            "Every total has checked out for {n} days.",
            "The data has shown up on time for {n} days.",
            "{n} days without the automation raising an eyebrow.",
            "Another uneventful day brings it to {n}.",
            "The two ledgers still read like copies after {n} days.",
            "{n} unbroken days, and the follow-up pile has not grown.",
            "Steady for {n} days now.",
            "The streak is {n} days old and not slowing.",
            "Nothing to see on day {n}.",
            "{n} days, and the data has given nobody a reason to look.",
            "Since this streak began, {n} days have gone by without a gap.",
            "Logged quietly: day {n}.",
            "{n} days, and the pipeline has yet to complain.",
            "Gapless days to date: {n}.",
            "Repetition has never looked so good: {n} days.",
            "{n} days, and every record has found its partner.",
            "Calm answers, {n} days in a row.",
            "The records have stayed put for {n} days.",
            "The line is unbroken at {n} days.",
            "For {n} days, the automation has had the easiest job in the building.",
            "The totals are square once more on day {n}.",
            "Nothing has asked for attention in {n} days.",
            "This makes day {n} of the streak.",
            "A {n}-day stretch with zero follow-ups.",
            "The systems are getting along famously, {n} days on.",
            "{n} days without a blip on either side.",
            "Another square on the wall chart, and that makes {n} days.",
            "The automation is still unimpressed by its own record of {n} days.",
            "{n} days, and the reports are getting very short.",
            "Uneventful for {n} days straight.",
            "The gaps column has stayed empty for {n} days.",
            "{n} days without a discrepancy worth the name.",
            "Another day without incident; that is {n}.",
            "The data has nothing to confess on day {n}.",
            "{n} days, and the routine looks permanent.",
            "The streak is wearing well at {n} days.",
            "{n} days strong.",
            "No news on day {n}, which is the good kind.",
            "Hitch-free days: {n}.",
            "{n} days, and the matching has become muscle memory.",
    ].asImmutable()

    /** The same, naming the next badge ({next}) and the distance to it ({left}, which carries its own "day"/"days"). */
    static final List<String> NEXT_BADGE_LINES = [
            "Day {n}, and {next} is {left} away.",
            "{left} to go until {next}, with {n} days already banked.",
            "{n} days in, and {next} is {left} off.",
            "With {n} days done, the streak falls {left} short of {next}.",
            "{next} is {left} out, and the streak sits at {n} days.",
            "{next} unlocks in {left}; this is day {n}.",
            "{n} unbroken days, with {next} {left} down the road.",
            "Hold on for {left} more and the {n}-day streak earns {next}.",
            "At {n} days, the streak has {next} in its sights, {left} ahead.",
            "{next} is waiting {left} from here, and the streak is at {n} days.",
            "{n} days down, {left} until {next}.",
            "The {n}-day streak is {left} from {next}.",
            "Between this streak and {next}: {left}, with {n} days in hand.",
            "Next stop {next}, {left} from day {n}.",
            "{next} comes into view in {left}, and the count is {n} days.",
            "{n} days so far, and {next} lands in {left}.",
            "{next} needs {left} more, with day {n} banked.",
            "With {n} days behind it, the streak has {left} to go for {next}.",
            "{left} more without a gap earns {next}, on top of {n} days already.",
            "The automation is {n} days in and {left} shy of {next}.",
            "{next} is on the horizon, {left} out, after {n} days.",
            "{n} days logged; {next} arrives in {left}.",
            "The {next} badge is {left} off, and day {n} did nothing to slow it down.",
            "That's {n} days, and the countdown to {next} reads {left}.",
            "Badge watch: {next} in {left}, streak at {n} days.",
            "{next} is {left} ahead of this {n}-day streak.",
            "Day {n} keeps the road to {next} open, {left} to go.",
            "Standing at {n} days, the streak needs {left} for {next}.",
            "{left} until {next}, and the data has behaved for {n} days already.",
            "{n} days on the board and {left} left before {next}.",
            "{next} is due in {left} if the {n}-day streak holds.",
            "The badge after this is {next}, {left} from day {n}.",
            "{left} to {next}, {n} days so far.",
            "{n} days in the bag, with {next} {left} further on.",
            "This is day {n}, and {next} opens up after {left} more.",
            "On day {n}, {next} sits {left} ahead.",
            "The streak has {n} days in it and needs {left} to reach {next}.",
            "Day {n} is done, {next} is {left} out, and nothing needs a look.",
            "The count is {n} days; {next} is a further {left}.",
            "{next} is pencilled in for {left} from now, at {n} days and holding.",
            "Day {n}, and the badge shelf has a space ready for {next}, {left} out.",
            "{n} days, and the next badge, {next}, is {left} away.",
            "{next} is the next mark, {left} beyond day {n}.",
            "Day {n}; {left} more and {next} is official.",
            "At {n} days, the next thing to aim for is {next}, {left} out.",
            "{n} days without a gap leaves {left} until {next}.",
            "The road to {next} is {left} long, and {n} days are already behind it.",
            "Day {n}, and the automation can see {next} from here, {left} away.",
            "{left} out from {next}, and the streak is {n} days old.",
            "{n} days of quiet so far, and {left} of quiet would bring {next}.",
            "If nothing changes for {left}, the {n}-day streak collects {next}.",
            "Day {n} goes in the books, and {next} is {left} further.",
            "{n} straight days; {next} waits {left} ahead.",
            "The only gap left is the {left} to {next}, after {n} days without one.",
            "{n} days, nothing to fix, and {left} to {next}.",
            "Day {n}; the next milestone, {next}, is {left} out.",
            "{next} is {left} from here, provided the data stays this dull past day {n}.",
            "The data has kept quiet for {n} days and has {left} to go for {next}.",
            "{n} days, and {next} is {left} away, so nobody touch anything.",
            "Give it {left} and {next} belongs to this {n}-day streak.",
            "The {next} badge sits {left} past day {n}.",
            "{n} days, and the march to {next} has {left} left in it.",
            "The streak needs {left} more for {next}, having done {n} days already.",
            "{left} from {next}, at {n} days.",
            "Another day lands, making {n}, and {next} is {left} off.",
            "{n} days deep, {left} shy of {next}.",
            "The {n}-day streak is on course for {next} in {left}.",
            "{n} days without a hiccup, {next} in {left}.",
            "{n} days on, {next} at {left} and closing.",
            "Next up is {next}, {left} away at day {n}.",
            "{left} until {next}; the streak is {n} days and holding steady.",
            "Day {n} of quiet, {left} until {next}.",
            "The automation has {n} days behind it and {next} {left} ahead.",
            "{next} is {left} off, and the data has given no reason to doubt it after {n} days.",
            "{n} days so far, {next} in {left}, nothing else to report.",
            "{left} more like these and {next} is in hand, {n} days in.",
            "Day {n}, with {left} still on the road to {next}.",
            "Mark day {n}; {next} is {left} out.",
            "The streak, at {n} days, is heading for {next}, {left} away.",
            "{n} unbroken days, and {next} is on the way in {left}.",
            "Day {n}, and {next} is officially on the calendar, {left} out.",
            "{next} follows in {left} if nothing changes, and nothing has for {n} days.",
            "The records have stayed in step for {n} days, and {next} is {left} off.",
            "{next} is {left} out, and the last {n} days suggest it will turn up on schedule.",
            "On day {n}, the gap to {next} is {left}.",
            "{left} short of {next}, the streak reaches {n} days.",
            "{left} for {next}, and day {n} is done.",
            "Every day so far has counted, {n} of them, and {next} needs {left} more.",
            "Patience, {next} is {left} away and the streak is {n} days in.",
            "{n} days, and {next} is the obvious next stop, {left} off.",
    ].asImmutable()

    /**
     * DAR-BE-108. The line for a side quest just found, once per tenant. Big Haul rides on the run that found it;
     * Black Friday Survivor is its own post to the tenant's streaks space. {records} is the run's record count with
     * thousands separators.
     */
    static final Map<String, List<String>> QUEST_LINES = [
            BIG_HAUL: [
                    "Side quest found: Big Haul. {records} records in one run, and every one of them lined up.",
                    "That was {records} records without a single difference, which counts as a Big Haul. Side quest found.",
                    "Big Haul: {records} records checked in one go and nothing to chase. Side quest found.",
                    "Side quest found: Big Haul, for carrying {records} records through one run without dropping any.",
                    "{records} records, one run, zero differences. That is the Big Haul side quest.",
                    "One run moved {records} records and every one lined up, so Big Haul is found.",
            ].asImmutable(),
            BLACK_FRIDAY_SURVIVOR: [
                    "Side quest found: Black Friday Survivor. Every automation lined up from Black Friday to Cyber Monday.",
                    "Black Friday through Cyber Monday, and not one automation found a difference. Side quest found: Black Friday Survivor.",
                    "The busiest weekend of the year came and went, and every automation stayed clean. Black Friday Survivor is found.",
                    "Every automation made it through Black Friday to Cyber Monday without a difference, which earns Black Friday Survivor.",
                    "Side quest found: Black Friday Survivor. Four of the loudest days of the year, and the records stayed quiet.",
                    "Black Friday Survivor: every automation, every day from Friday to Cyber Monday, all lined up.",
            ].asImmutable(),
    ].asImmutable()

    /** The line for a side quest just found, or null for a quest with no lines. */
    static String questLine(String key, long records) {
        String template = key ? RunNotificationVoice.pickLine(QUEST_LINES.get(key), "quest|" + key) : null
        return template?.replace("{records}", String.format(Locale.US, "%,d", records))
    }

    /** The plain pool and the next-badge pool dealt as one, so a long streak names its next badge about half the time. */
    static final List<String> LONG_WITH_NEXT_LINES = (LONG_STREAK_LINES + NEXT_BADGE_LINES).asImmutable()

    static Map<String, Object> badge(String key) {
        return BADGE_LADDER.find { it.key == key }
    }

    /** The first badge a streak of this many days has not reached yet, or null past the top of the ladder. */
    static Map<String, Object> nextBadge(int days) {
        return BADGE_LADDER.find { ((Integer) it.days) > days }
    }

    /** "1 day", "12 days": the distance with its unit, so no line has to guess the plural. */
    static String dayCount(int days) {
        return days == 1 ? "1 day" : "${days} days".toString()
    }

    /** 2nd, 3rd, 4th, 11th, 21st, 112th. */
    static String ordinal(int n) {
        int lastTwo = n % 100
        String suffix = (lastTwo >= 11 && lastTwo <= 13) ? "th" :
                [1: "st", 2: "nd", 3: "rd"].get(n % 10) ?: "th"
        return "${n}${suffix}".toString()
    }

    /**
     * The line for a badge just earned. timesEarned counts this earn, so 1 is the first. A once-only badge earned
     * "again" says nothing: the caller should not have asked, and silence beats a false celebration.
     */
    static String badgeLine(String key, int timesEarned) {
        Map<String, Object> earned = badge(key)
        if (earned == null || timesEarned < 1) return null
        String template
        if (timesEarned == 1) {
            template = RunNotificationVoice.pickLine(FIRST_EARN_LINES.get(key), "badgeFirst|" + key)
        } else {
            if (!earned.reEarnable) return null
            template = RunNotificationVoice.pickLine(RE_EARN_LINES, "badgeAgain")
                    ?.replace("{ordinal}", ordinal(timesEarned))
        }
        return template?.replace("{badge}", earned.title as String)
    }

    /** The streak line for a stored streak past the lookback; null below it, where RunNotificationVoice speaks. */
    static String longStreakLine(int days) {
        if (days < LONG_STREAK_FROM) return null
        Map<String, Object> next = nextBadge(days)
        if (next == null) {
            return RunNotificationVoice.pickLine(LONG_STREAK_LINES, "longStreak")?.replace("{n}", Integer.toString(days))
        }
        return RunNotificationVoice.pickLine(LONG_WITH_NEXT_LINES, "longStreak")
                ?.replace("{n}", Integer.toString(days))
                ?.replace("{next}", next.title as String)
                ?.replace("{left}", dayCount(((Integer) next.days) - days))
    }
}
