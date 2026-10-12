package com.eduaircontrol.msmonitoring.monitoring.infrastructure.persistence;

import com.eduaircontrol.msmonitoring.shared.contract.InstallationProjectionPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura de la replica local de instalaciones.
 *
 * <p>La escritura vive en {@code InstallationProjectionDao}, que la hace el
 * consumidor de eventos; esta clase solo consulta, y va en la capa de persistencia
 * del dominio monitoring porque es {@code MeasurementService} quien la necesita.
 */
@Repository
@Transactional(readOnly = true)
public class InstallationProjectionAdapter implements InstallationProjectionPort {

    private final JdbcTemplate jdbc;

    public InstallationProjectionAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<InstallationState> findState(UUID sensorInstallationId) {
        return jdbc.query("""
                        select sensor_installation_id, removed_at
                        from environment_monitoring.installation_projection
                        where sensor_installation_id = ?
                        """,
                        (rs, rowNum) -> new InstallationState(
                                rs.getObject("sensor_installation_id", UUID.class),
                                rs.getTimestamp("removed_at") == null ? null
                                        : rs.getTimestamp("removed_at").toInstant()),
                        sensorInstallationId)
                .stream().findFirst();
    }
}
