package io.softa.starter.metadata.sequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;

/**
 * The checks a code typed by hand must pass, as pure functions — the write validator, the live check a
 * form calls while the user types, and the rule editor all apply these, so they cannot disagree.
 *
 * <p>Order, and only the first failure is reported: the fixed rules (no spaces; unique as spelled,
 * which needs the database and is the caller's), then the sequence's format. Within Length &amp;
 * Characters, length before characters.
 */
public final class ManualCodeRules {

    /** The bounds of Min Length / Max Length — the widest a code column is. */
    public static final int LENGTH_BOUND = 32;

    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z]+)(?::(\\d+))?}");

    private ManualCodeRules() {
    }

    /** Fixed rule: spaces inside a code (the ends are trimmed before anything is checked). */
    public static String spaces(String label, String code) {
        return code.chars().anyMatch(Character::isWhitespace) ? label + " cannot contain spaces." : null;
    }

    /**
     * The sequence's format.
     *
     * @param template     the sequence template, for Same as Auto-generation
     * @param companyAbbr  the abbreviation of the record's company, null or blank when it has none
     * @return the message, or null when the code passes
     */
    public static String format(String label, String code, ManualEntryRule rule, String template, String companyAbbr) {
        if (rule == null) {
            return null;
        }
        return switch (rule.effectiveFormat()) {
            case ANY -> null;
            case SAME_AS_AUTO_GENERATION -> {
                Shape shape = shapeOf(template, companyAbbr);
                yield shape.regex().matcher(code).matches() ? null : label + " must be " + shape.wording() + ".";
            }
            case LENGTH_AND_CHARACTERS -> lengthAndCharacters(label, code, rule);
            case PATTERN -> {
                Pattern pattern = compile(rule.pattern());
                yield pattern == null || pattern.matcher(code).matches() ? null : rule.errorMessage();
            }
        };
    }

    private static String lengthAndCharacters(String label, String code, ManualEntryRule rule) {
        Integer min = rule.minLength();
        Integer max = rule.maxLength();
        int length = code.length();
        boolean tooShort = min != null && length < min;
        boolean tooLong = max != null && length > max;
        if (tooShort || tooLong) {
            if (min != null && max != null) {
                return min.equals(max)
                        ? label + " must be " + min + " characters."
                        : label + " must be " + min + "–" + max + " characters.";
            }
            return min != null
                    ? label + " must be at least " + min + " characters."
                    : label + " cannot exceed " + max + " characters.";
        }
        AllowedCharacters allowed = rule.allowedCharacters();
        if (allowed != null && !allowed.getPattern().matcher(code).matches()) {
            return label + " can only contain " + allowed.getWording() + ".";
        }
        return null;
    }

    /**
     * What a generated code looks like: the company abbreviation and the literals as they are, each
     * numeric token as that many digits. Also its wording — the parts joined by {@code " + "}, digit
     * runs merged: {@code {companyAbbr}{seq:7}} reads {@code ZK + 7 digits}, or {@code 7 digits} for a
     * company with no abbreviation. Literals and the abbreviation match exactly, case included.
     */
    public static Shape shapeOf(String template, String companyAbbr) {
        StringBuilder regex = new StringBuilder();
        List<String> words = new ArrayList<>();
        int pendingDigits = 0;
        boolean pendingOpenEnded = false;
        Matcher m = TOKEN.matcher(template == null ? "" : template);
        int last = 0;
        while (m.find()) {
            String literal = template.substring(last, m.start());
            if (!literal.isEmpty()) {
                pendingDigits = flushDigits(words, pendingDigits, pendingOpenEnded);
                pendingOpenEnded = false;
                regex.append(Pattern.quote(literal));
                words.add(literal);
            }
            String name = m.group(1);
            String width = m.group(2);
            switch (name) {
                case "companyAbbr" -> {
                    if (StringUtils.isNotBlank(companyAbbr)) {
                        pendingDigits = flushDigits(words, pendingDigits, pendingOpenEnded);
                        pendingOpenEnded = false;
                        regex.append(Pattern.quote(companyAbbr));
                        words.add(companyAbbr);
                    }
                }
                case "seq" -> {
                    if (width == null) {
                        regex.append("\\d+");
                        pendingOpenEnded = true;
                    } else {
                        int n = Integer.parseInt(width);
                        regex.append("\\d{").append(n).append('}');
                        pendingDigits += n;
                    }
                }
                case "yyyy" -> {
                    regex.append("\\d{4}");
                    pendingDigits += 4;
                }
                case "yy", "MM", "dd" -> {
                    regex.append("\\d{2}");
                    pendingDigits += 2;
                }
                default -> {
                    // An unknown token fails allocation already; here it matches itself.
                    regex.append(Pattern.quote(m.group()));
                    words.add(m.group());
                }
            }
            last = m.end();
        }
        String tail = template == null ? "" : template.substring(last);
        if (!tail.isEmpty()) {
            pendingDigits = flushDigits(words, pendingDigits, pendingOpenEnded);
            pendingOpenEnded = false;
            regex.append(Pattern.quote(tail));
            words.add(tail);
        }
        flushDigits(words, pendingDigits, pendingOpenEnded);
        return new Shape(Pattern.compile(regex.toString()), String.join(" + ", words));
    }

    private static int flushDigits(List<String> words, int digits, boolean openEnded) {
        if (openEnded) {
            words.add(digits > 0 ? "at least " + (digits + 1) + " digits" : "digits");
        } else if (digits > 0) {
            words.add(digits + " digits");
        }
        return 0;
    }

    /** The regex a generated code matches, and how to say it. */
    public record Shape(Pattern regex, String wording) {
    }

    /**
     * What is wrong with a rule as configured, by property — checked when the sequence is saved. Only
     * the active format's settings are judged; the others are kept as they are, unchecked.
     */
    public static Map<String, String> ruleErrors(ManualEntryRule rule) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (rule == null) {
            return errors;
        }
        switch (rule.effectiveFormat()) {
            case LENGTH_AND_CHARACTERS -> {
                Integer min = rule.minLength();
                Integer max = rule.maxLength();
                if (min == null && max == null && rule.allowedCharacters() == null) {
                    errors.put("format", "Set at least one of Min Length, Max Length or Allowed Characters.");
                    break;
                }
                if (min != null && (min < 1 || min > LENGTH_BOUND)) {
                    errors.put("minLength", "Min Length must be between 1 and " + LENGTH_BOUND + ".");
                }
                if (max != null && (max < 1 || max > LENGTH_BOUND)) {
                    errors.put("maxLength", "Max Length must be between 1 and " + LENGTH_BOUND + ".");
                }
                if (min != null && max != null && min > max && errors.isEmpty()) {
                    errors.put("minLength", "Min Length cannot be greater than Max Length.");
                }
            }
            case PATTERN -> {
                if (StringUtils.isBlank(rule.pattern())) {
                    errors.put("pattern", "Pattern is required.");
                } else if (compile(rule.pattern()) == null) {
                    errors.put("pattern", "Pattern is not a valid regular expression.");
                }
                if (StringUtils.isBlank(rule.errorMessage())) {
                    errors.put("errorMessage", "Error Message is required.");
                }
            }
            default -> {
                // Any and Same as Auto-generation have nothing to configure.
            }
        }
        return errors;
    }

    private static Pattern compile(String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
