package dev.sonti.offers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class FeedInputTest {
    private final Instant now = Instant.parse("2026-09-16T12:00:00Z");
    private final FeedInput parser = new FeedInput(Clock.fixed(now, ZoneOffset.UTC));
    private final String row = JsonMapper.builder().build().writeValueAsString(new Offer("demo", "merchant", "item", 1,
            "Keyboard", 999, "USD", 5, now, false));
    private FeedInput.Upload parse(String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return parser.read(new ByteArrayInputStream(bytes), -1, FeedInput.sha256(bytes), "demo", "merchant");
    }
    @Test void acceptsExactRowsCrLfAndFinalNewlineWithOriginalByteChecksum() throws Exception {
        var upload = parse(row + "\r\n" + row + "\n");
        assertThat(upload.rows()).hasSize(2).allMatch(r -> r.error() == null);
        assertThat(upload.bytes()).isEqualTo(row.getBytes(StandardCharsets.UTF_8).length * 2 + 3);
        assertThat(upload.rows().getFirst().sha256()).isNotEqualTo(upload.rows().getLast().sha256());
        assertThat(parse(row).rows()).hasSize(1);
    }
    @Test void rowErrorsAreSanitizedAndDoNotKeepRawPayload() throws Exception {
        var upload = parse("secret-invalid{\n" + row.replace("\"demo\"", "\"other\"") + "\n"
                + row.replace(now.toString(), now.plusSeconds(1).toString()) + "\n"
                + row.replace("999", "\"999\"") + "\n" + row.replace("999", "999.2") + "\n"
                + row.replace("\"version\":1", "\"version\":1,\"version\":2") + "\n{}\n\n" + row);
        assertThat(upload.rows()).hasSize(9);
        assertThat(upload.rows().subList(0, 8)).allMatch(r -> r.error() != null && r.offer() == null);
        assertThat(upload.rows().get(1).error()).isEqualTo("SCOPE_MISMATCH");
        assertThat(upload.rows().get(2).error()).isEqualTo("FUTURE_SOURCE");
        assertThat(upload.rows().getLast().error()).isNull();
        assertThat(upload.toString()).doesNotContain("secret-invalid");
        assertThat(parse(row + " {}").rows().getFirst().error()).isNotNull();
    }
    @Test void rejectsMissingUnknownNullAndInvalidFacts() throws Exception {
        for (String bad : new String[]{row.replace("\"deleted\":false", "\"extra\":false"),
                row.replace("\"deleted\":false", "\"deleted\":null"), row.replace("999", "-1"),
                row.replace("\"USD\"", "\"EUR\""), row.replace("\"version\":1", "\"version\":0")}) {
            assertThat(parse(bad).rows().getFirst().error()).isNotNull();
        }
    }
    @Test void exactLimitsPassAndFirstExcessIsRejectedWithoutTrustingContentLength() throws Exception {
        assertThat(parse((row + "\n").repeat(1000)).rows()).hasSize(1000);
        assertThat(parse("x".repeat(4096)).rows()).hasSize(1);
        for (String oversized : new String[]{(row + "\n").repeat(1001), "x".repeat(4097), "\n".repeat(FeedInput.MAX_BYTES + 1)}) {
            assertThatThrownBy(() -> parse(oversized)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(413));
        }
        byte[] huge = new byte[FeedInput.MAX_BYTES + 1];
        assertThatThrownBy(() -> parser.read(new ByteArrayInputStream(huge), 1, FeedInput.sha256(huge), "demo", "merchant"))
                .isInstanceOf(DomainException.class);
    }
    @Test void rejectsEmptyChecksumAndInvalidUtf8() {
        assertThatThrownBy(() -> parse("")).isInstanceOf(IllegalArgumentException.class);
        byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.read(new ByteArrayInputStream(bytes), -1, "0".repeat(64), "demo", "merchant"))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        assertThatThrownBy(() -> parser.read(new ByteArrayInputStream(malformed), -1, FeedInput.sha256(malformed), "demo", "merchant"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
