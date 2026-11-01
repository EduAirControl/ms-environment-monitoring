package com.eduaircontrol.msmonitoring.monitoring.infrastructure.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de retencion (ADR-020).
 *
 * <p>Diariamente a las 03:00 UTC elimina particiones vencidas y datos de
 * agregacion segun la politica de retencion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionJob {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 3 * * *")
    public void applyRetention() {
        // Drop expired monthly partitions (raw data > 90 days)
        var partitions = jdbc.queryForList("""
                SELECT inhrelid::regclass::text AS partition_name
                FROM pg_inherits
                WHERE inhparent = 'environment_monitoring.environment_measurement'::regclass
                """);
        for (var row : partitions) {
            String name = (String) row.get("partition_name");
            if (name == null || name.endsWith("_default")) continue;
            // Extract date from partition name (environment_measurement_YYYY_MM)
            String[] parts = name.split("_");
            if (parts.length < 4) continue;
            try {
                int year = Integer.parseInt(parts[parts.length - 2]);
                int month = Integer.parseInt(parts[parts.length - 1]);
                java.time.YearMonth ym = java.time.YearMonth.of(year, month);
                java.time.Instant partitionEnd = ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                if (partitionEnd.isBefore(Instant.now().minus(90, ChronoUnit.DAYS))) {
                    jdbc.execute("DROP TABLE IF EXISTS " + name);
                    log.info("Dropped expired partition: {}", name);
                }
            } catch (NumberFormatException e) {
                log.warn("Skipping partition with unparseable name: {}", name);
            }
        }

        // Retention on aggregation tables
        jdbc.update("DELETE FROM environment_monitoring.measurement_agg_5m WHERE bucket_start < ?",
                Instant.now().minus(180, ChronoUnit.DAYS));
        jdbc.update("DELETE FROM environment_monitoring.measurement_agg_1h WHERE bucket_start < ?",
                Instant.now().minus(365, ChronoUnit.DAYS));
        jdbc.update("DELETE FROM environment_monitoring.measurement_agg_1d WHERE bucket_start < ?",
                Instant.now().minus(5 * 365, ChronoUnit.DAYS));
    }
}
