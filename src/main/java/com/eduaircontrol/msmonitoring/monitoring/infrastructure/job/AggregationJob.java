package com.eduaircontrol.msmonitoring.monitoring.infrastructure.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de agregacion (ADR-020).
 *
 * <p>Cada 5 minutos recalcula los buckets de las ultimas 25 horas a partir de
 * los datos crudos (upsert idempotente), de modo que las mediciones que llegan
 * tarde (p. ej. un nodo que vuelve tras una caida de hasta 24 h) se incorporan
 * a buckets ya cerrados. Despues propaga la cascada: 5 min -> 1 h -> 1 dia.
 */
@Component
@RequiredArgsConstructor
public class AggregationJob {

    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void refreshAggregations() {
        Instant cutoff = Instant.now().minus(25, ChronoUnit.HOURS);

        // 5-minute buckets from raw measurements
        jdbc.update("""
                INSERT INTO environment_monitoring.measurement_agg_5m
                    (sensor_installation_id, variable_id, bucket_start, avg_value, min_value, max_value, sample_count)
                SELECT sensor_installation_id, variable_id,
                       date_trunc('hour', measured_at) + (EXTRACT(MINUTE FROM measured_at)::int / 5) * interval '5 minutes' AS bucket_start,
                       AVG(measured_value), MIN(measured_value), MAX(measured_value), COUNT(*)::int
                FROM environment_monitoring.environment_measurement
                WHERE measured_at >= ?
                GROUP BY sensor_installation_id, variable_id, bucket_start
                ON CONFLICT (sensor_installation_id, variable_id, bucket_start) DO UPDATE
                SET avg_value = EXCLUDED.avg_value,
                    min_value = EXCLUDED.min_value,
                    max_value = EXCLUDED.max_value,
                    sample_count = EXCLUDED.sample_count
                """, cutoff);

        // 1-hour buckets from 5-min aggregation
        jdbc.update("""
                INSERT INTO environment_monitoring.measurement_agg_1h
                    (sensor_installation_id, variable_id, bucket_start, avg_value, min_value, max_value, sample_count)
                SELECT sensor_installation_id, variable_id,
                       date_trunc('hour', bucket_start) AS bucket_start,
                       AVG(avg_value), MIN(min_value), MAX(max_value), SUM(sample_count)::int
                FROM environment_monitoring.measurement_agg_5m
                WHERE bucket_start >= ?
                GROUP BY sensor_installation_id, variable_id, date_trunc('hour', bucket_start)
                ON CONFLICT (sensor_installation_id, variable_id, bucket_start) DO UPDATE
                SET avg_value = EXCLUDED.avg_value,
                    min_value = EXCLUDED.min_value,
                    max_value = EXCLUDED.max_value,
                    sample_count = EXCLUDED.sample_count
                """, cutoff);

        // 1-day buckets from 1-hour aggregation
        jdbc.update("""
                INSERT INTO environment_monitoring.measurement_agg_1d
                    (sensor_installation_id, variable_id, bucket_start, avg_value, min_value, max_value, sample_count)
                SELECT sensor_installation_id, variable_id,
                       date_trunc('day', bucket_start) AS bucket_start,
                       AVG(avg_value), MIN(min_value), MAX(max_value), SUM(sample_count)::int
                FROM environment_monitoring.measurement_agg_1h
                WHERE bucket_start >= ?
                GROUP BY sensor_installation_id, variable_id, date_trunc('day', bucket_start)
                ON CONFLICT (sensor_installation_id, variable_id, bucket_start) DO UPDATE
                SET avg_value = EXCLUDED.avg_value,
                    min_value = EXCLUDED.min_value,
                    max_value = EXCLUDED.max_value,
                    sample_count = EXCLUDED.sample_count
                """, cutoff);
    }
}
