package com.habitcoach.goal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

/**
 * Regex/heuristic natural-language goal parser — a line-for-line port of
 * legacy-reference/backend/goal_parser.py. Deliberately deterministic and
 * dependency-free (no Strands call): the reference implementation never
 * used AI for this, and there is no reason to add a network hop and a new
 * failure mode for something regex already does correctly and instantly.
 *
 * Every regex, cut point, and fallback default below matches the reference
 * exactly so parsing behavior is unchanged for the mobile app.
 */
@Component
public class GoalParser {

    private static final Map<String, Double> WORD_NUMBERS = Map.of(
            "one", 1.0, "two", 2.0, "three", 3.0, "four", 4.0, "five", 5.0,
            "half an", 0.5, "an", 1.0, "a", 1.0
    );

    private static final List<Map.Entry<Pattern, String>> EMOJI_MAP = List.of(
            Map.entry(Pattern.compile("\\bgym\\b|\\bworkout\\b|\\bexercise\\b|\\brun\\b|\\brunning\\b", CASE_INSENSITIVE), "🏋️"),
            Map.entry(Pattern.compile("\\bread\\b|\\breading\\b|\\bbook\\b", CASE_INSENSITIVE), "📚"),
            Map.entry(Pattern.compile("\\bstudy\\b|\\bstudying\\b|\\bjava\\b|\\bcode\\b|\\bcoding\\b|\\bprogramming\\b", CASE_INSENSITIVE), "💻"),
            Map.entry(Pattern.compile("\\bmeditat", CASE_INSENSITIVE), "🧘"),
            Map.entry(Pattern.compile("\\bwater\\b|\\bdrink\\b|\\bhydrat", CASE_INSENSITIVE), "💧"),
            Map.entry(Pattern.compile("\\bsleep\\b|\\bwake\\b", CASE_INSENSITIVE), "🌙"),
            Map.entry(Pattern.compile("\\bwrite\\b|\\bwriting\\b|\\bjournal", CASE_INSENSITIVE), "✍️")
    );

    private static final String DEFAULT_EMOJI = "🎯";

    private static final Pattern EXPLICIT_MERIDIEM_TIME =
            Pattern.compile("(\\d{1,2})(:(\\d{2}))?\\s*(am|pm|AM|PM)");
    private static final Pattern EXPLICIT_AT_TIME =
            Pattern.compile("\\bat\\s+(\\d{1,2}):(\\d{2})\\b");

    private static final Pattern HOURS_DURATION = Pattern.compile("(\\d+)\\s*(hour|hr)s?");
    private static final Pattern MINUTES_DURATION = Pattern.compile("(\\d+)\\s*(minute|min)s?");
    private static final Pattern WORD_HOUR_DURATION = Pattern.compile("(one|a|an|half an)\\s+hour");

    private static final Pattern TOTAL_DAYS = Pattern.compile("(\\d+)\\s*[- ]?days?");

    private static final Pattern LEADING_PHRASING = Pattern.compile(
            "^\\s*i want to\\s+|^\\s*i'd like to\\s+|^\\s*i will\\s+|^\\s*let'?s\\s+", CASE_INSENSITIVE);
    private static final Pattern LEADING_HABIT_OF = Pattern.compile(
            "^\\s*build\\s+(a|the)\\s+habit\\s+of\\s+|^\\s*(start|begin)\\s+(a|the)\\s+habit\\s+of\\s+", CASE_INSENSITIVE);
    private static final List<Pattern> NAME_CUT_POINTS = List.of(
            Pattern.compile("\\bevery day\\b", CASE_INSENSITIVE),
            Pattern.compile("\\bevery\\s+\\w+\\b", CASE_INSENSITIVE),
            Pattern.compile("\\bat\\s+\\d", CASE_INSENSITIVE),
            Pattern.compile("\\bfor\\s+\\d", CASE_INSENSITIVE),
            Pattern.compile("\\bfor\\s+the next\\b", CASE_INSENSITIVE),
            Pattern.compile("\\bfor\\s+one\\b", CASE_INSENSITIVE),
            Pattern.compile("\\bfor\\s+a\\b", CASE_INSENSITIVE)
    );

