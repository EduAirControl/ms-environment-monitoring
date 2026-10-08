package com.eduaircontrol.msmonitoring.infrastructure.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escritura de la proyeccion local de instalaciones.
 *
 * <p>Es un UPSERT y no un INSERT: la entrega es at-least-once y una reentrega del
 * mismo evento debe ser un no-op, no una violacion de clave primaria.
 *
 * <p>Un cerrado y luego una reinstalacion sobre la misma instalacion no es un caso
 * raro (el sensor se retira y vuelve al mismo aula), asi que {@code removed_at} se
 * sobreescribe en cada evento en vez de acumularse.
 */
@Repository
public class InstallationProjectionDao {

    private static final String UPSERT = """
            INSERT INTO environment_monitoring.installation_projection
                (sensor_installation_id, educational_environment_id,
                 environment_type_id, sensor_id, removed_at, synced_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (sensor_installation_id) DO UPDATE SET
                educational_environment_id = EXCLUDED.educational_environment_id,
                environment_type_id        = EXCLUDED.environment_type_id,
                sensor_id                  = EXCLUDED.sensor_id,
                removed_at                 = EXCLUDED.removed_at,
                synced_at                  = now()
            """;

    private final JdbcTemplate jdbc;

    public InstallationProjectionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param environmentTypeId puede ser null: lo resuelve la replica local de
     *                          ambientes y este servicio no es su dueno.
     */
    @Transactional
    public void upsert(UUID sensorInstallationId, UUID educationalEnvironmentId,
                       UUID environmentTypeId, UUID sensorId, Instant removedAt) {
        // El Instant se convierte a Timestamp porque el driver de PostgreSQL no
        // sabe inferir el tipo SQL de un java.time.Instant y falla la sentencia.
        // Un null si se pasa tal cual no da problema, por eso el fallo solo
        // aparecia al cerrar una instalacion y no al crearla.
        Timestamp removed = removedAt == null ? null : Timestamp.from(removedAt);
        jdbc.update(UPSERT,
                sensorInstallationId,
                educationalEnvironmentId,
                environmentTypeId,
                sensorId,
                removed);
    }
}