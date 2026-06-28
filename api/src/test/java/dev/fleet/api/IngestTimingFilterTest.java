package dev.fleet.api;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IngestTimingFilterTest {
    @Test
    void requestReceiptIsStampedBeforeBodyConsumptionAndCannotComeFromClientJson()
            throws Exception {
        var request = new MockHttpServletRequest("POST", "/ingest");
        request.setContent("{\"ingestedAt\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long before = System.currentTimeMillis();
        new IngestTimingFilter()
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (input, response) -> {
                            long stamp = (long) input.getAttribute(IngestTimingFilter.ATTRIBUTE);
                            assertThat(stamp).isBetween(before, System.currentTimeMillis());
                            assertThat(stamp).isNotEqualTo(1);
                            assertThat(input.getInputStream().readAllBytes())
                                    .isEqualTo(request.getContentAsByteArray());
                        });
    }
}
