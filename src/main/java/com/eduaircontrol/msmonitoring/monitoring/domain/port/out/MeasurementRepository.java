package com.eduaircontrol.msmonitoring.monitoring.domain.port.out;

import com.eduaircontrol.msmonitoring.analysis.domain.model.PageResult;
import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentMeasurement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistencia de mediciones.
 */
public interface MeasurementRepository {

    EnvironmentMeasurement save(EnvironmentMeasurement measurement);

    boolean existsByInstallationAndVariableAndInstant(UUID installationId, UUID variableId,
                                                     Instant measuredAt);

    Optional<EnvironmentMeasurement> findById(UUID id);

    List<EnvironmentMeasurement> latestByEnvironment(UUID environmentId);

    /**
     * Ultima medicion por (ambiente, variable) de todos los ambientes.
     *
     * <p>Es lo que el panel necesita para pintar el ranking sin hacer una consulta
     * por ambiente.
     */
    List<EnvironmentCurrent> latestAll();

    PageResult<EnvironmentMeasurement> history(UUID environmentId, UUID variableId,
                                               Instant from, Instant to, int page, int limit);

    long count();

    /** Ultima medicion de una variable en un ambiente. */
    record EnvironmentCurrent(UUID environmentId, UUID variableId,
                              java.math.BigDecimal value, Instant measuredAt) {
    }
}
