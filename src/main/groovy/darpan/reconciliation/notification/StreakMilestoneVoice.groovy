package darpan.reconciliation.notification

/**
 * DAR-BE-121. The chat lines for a stored streak (DAR-BE-107): the badge an automation just earned, and the streak line
 * (DAR-BE-144: the only streak line; the 20-run lookback is gone). Pure, like RunNotificationVoice, and dealt
 * through its pickLine so every pool spends all its entries before one comes round again.
 *
 * AutomationStreakSupport.notificationFacts reads the stored streak and the badge or quest this run's slot earned, and a
 * badge's line rides on the run message that crossed the threshold (DAR-BE-108), in place of the streak line.
 *
 * Badges belong to an automation and count clean DAYS (DAR-UI-059). First Light and Full Moon are earned once per
 * automation, ever; from Lunar Lap up a later streak earns the badge again. Every badge and side-quest message also
 * carries one astronomy fact (DAR-BE-138, factLine), never the same one twice for a tenant.
 */
class StreakMilestoneVoice {

    /**
     * The ladder, lowest first. Keys match StreakCelebrationSupport.CATALOG where a badge has a GIF. The keys are stored
     * on AutomationStreakBadge rows, so they keep their first names; DAR-BE-138 renamed the titles after the sky
     * (Aditi 2026-10-27), each fitting its day count: a telescope's first look, new moon to full, one lap of the Moon's
     * phases, two of them, a season, half an orbit, a whole one.
     */
    static final List<Map<String, Object>> BADGE_LADDER = [
            [key: "HIGH_FIVE", title: "First Light", days: 5, reEarnable: false],
            [key: "FIFTEEN_DAYS_OF_FAME", title: "Full Moon", days: 15, reEarnable: false],
            [key: "MONTH_DOZE", title: "Lunar Lap", days: 30, reEarnable: true],
            [key: "DOUBLE_DOZE", title: "Twin Moons", days: 60, reEarnable: true],
            [key: "QUARTER_CLEAN", title: "Solstice", days: 90, reEarnable: true],
            [key: "HALF_YEAR_HIBERNATION", title: "Far Side of the Sun", days: 180, reEarnable: true],
            [key: "GLORIOUSLY_BORING", title: "Full Orbit", days: 365, reEarnable: true],
    ].asImmutable()

    /** The first day count worth a line, where the run lookback used to say "3 in a row". */
    static final int STREAK_LINE_FROM = 3

