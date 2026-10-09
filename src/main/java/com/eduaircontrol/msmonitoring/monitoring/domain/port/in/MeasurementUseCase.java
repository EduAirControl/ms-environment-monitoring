package com.eduaircontrol.msmonitoring.monitoring.domain.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Casos de uso de mediciones (HU-MON-001, HU-MON-002, HU-MON-004).
 */
public interface MeasurementUseCase {

    /** Persiste una medicion aceptada y devuelve su id. */
    UUID record(RecordCommand command);

    /** Ultimos valores por variable de un ambiente (HU-MON-003). */
    List<CurrentValue> currentValues(UUID environmentId);

    /**
     * Ultima medicion por (ambiente, variable) de todos los ambientes.
     *
     * <p>Es lo que consume el panel web: sin esto, pintar el ranking obligaria a
     * una consulta por ambiente.
     */
    List<EnvironmentCurrentValue> currentValuesForAll();

    /** Historial paginado por variable y ventana (HU-MON-004). */
    List<MeasurementView> history(HistoryQuery query);

    /** Total de mediciones registradas. */
    long count();

    record RecordCommand(
            UUID sensorInstallationId,
            UUID variableId,
            BigDecimal value,
            Instant measuredAt) {
    }

    record CurrentValue(
            UUID variableId,
            String variableCode,
            BigDecimal value,
            Instant measuredAt) {
    }

    record EnvironmentCurrentValue(
            UUID environmentId,
            UUID variableId,
            String variableCode,
            BigDecimal value,
            Instant measuredAt) {
    }

    record HistoryQuery(
            UUID environmentId,
            UUID variableId,
            Instant from,
            Instant to,
            int page,
            int limit) {
    }

    record MeasurementView(
            UUID id,
            UUID variableId,
            String variableCode,
            BigDecimal value,
            Instant measuredAt) {
    }
}
