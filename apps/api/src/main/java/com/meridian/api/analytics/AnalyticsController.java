package com.meridian.api.analytics;

import com.meridian.api.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /analytics} — the numbers behind the Overview and Analytics tabs.
 *
 * <p>Six read-only endpoints, one per chart, each scoped to the caller's org. The envelope key for
 * each ({@code tiers}, {@code series}, {@code cells}, {@code breakdown}, {@code authors}) is what
 * the frontend destructures, so the names are load-bearing.
 */
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsQueries queries;

    public AnalyticsController(AnalyticsQueries queries) {
        this.queries = queries;
    }

    /**
     * The overview tiles. The four aggregates are independent, but they are issued sequentially
     * here rather than in parallel as the old {@code Promise.all} did — see DECISIONS.md; each is
     * an indexed lookup and the combined latency is well under the time the page spends rendering.
     */
    @GetMapping("/overview")
    public Map<String, Object> overview(@AuthenticationPrincipal AuthenticatedUser user) {
        UUID orgId = user.orgId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tiers", queries.tierDistribution(orgId));
        body.put("throughput_30d", queries.throughput30d(orgId));
        body.put("sla_breaches", queries.slaBreaches(orgId));
        body.put("recent", queries.recent(orgId));
        return body;
    }

    @GetMapping("/pr-size-trend")
    public Map<String, Object> sizeTrend(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("series", queries.sizeTrend(user.orgId()));
    }

    @GetMapping("/cycle-time")
    public Map<String, Object> cycleTime(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("breakdown", queries.cycleTime(user.orgId()));
    }

    @GetMapping("/merge-heatmap")
    public Map<String, Object> mergeHeatmap(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("cells", queries.mergeHeatmap(user.orgId()));
    }

    @GetMapping("/revert-rate")
    public Map<String, Object> revertRate(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("series", queries.revertRate(user.orgId()));
    }

    @GetMapping("/author-trends")
    public Map<String, Object> authorTrends(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("authors", queries.authorTrends(user.orgId()));
    }
}
