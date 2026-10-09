package com.eduaircontrol.msmonitoring.monitoring.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Evalua una medicion contra el umbral vigente y genera la alerta si hay excedencia.
 *
 * <p>Vive fuera del consumidor de eventos a proposito: las mediciones entran por
 * HTTP ({@code POST /api/v1/measurements}, camino unico de ingesta) y la evaluacion
 * se dispara en ese mismo registro. Si dependiera de un mensaje del broker, una
 * medicion podria guardarse y nunca alertar.
 *
 * <p>Es idempotente por diseno de {@link AlertService#raiseIfAbsent}: si la medicion
 * ya genero una alerta activa, no se duplica.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeasurementAlertingService {

    private final ThresholdService thresholdService;
    private final AlertService alertService;
    private final JdbcTemplate jdbc;

    /**
     * Evalua la medicion recien guardada.
     *
     * <p>Silencioso cuando no puede atribuir la lectura: sin proyeccion de
     * instalacion o sin ambiente replicado la lectura no tiene ambiente que alertar
     * y fallar aqui haria que la ingesta entera devolviera error (limitacion
     * documentada en DEC-006).
     */
    public void evaluate(UUID measurementId, UUID sensorInstallationId,
                         UUID variableId, BigDecimal value, Instant measuredAt) {
        if (sensorInstallationId == null || variableId == null || value == null) {
            return;
        }
        UUID environmentId = environmentOf(sensorInstallationId);
        if (environmentId == null) {
            log.debug("Sin proyeccion para la instalacion {}; se omite la evaluacion",
                    sensorInstallationId);
            return;
        }
        UUID environmentTypeId = environmentTypeOf(environmentId);
        if (environmentTypeId == null) {
            log.debug("Ambiente {} sin tipo resuelto; se omite la evaluacion", environmentId);
            return;
        }

        thresholdService.evaluate(environmentTypeId, variableId, value,
                        measuredAt.atZone(ZoneOffset.UTC).toLocalDate())
                .ifPresent(evaluation -> alertService.raiseIfAbsent(
                        environmentId, variableId,
                        evaluation.thresholdId(), measurementId,
                        evaluation.severityCode(), measuredAt));
    }

    /**
     * Ambiente de la instalacion segun la proyeccion local.
     *
     * <p>La proyeccion la mantiene el consumidor de {@code sensor.installation.*} de
     * ms-sensor-management. Sin esa fila la lectura no se puede atribuir.
     */
    private UUID environmentOf(UUID sensorInstallationId) {
        return jdbc.query("""
                        select educational_environment_id
                        from environment_monitoring.installation_projection
                        where sensor_installation_id = ?
                          and removed_at is null
                        limit 1
                        """,
                        (rs, rowNum) -> rs.getObject("educational_environment_id", UUID.class),
                        sensorInstallationId)
                .stream().findFirst().orElse(null);
    }

    private UUID environmentTypeOf(UUID environmentId) {
        return jdbc.query("""
                        select environment_type_id
                        from environment_monitoring.educational_environment
                        where educational_environment_id = ?
                        """,
                        (rs, rowNum) -> rs.getObject("environment_type_id", UUID.class),
                        environmentId)
                .stream().findFirst().orElse(null);
    }
}
