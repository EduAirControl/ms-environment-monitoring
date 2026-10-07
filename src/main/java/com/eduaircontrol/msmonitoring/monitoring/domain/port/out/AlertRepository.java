package com.eduaircontrol.msmonitoring.monitoring.domain.port.out;

import com.eduaircontrol.msmonitoring.analysis.domain.model.PageResult;
import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentAlert;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistencia de alertas.
 */
public interface AlertRepository {

    EnvironmentAlert save(EnvironmentAlert alert);

    Optional<EnvironmentAlert> findById(UUID id);

    PageResult<EnvironmentAlert> search(UUID environmentId, Boolean active,
                                        java.time.Instant from, java.time.Instant to,
                                        int page, int limit);

    List<EnvironmentAlert> findActiveByMeasurement(UUID measurementId);
}
