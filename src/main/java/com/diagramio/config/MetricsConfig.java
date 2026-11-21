package com.diagramio.config;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình Micrometer Prometheus Metrics và Prometheus Scrape Endpoint cho Spring Boot Actuator.
 *
 * <p>Cung cấp:
 * <ul>
 *   <li>PrometheusMeterRegistry để thu thập số liệu thời gian phản hồi (http.server.requests),
 *       tỷ lệ lỗi và connection pool HikariCP.</li>
 *   <li>PrometheusScrapeEndpoint phục vụ cho việc scrape metrics tại /actuator/prometheus.</li>
 * </ul>
 */
@Configuration
public class MetricsConfig {

    @Bean
    @ConditionalOnMissingBean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = PrometheusScrapeEndpoint.class)
    public PrometheusScrapeEndpoint prometheusScrapeEndpoint(PrometheusMeterRegistry prometheusMeterRegistry) {
        return new PrometheusScrapeEndpoint(prometheusMeterRegistry.getPrometheusRegistry());
    }
}
