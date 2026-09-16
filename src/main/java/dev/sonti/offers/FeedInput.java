package dev.sonti.offers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded admission parser. Invalid row bodies never enter persistent storage. */
final class FeedInput {
    static final int MAX_BYTES = 1024 * 1024, MAX_ROWS = 1000, MAX_LINE_BYTES = 4096;
    record Row(int number, String sha256, Offer offer, String error) {}
    record Upload(String sha256, int bytes, List<Row> rows) {}
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private static final Set<String> FIELDS = Set.of("tenantId", "merchantId", "offerId", "version", "title", "priceMinor",
            "currency", "availableQuantity", "sourceUpdatedAt", "deleted");
    FeedInput(Clock clock) { this.clock = clock; }

    Upload read(InputStream stream, long declaredLength, String checksum, String tenant, String merchant) throws IOException {
        Input.identifier(tenant); Input.identifier(merchant);
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA-256 required.");
        if (declaredLength > MAX_BYTES) throw oversized();
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw oversized();
        if (bytes.length == 0 || !sha256(bytes).equals(checksum)) throw new IllegalArgumentException("Empty feed or checksum mismatch.");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("UTF-8 required."); }
        var rows = new ArrayList<Row>();
        var admittedAt = clock.instant();
        for (int start = 0; start < bytes.length;) {
            int end = start;
            while (end < bytes.length && bytes[end] != '\n') {
                if (end - start >= MAX_LINE_BYTES) throw oversized();
                end++;
            }
            if (rows.size() == MAX_ROWS) throw oversized();
            byte[] line = java.util.Arrays.copyOfRange(bytes, start, end);
            String hash = sha256(line), error = null;
            Offer offer = null;
            try {
                var tree = json.readTree(line);
                if (tree == null || !tree.isObject() || tree.size() != FIELDS.size()
                        || !tree.properties().stream().allMatch(e -> FIELDS.contains(e.getKey()))) {
                    error = "INVALID_OFFER";
                } else {
                    offer = json.treeToValue(tree, Offer.class);
                    if (!tenant.equals(offer.tenantId()) || !merchant.equals(offer.merchantId())) error = "SCOPE_MISMATCH";
                    else if (offer.sourceUpdatedAt().isAfter(admittedAt)) error = "FUTURE_SOURCE";
                }
            } catch (tools.jackson.core.exc.StreamReadException invalid) { error = "INVALID_JSON"; }
            catch (RuntimeException invalid) { error = "INVALID_OFFER"; }
            rows.add(new Row(rows.size() + 1, hash, error == null ? offer : null, error));
            start = end + 1;
        }
        return new Upload(checksum, bytes.length, List.copyOf(rows));
    }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static DomainException oversized() { return new DomainException(413, "Feed exceeds documented byte, line or row limits."); }
}
