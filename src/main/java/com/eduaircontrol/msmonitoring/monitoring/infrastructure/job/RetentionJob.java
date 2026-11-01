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
 * agregacion segun la politica de retencion. Usa la funcion PL/pgSQL
 * drop_partition_if_expired para evitar SQL dinamico en Java (SonarCloud S2077).
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
            if (name == null) continue;
            // Extract just the table name (without schema prefix)
            String tableName = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            if (tableName.endsWith("_default")) continue;
            try {
                Boolean dropped = jdbc.queryForObject(
                        "SELECT environment_monitoring.drop_partition_if_expired(?, ?)",
                        Boolean.class, tableName, 90);
                if (Boolean.TRUE.equals(dropped)) {
                    log.info("Dropped expired partition: {}", tableName);
                }
            } catch (Exception e) {
                log.warn("Error processing partition {}: {}", tableName, e.getMessage());
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