    /**
     * The line on the run message whose run earned a badge, the first time this automation earns it. Badges are rare,
     * so these get room for a joke (DAR-BE-138, Aditi 2026-10-27: "this is a once in a while thing so we can have some
     * more fun with the text"): up to three short sentences, each playing on the badge's bit of sky and saying what it
     * took. {badge} is the title, filled in here so no line can misspell it. No line puts an article before {badge}:
     * "a Full Orbit" and "another Solstice" do not read.
     */
    static final Map<String, List<String>> FIRST_EARN_LINES = [
            HIGH_FIVE: [
                    "{badge}. Five days in a row lined up, and the streak just opened its eyes to the sky.",
                    "Every telescope has a first night, and this automation just had five matched days of it. {badge} unlocked.",
                    "Five straight days without a single difference. The lens cap is off, and this is {badge}.",
                    "{badge} is in. Five matched days, and the automation has officially started looking up.",
                    "Five days, zero differences, and the first badge comes into focus — {badge}.",
                    "The dome is open and the sky is quiet. Five days in a row matched, which is exactly what {badge} asks for.",
                    "Five matched days on the trot. Astronomers call a new telescope's first look {badge}, and so do we.",
                    "Day five lined up like the four before it. Point the telescope, focus, and collect {badge}.",
                    "{badge}, after five straight days of both systems telling the same story.",
                    "Five days without anything to chase. The streak has had its first good look at the stars, and that is {badge}.",
                    "Nothing out of line for five days running. Small step, clear skies, {badge}.",
                    "Five in a row. Somewhere an observatory is cheering quietly for {badge}.",
            ].asImmutable(),
            FIFTEEN_DAYS_OF_FAME: [
                    "Fifteen days in a row matched, about as long as the Moon takes to go from new to full. {badge} unlocked.",
                    "{badge}. Fifteen straight days without a difference, and the streak is lit all the way across.",
                    "Half a lunar month of matched records. The Moon is round, the data is square, and this is {badge}.",
                    "Fifteen days, zero differences. Werewolves are nervous, reconcilers are relaxed, and the badge is {badge}.",
                    "The streak has waxed for fifteen days straight and is now at {badge}.",
                    "Fifteen consecutive matched days. Bright, round, and impossible to miss — {badge}.",
                    "{badge} is in. Fifteen days of nothing to chase, and the night has never looked brighter.",
                    "Fifteen days lined up end to end. Howling is optional, {badge} is not.",
                    "New to full in fifteen days, same as the Moon. {badge}.",
                    "Fifteen days without an exception, and the automation is glowing a little. That is {badge}.",
                    "Every run for fifteen days lined up, and the streak has reached peak brightness. {badge}.",
                    "Fifteen matched days running, a whole face of good news. {badge} unlocked.",
            ].asImmutable(),
            MONTH_DOZE: [
                    "Thirty days, zero differences, and the Moon ran a whole lap of its phases while this automation matched every day of it. {badge} unlocked.",
                    "{badge}. The Moon went new, full, and new again, and the data did not miss a single night.",
                    "Thirty straight days lined up. The Moon needs about 29 and a half days for a lap of its phases, so this automation finished half a day ahead. {badge}.",
                    "One whole lunar cycle of matched records. The tides came and went, the data stayed put, and the badge is {badge}.",
                    "Thirty days of data so steady you could set a lunar calendar by it. {badge} awarded.",
                    "{badge} is in. Every phase of the Moon, every day matched, and not one difference worth a second look.",
                    "The Moon has lapped the sky once, and so has this streak. {badge}.",
                    "Thirty days in a row matched. Crescent, quarter, gibbous, full and back again, all without an exception. That is {badge}.",
                    "A full month of nothing to chase. The Moon did all the changing, the data did none, and {badge} goes on the record.",
                    "Thirty consecutive matched days, one complete round of the Moon's moods. {badge} unlocked.",
                    "The streak just finished a lap of the Moon's calendar. Thirty days, every one lined up, {badge}.",
                    "Thirty days without a single difference. The Moon is back where it started, and the automation is holding {badge}.",
            ].asImmutable(),
            DOUBLE_DOZE: [
                    "Sixty days matched, two whole lunar cycles back to back. {badge} unlocked.",
                    "{badge}. The Moon has gone round its phases twice and this automation has not blinked once.",
                    "Two lunar laps, sixty days, zero differences. Mars has two moons as well, and they have never had to reconcile anything. That is {badge}.",
                    "Sixty straight days lined up. One moon was impressive, two is a habit, and the badge is {badge}.",
                    "Sixty days of nothing to chase. Double the moonlight, double the quiet, {badge}.",
                    "{badge} is in. Sixty consecutive matched days, and the night sky is getting crowded with good news.",
                    "Two months of runs that all lined up. The Moon came round twice to check, and found {badge}.",
                    "Sixty days without a single difference, which earns a second moon for the collection. {badge}.",
                    "The first lunar lap went so well it got a sequel. Sixty matched days in a row, {badge}.",
                    "Sixty days, two full moons, no exceptions. {badge} unlocked.",
                    "Every day for sixty days has matched. Two moons up, nothing out of line, and the badge reads {badge}.",
                    "Sixty consecutive days lined up. The streak now has more moons than Earth does, and they are called {badge}.",
            ].asImmutable(),
            QUARTER_CLEAN: [
                    "Ninety days in a row matched, a whole season from one turning point of the year to the next. Sunglasses on for {badge}.",
                    "{badge}. A full quarter without a single difference, and the sun is putting on a show about it.",
                    "Ninety straight days lined up. Stonehenge lines up with the solstice sunrise, this automation lines up records, and both get {badge}.",
                    "A whole season of matched records. The sun has reached the end of its swing, and the automation has reached {badge}.",
                    "Ninety days, zero exceptions. Finance owes this automation a coffee and the sun owes it a pair of shades. {badge}.",
                    "{badge} is in. Three months of nothing to chase, and the quarterly review just became one slide long.",
                    "Ninety consecutive matched days. The brightest stretch this automation has had yet, and it is called {badge}.",
                    "The season turned and the data did not. Ninety days in a row, {badge}.",
                    "Ninety matched days end to end. Deal with it: this automation has {badge}.",
                    "A quarter of a year, every day lined up. Solstice means the sun stands still, and so did the difference count, at zero. {badge}.",
                    "Three months without a difference. Put on the shades, step into the sunlight, and collect {badge}.",
                    "Ninety days of steady data, one full season. {badge} unlocked, and the sun is shining on it.",
            ].asImmutable(),
            HALF_YEAR_HIBERNATION: [
                    "A hundred and eighty matched days. Earth has travelled halfway round the Sun since this streak began, and the automation earns {badge}.",
                    "{badge}. Six months without a single difference, and the planet is on the other side of its orbit.",
                    "Half a year of matched records. Where Earth was when this streak started is now roughly three hundred million kilometres away — {badge}.",
                    "180 days in a row lined up. Half an orbit, zero exceptions, {badge}.",
                    "Six months, every day matched. The streak has watched two seasons go by on the way to {badge}.",
                    "{badge} is in. A hundred and eighty days of steady data, and Earth has swung all the way round to celebrate.",
                    "180 straight matched days. Somewhere past the Sun, a planet full of good news is waving hello, and it says {badge}.",
                    "Half a year without anything to chase. The Sun now sits between today and day one, and the badge is {badge}.",
                    "Six months of matched days end to end. Halfway round the Sun and not a wobble in the orbit. {badge}.",
                    "A hundred and eighty days lined up. Hi from the far side, and congratulations on {badge}.",
                    "Two seasons, not one difference. {badge} unlocked, see you on the way back round.",
                    "180 consecutive matched days. The view from over here is excellent, and it includes {badge}.",
            ].asImmutable(),
            GLORIOUSLY_BORING: [
                    "A full year. 365 days without a single difference, and Earth has gone all the way round the Sun to hand over {badge}.",
                    "{badge}. One trip around the Sun, every day matched, and nothing worth telling anyone about, which is the whole point.",
                    "365 days in a row lined up. Earth covered roughly 940 million kilometres to get here and the data did not drop a single record. That is {badge}.",
                    "One year without a difference. Historians of this automation will find the period very dull, and that is exactly why it earns {badge}.",
                    "Every single day for a whole orbit matched. Lap one of one, finish line crossed, {badge}.",
                    "A year of nothing to chase. The planet went round the Sun at about thirty kilometres a second and the records still kept up. {badge}.",
                    "365 straight days. No drama, no plot twists, one full orbit. {badge}.",
                    "{badge} is official. Twelve months, all 365 days matched, and the most exciting thing that happened was this message.",
                    "One full lap of the Sun, every record in its place. Happy orbit-versary, and here is {badge}.",
                    "Three hundred and sixty-five days of the same good news. Earth is back where it started, and the automation is holding {badge}.",
                    "A whole year without a single difference. Somebody write a very short book about it, titled {badge}.",
                    "365 days matched. The seasons went round, the Sun came back to the same spot among the stars, and {badge} went on the record.",
            ].asImmutable(),
    ].asImmutable()

