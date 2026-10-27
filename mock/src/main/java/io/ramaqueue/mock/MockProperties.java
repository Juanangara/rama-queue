package io.ramaqueue.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mock")
public record MockProperties(
        @DefaultValue("20261026") long seed,
        @DefaultValue("150") int latencyMs,
        @DefaultValue("100") int jitterMs,
        @DefaultValue("120") int limitPerMinute,
        @DefaultValue("30") int autoBlockSeconds,
        @DefaultValue("true") boolean congested) {
}