    public ParsedGoal parse(String text) {
        String safeText = text == null ? "" : text;
        String lower = safeText.toLowerCase();
        return new ParsedGoal(
                parseName(safeText),
                pickEmoji(lower),
                parseTime(lower),
                parseDurationMinutes(lower),
                parseTotalDays(lower),
                explicitClockTime(lower).isPresent()
        );
    }

    private Optional<String> explicitClockTime(String text) {
        Matcher m = EXPLICIT_MERIDIEM_TIME.matcher(text);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            String meridiem = m.group(4).toLowerCase();
            if (meridiem.equals("pm") && hour != 12) {
                hour += 12;
            }
            if (meridiem.equals("am") && hour == 12) {
                hour = 0;
            }
            return Optional.of(String.format("%02d:%02d", hour, minute));
        }

        Matcher atMatch = EXPLICIT_AT_TIME.matcher(text);
        if (atMatch.find()) {
            return Optional.of(String.format("%02d:%02d",
                    Integer.parseInt(atMatch.group(1)), Integer.parseInt(atMatch.group(2))));
        }

        return Optional.empty();
    }

    private String parseTime(String text) {
        Optional<String> explicit = explicitClockTime(text);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        if (Pattern.compile("\\bmorning\\b").matcher(text).find()) {
            return "07:00";
        }
        if (Pattern.compile("\\bevening\\b").matcher(text).find()) {
            return "19:00";
        }
        if (Pattern.compile("\\bnight\\b").matcher(text).find()) {
            return "21:00";
        }
        if (Pattern.compile("\\bafternoon\\b").matcher(text).find()) {
            return "15:00";
        }
        return "18:00";
    }

    private int parseDurationMinutes(String text) {
        Matcher hours = HOURS_DURATION.matcher(text);
        if (hours.find()) {
            return Integer.parseInt(hours.group(1)) * 60;
        }
        Matcher minutes = MINUTES_DURATION.matcher(text);
        if (minutes.find()) {
            return Integer.parseInt(minutes.group(1));
        }
        Matcher wordHour = WORD_HOUR_DURATION.matcher(text);
        if (wordHour.find()) {
            return (int) (WORD_NUMBERS.get(wordHour.group(1)) * 60);
        }
        return 30;
    }

    private int parseTotalDays(String text) {
        Matcher m = TOTAL_DAYS.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 21;
    }

    private String parseName(String text) {
        String cleaned = LEADING_PHRASING.matcher(text).replaceFirst("").strip();
        cleaned = LEADING_HABIT_OF.matcher(cleaned).replaceFirst("").strip();

        int cutIdx = cleaned.length();
        for (Pattern pattern : NAME_CUT_POINTS) {
            Matcher m = pattern.matcher(cleaned);
            if (m.find()) {
                cutIdx = Math.min(cutIdx, m.start());
            }
        }
        String name = stripChars(cleaned.substring(0, cutIdx), " ,.");

        if (name.isEmpty()) {
            String fallback = stripChars(cleaned, " ,.");
            if (!fallback.isEmpty()) {
                name = fallback.substring(0, Math.min(40, fallback.length()));
            }
            if (name.isEmpty()) {
                name = "My Habit";
            }
        }

        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String pickEmoji(String text) {
        for (Map.Entry<Pattern, String> entry : EMOJI_MAP) {
            if (entry.getKey().matcher(text).find()) {
                return entry.getValue();
            }
        }
        return DEFAULT_EMOJI;
    }

    /** Java equivalent of Python's str.strip(chars): trims any of the given
     * characters (not a whole substring) from both ends. */
    private static String stripChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }
}