    /** A badge from Lunar Lap up, earned again on a later streak; {ordinal} is "2nd", "3rd" and on. No day count: shared by every badge. */
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
     * A stored streak, counted in DAYS (DAR-BE-107), so no line says "runs". Read for any count from STREAK_LINE_FROM
     * into the thousands; no badge is named, so these still read once every badge is earned.
     */
    static final List<String> STREAK_LINES = [
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
     * DAR-BE-108. The line for a side quest just found, once per tenant. Meteor Shower rides on the run that found it;
     * Black Hole Survivor is its own post to the tenant's streaks space. {records} is the run's record count with
     * thousands separators. The keys predate the DAR-BE-138 names (Big Haul, Black Friday Survivor) and stay as stored.
     */
    static final Map<String, List<String>> QUEST_LINES = [
            BIG_HAUL: [
                    "Side quest found: Meteor Shower. {records} records came down in one run, and every one of them landed where it should.",
                    "{records} records in a single run without a single difference. That is a Meteor Shower, and the side quest is found.",
                    "Meteor Shower: {records} records streaked through one run and not one burned up. Side quest found.",
                    "Side quest found: Meteor Shower, for {records} records in one run with nothing out of line.",
                    "{records} records, one run, zero differences. Make a wish, it is the Meteor Shower side quest.",
                    "One run brought down {records} records and all of them matched. Meteor Shower is found.",
            ].asImmutable(),
            BLACK_FRIDAY_SURVIVOR: [
                    "Side quest found: Black Hole Survivor. Every automation lined up from Black Friday to Cyber Monday, and nothing got pulled in.",
                    "Black Friday through Cyber Monday pulls like a black hole, and not one automation found a difference. Side quest found: Black Hole Survivor.",
                    "The busiest weekend of the year came and went, and every automation held its orbit. Black Hole Survivor is found.",
                    "Every automation made it through Black Friday to Cyber Monday without a difference, which earns Black Hole Survivor.",
                    "Side quest found: Black Hole Survivor. Four of the heaviest days of the year, and every record escaped.",
                    "Black Hole Survivor: every automation, every day from Black Friday to Cyber Monday, all lined up.",
            ].asImmutable(),
    ].asImmutable()

    /** The line for a side quest just found, or null for a quest with no lines. */
    static String questLine(String key, long records) {
        String template = key ? RunNotificationVoice.pickLine(QUEST_LINES.get(key), "quest|" + key) : null
        return template?.replace("{records}", String.format(Locale.US, "%,d", records))
    }

    /** DAR-BE-138. The line that carries a badge or side quest's astronomy fact; null for no fact. */
    static String factLine(String fact) {
        String text = fact?.trim()
        return text ? "Space fact: ${text}".toString() : null
    }

    /** The plain pool and the next-badge pool dealt as one, so a streak names its next badge about half the time. */
    static final List<String> STREAK_WITH_NEXT_LINES = (STREAK_LINES + NEXT_BADGE_LINES).asImmutable()

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

    /** The streak line for a stored streak; null below STREAK_LINE_FROM, where a clean message has no streak to tell. */
    static String streakLine(int days) {
        if (days < STREAK_LINE_FROM) return null
        Map<String, Object> next = nextBadge(days)
        if (next == null) {
            return RunNotificationVoice.pickLine(STREAK_LINES, "streak")?.replace("{n}", Integer.toString(days))
        }
        return RunNotificationVoice.pickLine(STREAK_WITH_NEXT_LINES, "streak")
                ?.replace("{n}", Integer.toString(days))
                ?.replace("{next}", next.title as String)
                ?.replace("{left}", dayCount(((Integer) next.days) - days))
    }
}
