package com.eduaircontrol.msmonitoring.infrastructure.messaging.classroom;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * DAO para la réplica local de ambientes educativos.
 *
 * <p>Trabaja contra el esquema canónico de
 * {@code db/changelog/canonical/03_tables/01_catalogos/002_educational_environment.sql}:
 * la columna es {@code educational_environment_id} y no hay columnas de estado ni
 * de sincronización. La replica es un catálogo de consulta (tipo de ambiente para
 * umbrales, nombre para etiquetar análisis), no un espejo del agregado.
 */
@Repository
@RequiredArgsConstructor
public class ClassroomEnvironmentDao {

    private final JdbcTemplate jdbc;

    /**
     * Upsert de un ambiente educativo en la réplica local.
     */
    @Transactional
    public void upsert(UUID environmentId, String code, String name, UUID campusId,
                       UUID environmentTypeId, Integer floor) {
        String sql = """
            INSERT INTO environment_monitoring.educational_environment
                (educational_environment_id, code, name, campus_id, environment_type_id, floor)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (educational_environment_id) DO UPDATE SET
                code = EXCLUDED.code,
                name = EXCLUDED.name,
                campus_id = EXCLUDED.campus_id,
                environment_type_id = EXCLUDED.environment_type_id,
                floor = EXCLUDED.floor
            """;
        jdbc.update(sql, environmentId, code, name, campusId, environmentTypeId, floor);
    }

    /**
     * Retira un ambiente de la réplica.
     *
     * <p>Es un borrado físico y no una marca: los lectores ({@code ThresholdAdapter},
     * {@code EnvironmentLookupAdapter}, {@code SensorInstallationListener}) consultan
     * la tabla sin filtro de estado, así que una fila marcada seguiría resolviéndose
     * para análisis nuevos. Borrar hace que el ambiente deje de existir para este
     * servicio, que es exactamente lo que significa que lo hayan eliminado aguas
     * arriba. Los análisis ya calculados conservan su
     * {@code educational_environment_id} como referencia lógica.
     */
    @Transactional
    public void markRemoved(UUID environmentId) {
        jdbc.update(
                "DELETE FROM environment_monitoring.educational_environment WHERE educational_environment_id = ?",
                environmentId);
    }
}
