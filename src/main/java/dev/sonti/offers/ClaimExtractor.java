package dev.sonti.offers;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Baseline monetary-mention extraction, not semantic understanding or verification. */
final class ClaimExtractor {
    private static final Pattern MARKER = Pattern.compile("\\$|\\bUSD\\b");
    private static final Pattern FOREIGN = Pattern.compile("(?i)\\b(?:EUR|GBP|CAD|AUD|JPY|INR)\\b|[€£¥₹]");
    private static final Pattern AMOUNT = Pattern.compile("(?<![\\w$-])(?:USD\\s+|\\$)([0-9]{1,19}\\.[0-9]{2})(?![0-9]|[.,][0-9])");
    record Proposal(long priceMinor, String currency, int start, int end, String evidence) {}
    record Extraction(String status, String reason, Proposal proposal) {}
    Extraction extract(String text) {
        if (text == null || text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("Text must contain 1–2000 characters.");
        if (FOREIGN.matcher(text).find()) return abstain("UNSUPPORTED_CURRENCY");
        long markers = MARKER.matcher(text).results().limit(2).count();
        if (markers != 1) return abstain(markers == 0 ? "NO_AMOUNT" : "AMBIGUOUS_AMOUNT");
        var match = AMOUNT.matcher(text);
        if (!match.find()) return abstain("UNSUPPORTED_FORMAT");
        try {
            long minor = new BigDecimal(match.group(1)).movePointRight(2).longValueExact();
            return new Extraction("PROPOSED", "EXPLICIT_USD_AMOUNT",
                    new Proposal(minor, "USD", match.start(), match.end(), text.substring(match.start(), match.end())));
        } catch (ArithmeticException overflow) { return abstain("AMOUNT_OUT_OF_RANGE"); }
    }
    private Extraction abstain(String reason) { return new Extraction("ABSTAINED", reason, null); }
}
