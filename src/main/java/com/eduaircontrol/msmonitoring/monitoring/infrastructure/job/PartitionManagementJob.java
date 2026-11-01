package com.eduaircontrol.msmonitoring.monitoring.infrastructure.job;

import java.time.YearMonth;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de gestion de particiones (ADR-020).
 *
 * <p>Diariamente crea particiones mensuales para los proximos 3 meses si no
 * existen, usando la funcion PL/pgSQL ensure_partition (sin SQL dinamico en Java).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionManagementJob {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 1 * * *")
    public void ensureFuturePartitions() {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i < 3; i++) {
            YearMonth ym = now.plusMonths(i);
            String partitionName = "environment_measurement_" + ym.getYear() + "_" +
                    String.format("%02d", ym.getMonthValue());
            String fromLiteral = ym.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
            String toLiteral = ym.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
            try {
                jdbc.update("SELECT environment_monitoring.ensure_partition(?, ?, ?)",
                        partitionName, fromLiteral, toLiteral);
                log.info("Ensured partition: {}", partitionName);
            } catch (Exception e) {
                log.debug("Partition {} already exists or error: {}", partitionName, e.getMessage());
            }
        }
    }
}
