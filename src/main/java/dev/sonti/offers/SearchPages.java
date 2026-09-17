package dev.sonti.offers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, explicitly process-local cursor lifecycle. Tokens grant no tenant authorization. */
final class SearchPages implements AutoCloseable {
    interface Source {
        String open();
        List<OpenSearchIndex.Hit> read(String pit, String tenant, String query, int count, List<Object> after);
        void close(List<String> pits);
    }
    record Page(List<OfferSearch.Result> results, String nextCursor, Instant expiresAt, boolean scanLimitReached) {}
    record Position(UUID session, List<Object> after, int examined) {}
    private static final int MAX_SCANNED = 10000;
    private static final class Session {
        final UUID id = UUID.randomUUID();
        final String tenant, query;
        final int limit;
        final Instant expires;
        final ReentrantLock lock = new ReentrantLock();
        String pit;
        boolean closed;
        Session(String tenant, String query, int limit, Instant now) {
            this.tenant = tenant; this.query = query; this.limit = limit; this.expires = now.plusSeconds(120);
        }
    }
    private final Source source;
    private final OfferSearch verifier;
    private final Clock clock;
    private final JsonMapper json;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Semaphore requests = new Semaphore(8);
    private final byte[] secret = new byte[32];
    private boolean stopped;

    SearchPages(Source source, OfferSearch verifier, Clock clock, JsonMapper json) {
        this.source = source; this.verifier = verifier; this.clock = clock; this.json = json;
        new SecureRandom().nextBytes(secret);
    }
    Page search(String tenant, String query, int limit, String cursor) {
        validate(tenant, query, limit);
        if (!requests.tryAcquire()) throw busy();
        try {
            Position position = cursor == null ? null : decode(cursor);
            Session session = position == null ? reserve(tenant, query.strip(), limit) : session(position.session());
            if (!session.lock.tryLock()) throw busy();
            try {
                check(session, tenant, query.strip(), limit);
                if (position == null) {
                    try { session.pit = source.open(); }
                    catch (RuntimeException failure) { session.closed = true; throw failure; }
                }
                int examined = position == null ? 0 : position.examined();
                int count = Math.min(limit * 5, MAX_SCANNED - examined);
                var hits = source.read(session.pit, tenant, session.query, count, position == null ? null : position.after());
                if (hits.size() > count) throw new DomainException(503, "Invalid search batch.");
                var verified = new HashMap<Offer, OfferSearch.Result>();
                for (var result : verifier.verified(tenant, hits.stream().map(OpenSearchIndex.Hit::offer).toList())) {
                    verified.put(result.offer(), result);
                }
                var results = new ArrayList<OfferSearch.Result>();
                List<Object> after = null;
                int consumed = 0;
                for (var hit : hits) {
                    consumed++; examined++; after = hit.sort();
                    var result = verified.get(hit.offer());
                    if (result != null) results.add(result);
                    if (results.size() == limit) break;
                }
                check(session, tenant, session.query, limit); // Expiry during dependency I/O fails closed too.
                boolean capped = examined == MAX_SCANNED;
                boolean exhausted = consumed == hits.size() && hits.size() < count;
                String next = capped || exhausted ? null : encode(new Position(session.id, after, examined));
                if (next == null) finish(session);
                return new Page(List.copyOf(results), next, session.expires, capped);
            } catch (RuntimeException failure) {
                // A failed initial request has no client-held cursor, so release its known PIT.
                if (position == null) finish(session);
                throw failure;
            } finally { session.lock.unlock(); }
        } finally { requests.release(); }
    }
    void cancel(String tenant, String query, int limit, String cursor) {
        validate(tenant, query, limit);
        if (!requests.tryAcquire()) throw busy();
        try {
            Session session = session(decode(cursor).session());
            if (!session.lock.tryLock()) throw busy();
            try { check(session, tenant, query.strip(), limit); finish(session); }
            finally { session.lock.unlock(); }
        } finally { requests.release(); }
    }
    private synchronized Session reserve(String tenant, String query, int limit) {
        if (stopped) throw gone();
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !now.isBefore(s.expires.plusSeconds(10)));
        if (sessions.size() >= 128) throw busy();
        Session session = new Session(tenant, query, limit, now);
        sessions.put(session.id, session);
        return session;
    }
    private synchronized Session session(UUID id) {
        Session session = sessions.get(id);
        if (stopped || session == null) throw gone();
        return session;
    }
    private void check(Session session, String tenant, String query, int limit) {
        if (!session.tenant.equals(tenant) || !session.query.equals(query) || session.limit != limit) {
            throw new IllegalArgumentException("Cursor scope mismatch.");
        }
        if (session.closed || !clock.instant().isBefore(session.expires)) throw gone();
    }
    private void finish(Session session) {
        session.closed = true;
        if (session.pit != null) {
            try {
                source.close(List.of(session.pit)); // Returns only after confirmed backend deletion.
                synchronized (this) { sessions.remove(session.id, session); }
            }
            catch (RuntimeException ignored) { /* Fixed backend expiry plus retained admission reservation bounds leaks. */ }
        }
    }
    private String encode(Position position) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(position));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }
    private Position decode(String cursor) {
        if (cursor == null || cursor.length() > 2048 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid cursor.");
        }
        try {
            int split = cursor.indexOf('.');
            String payload = cursor.substring(0, split);
            Position position = json.readValue(Base64.getUrlDecoder().decode(payload), Position.class);
            if (position.session() == null || position.after() == null || position.after().size() != 3
                    || position.examined() < 1 || position.examined() >= MAX_SCANNED) throw new IllegalArgumentException();
            session(position.session()); // A well-shaped token from a previous process returns 410, not a fresh search.
            if (!MessageDigest.isEqual(mac(payload), Base64.getUrlDecoder().decode(cursor.substring(split + 1)))) {
                throw new IllegalArgumentException();
            }
            return position;
        } catch (DomainException unavailable) { throw unavailable; }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid cursor."); }
    }
    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Cursor signing unavailable."); }
    }
    private static void validate(String tenant, String query, int limit) {
        Input.identifier(tenant);
        if (query == null || query.isBlank() || query.length() > 200 || limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid search bounds.");
    }
    private static DomainException busy() { return new DomainException(429, "Search capacity reached. Retry later."); }
    private static DomainException gone() { return new DomainException(410, "Search cursor expired or closed. Start a new search."); }
    @Override public synchronized void close() {
        stopped = true;
        // Spring closes this bean after HTTP requests drain; one network call, not 128 serial timeouts.
        var pits = sessions.values().stream().map(s -> s.pit).filter(java.util.Objects::nonNull).distinct().toList();
        try { source.close(pits); } catch (RuntimeException ignored) { /* Fixed PIT expiry remains the fallback. */ }
        sessions.clear();
    }
}
