package dev.sonti.offers;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound actual bytes, including chunked requests, before JSON binding. */
@Component
@Order(-90) // Authentication's servlet filter runs at -100.
final class RequestLimits extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        boolean upload = request.getMethod().equals("POST") && request.getRequestURI().matches("/api/v1/feeds/[^/]+/[^/]+");
        int maximum = upload ? 1_048_576 : 16_384;
        if (request.getHeader("Content-Encoding") != null) { reject(response, 415); return; }
        if (request.getContentLengthLong() > maximum) { reject(response, 413); return; }
        byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
        if (bytes.length > maximum) { reject(response, 413); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API only."); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        }, response);
    }
    static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"Request exceeds supported limits.\"}");
    }
}
