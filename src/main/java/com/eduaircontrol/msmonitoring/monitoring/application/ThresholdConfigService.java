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

    private static final String SEVERITY_INFO = "INFO";
    private static final String SEVERITY_WARNING = "WARNING";

    private final JdbcTemplate jdbc;

    /**
     * Umbrales INFO (normal) y WARNING (advertencia) efectivos del ambiente,
     * por variable y severidad.
     *
     * <p>CRITICAL no se guarda: se deriva como «fuera del rango WARNING».
     */
    @Transactional(readOnly = true)
    public List<ThresholdView> effective(UUID environmentId) {
        UUID environmentTypeId = requireEnvironmentType(environmentId);
        List<ThresholdRow> rows = jdbc.query("""
                select v.code as variable_code, s.code as severity_code,
                       t.min_value, t.max_value, t.educational_environment_id
                from environment_monitoring.variable_threshold t
                join environment_monitoring.severity s on s.severity_id = t.severity_id
                join environment_monitoring.variable v on v.variable_id = t.variable_id
                where t.valid_to is null
                  and s.code in (?, ?)
                  -- El umbral del tipo (sin ambiente) o el override DE ESTE ambiente.
                  -- Sin el "educational_environment_id is null" en la primera rama,
                  -- el override de otro ambiente del mismo tipo se colaba aqui.
                  and ((t.educational_environment_id is null
                        and t.environment_type_id = ?)
                       or t.educational_environment_id = ?)
                order by v.code, s.level
                """, (rs, rowNum) -> new ThresholdRow(
                rs.getString("variable_code"),
                rs.getString("severity_code"),
                rs.getBigDecimal("min_value"),
                rs.getBigDecimal("max_value"),
                rs.getObject("educational_environment_id", UUID.class) != null),
                SEVERITY_INFO, SEVERITY_WARNING, environmentTypeId, environmentId);

        // La clave incluye la severidad: cada variable puede tener INFO y WARNING.
        Map<String, ThresholdRow> effective = new LinkedHashMap<>();
        rows.forEach(row -> effective.merge(
                row.variableCode() + "|" + row.severityCode(), row,
                (existing, incoming) -> incoming.environmentScoped() ? incoming : existing));

        List<ThresholdView> result = new ArrayList<>();
        effective.values().forEach(row -> result.add(new ThresholdView(
                row.variableCode(), row.severityCode(), row.min(), row.max(),
                row.environmentScoped() ? "ENVIRONMENT" : "TYPE")));
        return result;
    }

    /**
     * Crea o reemplaza el override WARNING del ambiente para una variable.
     * Se mantiene por compatibilidad con el endpoint antiguo.
     */
    @Transactional
    public ThresholdView updateWarning(UUID environmentId, String variableCode,
                                       BigDecimal minValue, BigDecimal maxValue) {
        return update(environmentId, variableCode, SEVERITY_WARNING, minValue, maxValue);
    }

    /**
     * Crea o reemplaza el override del ambiente para una variable y severidad.
     * Solo INFO y WARNING son editables; CRITICAL se deriva del WARNING.
     */
    @Transactional
    public ThresholdView update(UUID environmentId, String variableCode, String severityCode,
                                BigDecimal minValue, BigDecimal maxValue) {
        String severity = normalizeSeverity(severityCode);
        if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
            throw new IllegalArgumentException("El minimo no puede superar el maximo");
        }
        UUID environmentTypeId = requireEnvironmentType(environmentId);
        UUID variableId = requireVariable(variableCode);
        UUID severityId = requireSeverity(severity);

        validateAgainstEffective(environmentId, variableCode, severity, minValue, maxValue);

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

        return new ThresholdView(variableCode, severity, minValue, maxValue, "ENVIRONMENT");
    }

    /**
     * Guarda varios umbrales del ambiente en una sola transaccion. Los INFO se
     * aplican antes que los WARNING para que la validacion de contencion use el
     * rango normal nuevo.
     */
    @Transactional
    public List<ThresholdView> updateAll(UUID environmentId, List<ThresholdUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("Debe enviar al menos un umbral");
        }
        return updates.stream()
                .sorted((a, b) -> Integer.compare(severityOrder(a.severityCode()), severityOrder(b.severityCode())))
                .map(update -> update(environmentId, update.variableCode(),
                        update.severityCode(), update.minValue(), update.maxValue()))
                .toList();
    }

    private void validateAgainstEffective(UUID environmentId, String variableCode,
                                          String severityCode,
                                          BigDecimal minValue, BigDecimal maxValue) {
        Map<String, ThresholdView> current = new LinkedHashMap<>();
        effective(environmentId).stream()
                .filter(t -> variableCode.equals(t.variableCode()))
                .forEach(t -> current.put(t.severityCode(), t));

        ThresholdView info = SEVERITY_INFO.equals(severityCode)
                ? new ThresholdView(variableCode, SEVERITY_INFO, minValue, maxValue, "ENVIRONMENT")
                : current.get(SEVERITY_INFO);
        ThresholdView warning = SEVERITY_WARNING.equals(severityCode)
                ? new ThresholdView(variableCode, SEVERITY_WARNING, minValue, maxValue, "ENVIRONMENT")
                : current.get(SEVERITY_WARNING);

        // El rango normal debe quedar contenido en el de advertencia; si no, un
        // valor podria ser CRITICAL y OK a la vez segun el orden de evaluacion.
        if (info != null && warning != null) {
            if (info.minValue() != null && warning.minValue() != null
                    && info.minValue().compareTo(warning.minValue()) < 0) {
                throw new IllegalArgumentException(
                        "El minimo normal no puede ser menor que el minimo de advertencia");
            }
            if (info.maxValue() != null && warning.maxValue() != null
                    && info.maxValue().compareTo(warning.maxValue()) > 0) {
                throw new IllegalArgumentException(
                        "El maximo normal no puede ser mayor que el maximo de advertencia");
            }
        }
    }

    private String normalizeSeverity(String severityCode) {
        String severity = severityCode == null ? SEVERITY_WARNING : severityCode.toUpperCase();
        if (!SEVERITY_INFO.equals(severity) && !SEVERITY_WARNING.equals(severity)) {
            throw new IllegalArgumentException(
                    "Solo se pueden editar los umbrales INFO y WARNING; CRITICAL se deriva");
        }
        return severity;
    }

    private int severityOrder(String severityCode) {
        return SEVERITY_INFO.equalsIgnoreCase(severityCode) ? 0 : 1;
    }

    private UUID requireVariable(String variableCode) {
        UUID variableId = jdbc.query("""
                        select variable_id from environment_monitoring.variable
                        where code = ?
                        """,
                rs -> rs.next() ? rs.getObject("variable_id", UUID.class) : null,
                variableCode);
        if (variableId == null) {
            throw new NotFoundException("Variable no encontrada: " + variableCode);
        }
        return variableId;
    }

    private UUID requireSeverity(String severityCode) {
        UUID severityId = jdbc.query("""
                        select severity_id from environment_monitoring.severity
                        where code = ?
                        """,
                rs -> rs.next() ? rs.getObject("severity_id", UUID.class) : null,
                severityCode);
        if (severityId == null) {
            throw new NotFoundException("Severidad no encontrada: " + severityCode);
        }
        return severityId;
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

    public record ThresholdView(String variableCode, String severityCode,
                                BigDecimal minValue, BigDecimal maxValue, String source) {
    }

    public record ThresholdUpdate(String variableCode, String severityCode,
                                  BigDecimal minValue, BigDecimal maxValue) {
    }

    private record ThresholdRow(String variableCode, String severityCode,
                                BigDecimal min, BigDecimal max, boolean environmentScoped) {
    }
}
