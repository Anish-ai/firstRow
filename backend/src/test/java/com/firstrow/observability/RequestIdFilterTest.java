package com.firstrow.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesASafeIncomingId() {
        assertThat(RequestIdFilter.resolve("lb-1234.abc_DEF")).isEqualTo("lb-1234.abc_DEF");
    }

    @Test
    void generatesAnIdWhenMissing() {
        assertThat(RequestIdFilter.resolve(null)).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesUnsafeIdsThatCouldForgeLogLines() {
        assertThat(RequestIdFilter.resolve("abc\nINFO fake log line")).doesNotContain("\n").hasSize(36);
        assertThat(RequestIdFilter.resolve("x".repeat(65))).hasSize(36);
    }

    @Test
    void putsTheIdInTheResponseAndMdcDuringTheRequestAndClearsItAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get("requestId"));
            }
        });

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-42");
        assertThat(seenInMdc.get()).isEqualTo("req-42");
        assertThat(MDC.get("requestId")).isNull(); // not leaked to the next request on this thread
    }
}
