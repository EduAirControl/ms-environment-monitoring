package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura y escritura de umbrales por ambiente (enmienda a ADR-014).
 *
 * <p>El umbral efectivo de un ambiente es su override WARNING
 * ({@code educational_environment_id} con valor) si existe; si no, el WARNING
 * generico del tipo de ambiente. El PUT crea o reemplaza el override del
 * ambiente para la variable indicada: la fila anterior se cierra con
 * {@code valid_to} (los umbrales nunca se borran).
 */
@Service
@RequiredArgsConstructor
public class ThresholdConfigService {

    private static final String SEVERITY_WARNING = "WARNING";

    private final JdbcTemplate jdbc;

    /** Umbrales WARNING efectivos del ambiente, por variable. */
    @Transactional(readOnly = true)
    public List<ThresholdView> effective(UUID environmentId) {
        UUID environmentTypeId = requireEnvironmentType(environmentId);
        List<ThresholdRow> rows = jdbc.query("""
                select v.code as variable_code, t.min_value, t.max_value,
                       t.educational_environment_id
                from environment_monitoring.variable_threshold t
                join environment_monitoring.severity s on s.severity_id = t.severity_id
                join environment_monitoring.variable v on v.variable_id = t.variable_id
                where t.valid_to is null
                  and s.code = ?
                  -- El umbral del tipo (sin ambiente) o el override DE ESTE ambiente.
                  -- Sin el "educational_environment_id is null" en la primera rama,
                  -- el override de otro ambiente del mismo tipo se colaba aqui.
                  and ((t.educational_environment_id is null
                        and t.environment_type_id = ?)
                       or t.educational_environment_id = ?)
                order by v.code
                """, (rs, rowNum) -> new ThresholdRow(
                rs.getString("variable_code"),
                rs.getBigDecimal("min_value"),
                rs.getBigDecimal("max_value"),
                rs.getObject("educational_environment_id", UUID.class) != null),
                SEVERITY_WARNING, environmentTypeId, environmentId);

        Map<String, ThresholdRow> byVariable = new LinkedHashMap<>();
        rows.forEach(row -> byVariable.merge(
                row.variableCode(), row,
                (existing, incoming) -> incoming.environmentScoped() ? incoming : existing));

        List<ThresholdView> result = new ArrayList<>();
        byVariable.forEach((code, row) -> result.add(new ThresholdView(
                code, row.min(), row.max(), row.environmentScoped() ? "ENVIRONMENT" : "TYPE")));
        return result;
    }

    /**
     * Crea o reemplaza el override WARNING del ambiente para una variable.
     * Devuelve el umbral efectivo resultante.
     */
    @Transactional
    public ThresholdView updateWarning(UUID environmentId, String variableCode,
                                       BigDecimal minValue, BigDecimal maxValue) {
        if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
            throw new IllegalArgumentException("El minimo no puede superar el maximo");
        }
        UUID environmentTypeId = requireEnvironmentType(environmentId);
        UUID variableId = jdbc.query("""
                        select variable_id from environment_monitoring.variable
                        where code = ?
                        """,
                rs -> rs.next() ? rs.getObject("variable_id", UUID.class) : null,
                variableCode);
        if (variableId == null) {
            throw new NotFoundException("Variable no encontrada: " + variableCode);
        }
        UUID severityId = jdbc.query("""
                        select severity_id from environment_monitoring.severity
                        where code = ?
                        """,
                rs -> rs.next() ? rs.getObject("severity_id", UUID.class) : null,
                SEVERITY_WARNING);
        if (severityId == null) {
            throw new NotFoundException("Severidad no encontrada: " + SEVERITY_WARNING);
        }

        LocalDate today = LocalDate.now();
        // Se cierra el override vigente; los umbrales con historia no se borran.
        // El constraint ck_variable_threshold_validity exige valid_from < valid_to,
        // asi que una fila creada hoy (sin dia cerrado) se elimina y una con
        // historia se cierra con valid_to = hoy.
        jdbc.update("""
                delete from environment_monitoring.variable_threshold
                where valid_to is null
                  and educational_environment_id = ?
                  and variable_id = ?
                  and severity_id = ?
                  and valid_from >= ?
                """, environmentId, variableId, severityId, today);
        jdbc.update("""
                update environment_monitoring.variable_threshold
                set valid_to = ?
                where valid_to is null
                  and educational_environment_id = ?
                  and variable_id = ?
                  and severity_id = ?
                """, today, environmentId, variableId, severityId);

        jdbc.update("""
                insert into environment_monitoring.variable_threshold
                    (variable_threshold_id, variable_id, environment_type_id,
                     educational_environment_id, min_value, max_value,
                     severity_id, valid_from)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), variableId, environmentTypeId,
                environmentId, minValue, maxValue, severityId, today);

        return new ThresholdView(variableCode, minValue, maxValue, "ENVIRONMENT");
    }

    private UUID requireEnvironmentType(UUID environmentId) {
        UUID environmentTypeId = jdbc.query("""
                        select environment_type_id
                        from environment_monitoring.educational_environment
                        where educational_environment_id = ?
                        """,
                rs -> rs.next() ? rs.getObject("environment_type_id", UUID.class) : null,
                environmentId);
        if (environmentTypeId == null) {
            throw new NotFoundException("Ambiente no encontrado");
        }
        return environmentTypeId;
    }

    public record ThresholdView(String variableCode, BigDecimal minValue, BigDecimal maxValue,
                                String source) {
    }

    private record ThresholdRow(String variableCode, BigDecimal min, BigDecimal max,
                                boolean environmentScoped) {
    }
}
