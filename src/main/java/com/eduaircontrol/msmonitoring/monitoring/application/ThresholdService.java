package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.VariableThreshold;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluacion de umbrales (ADR-014 enmendado: override por ambiente).
 *
 * <p>Resuelve el umbral activo por (variable_id, severity_id) priorizando el
 * override del ambiente ({@code educational_environment_id}) y, si no existe,
 * el generico del tipo de ambiente. La semantica es:
 * <ul>
 *   <li>Dentro del rango INFO (normal) → sin alerta.</li>
 *   <li>Fuera de INFO pero dentro de WARNING → alerta WARNING.</li>
 *   <li>Fuera de WARNING → alerta CRITICAL.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ThresholdService {

    private final JdbcTemplate jdbc;

    /**
     * Evalua una medicion y devuelve la severidad que corresponde, o vacio si esta
     * dentro del rango normal.
     */
    @Transactional(readOnly = true)
    public Optional<Evaluation> evaluate(UUID environmentId, UUID environmentTypeId,
                                         UUID variableId, BigDecimal value, LocalDate date) {
        // Para las alertas solo interesa lo que sale del rango normal: un valor
        // OK no genera alerta. El panel, en cambio, necesita tambien el OK para
        // poder pintar el estado sin depender de que exista una alerta.
        return evaluateSeverity(environmentId, environmentTypeId, variableId, value, date)
                .filter(evaluation -> !"OK".equals(evaluation.severityCode()));
    }

    /**
     * Evalua una medicion y devuelve siempre su severidad cuando hay umbrales:
     * {@code OK}, {@code WARNING} o {@code CRITICAL}. Vacio solo si no hay
     * umbrales aplicables (o el valor es nulo), que la UI debe pintar como
     * desconocido.
     */
    @Transactional(readOnly = true)
    public Optional<Evaluation> evaluateSeverity(UUID environmentId, UUID environmentTypeId,
                                                 UUID variableId, BigDecimal value, LocalDate date) {
        if (value == null) {
            return Optional.empty();
        }

        List<ThresholdRow> thresholds = jdbc.query("""
                select t.variable_threshold_id, t.min_value, t.max_value,
                       s.code as severity, t.educational_environment_id
                from environment_monitoring.variable_threshold t
                join environment_monitoring.severity s on s.severity_id = t.severity_id
                where (t.valid_to is null or t.valid_to > ?)
                  and t.valid_from <= ?
                  and t.variable_id = ?
                  -- El umbral del tipo (sin ambiente) o el override DE ESTE ambiente:
                  -- el override de otro ambiente del mismo tipo no debe aplicar.
                  and ((t.educational_environment_id is null
                        and t.environment_type_id = ?)
                       or t.educational_environment_id = ?)
                """, (rs, rowNum) -> new ThresholdRow(
                rs.getObject("variable_threshold_id", UUID.class),
                rs.getBigDecimal("min_value"),
                rs.getBigDecimal("max_value"),
                rs.getString("severity"),
                rs.getObject("educational_environment_id", UUID.class) != null),
                date, date, variableId, environmentTypeId, environmentId);

        // El override del ambiente gana sobre el umbral del tipo.
        ThresholdRow info = bySeverity(thresholds, "INFO").orElse(null);
        ThresholdRow warning = bySeverity(thresholds, "WARNING").orElse(null);

        // Dentro del rango normal: OK.
        if (info != null && inside(value, info)) {
            return Optional.of(new Evaluation(info.id(), "OK", value));
        }
        // Dentro del rango de advertencia (pero fuera del normal): WARNING.
        if (warning != null && inside(value, warning)) {
            return Optional.of(new Evaluation(warning.id(), "WARNING", value));
        }
        // Fuera de todo lo conocido: CRITICAL. Se usa el umbral WARNING como
        // referencia para la trazabilidad, porque es el que define el limite.
        if (warning != null) {
            return Optional.of(new Evaluation(warning.id(), "CRITICAL", value));
        }
        // Sin umbrales configurados: no se puede evaluar.
        return Optional.empty();
    }

    private Optional<ThresholdRow> bySeverity(List<ThresholdRow> rows, String severity) {
        return rows.stream()
                .filter(row -> severity.equals(row.severity()))
                // Primera prioridad: override del ambiente; si no, umbral del tipo.
                .sorted((a, b) -> Boolean.compare(b.environmentScoped(), a.environmentScoped()))
                .findFirst();
    }

    private boolean inside(BigDecimal value, ThresholdRow threshold) {
        if (threshold.min() != null && value.compareTo(threshold.min()) < 0) {
            return false;
        }
        return threshold.max() == null || value.compareTo(threshold.max()) <= 0;
    }

    public record Evaluation(UUID thresholdId, String severityCode, BigDecimal value) {
    }

    private record ThresholdRow(UUID id, BigDecimal min, BigDecimal max, String severity,
                                boolean environmentScoped) {
    }
}
