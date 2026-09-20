package com.meridian.api.ml;

import com.meridian.api.common.RawJson;

import java.math.BigDecimal;

/**
 * A scoring result, from either the ML service or the local fallback.
 *
 * <p>{@code contributions} stays as raw JSON: it is the model's explanation of its own output, its
 * shape belongs to the ML service, and it is only ever stored and handed back to the UI. Parsing it
 * into Java types here would mean a change on the Python side needs a matching change here before
 * it could reach the dashboard.
 */
public record MlScore(
        BigDecimal score,
        String tier,
        String confidence,
        String modelVersion,
        RawJson contributions
) {
}
