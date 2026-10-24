package com.eduaircontrol.msmonitoring.monitoring.domain.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Casos de uso de alertas.
 */
public interface AlertUseCase {

    /** Lista alertas con filtros y paginacion. */
    List<AlertView> list(AlertQuery query);

    /** Reconoce una alerta activa. */
    AlertView acknowledge(UUID alertId, UUID acknowledgedBy, Instant at);

    /** Elimina (soft delete) una alerta: sale de la listas, la traza se conserva. */
    void delete(UUID alertId, Instant at);

    /**
     * Cierra (soft delete) las alertas vigentes de un ambiente+variable cuando
     * la medicion vuelve al rango normal.
     */
    void resolveOpen(UUID environmentId, UUID variableId);

    record AlertQuery(
            UUID environmentId,
            String status,
            Instant from,
            Instant to,
            int page,
            int limit) {
    }

    record AlertView(
            UUID id,
            UUID environmentId,
            UUID variableId,
            String variableCode,
            String severityCode,
            java.math.BigDecimal triggeringValue,
            java.math.BigDecimal thresholdMinValue,
            java.math.BigDecimal thresholdMaxValue,
            Instant raisedAt,
            Instant acknowledgedAt,
            UUID acknowledgedBy) {
    }
}
