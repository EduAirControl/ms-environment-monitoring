package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.MeasurementUseCase;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.out.MeasurementRepository;
import com.eduaircontrol.msmonitoring.shared.contract.VariableCatalogPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro y consulta de mediciones (HU-MON-001 … HU-MON-004).
 *
 * <p>El registro es idempotente por (instalacion, variable, instante):
 * una reingesta no duplica. La calidad por defecto es VALID.
 */
@Service
@RequiredArgsConstructor
public class MeasurementService implements MeasurementUseCase {

    private final MeasurementRepository measurementRepository;
    private final VariableCatalogPort variableCatalogPort;
    private final MeasurementRecordingService recordingService;
    private final ThresholdService thresholdService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    public UUID record(RecordCommand command, UUID callerSensorId) {
        return recordingService.record(command, callerSensorId);
    }

    @Override
    public BatchResult recordBatch(List<RecordCommand> commands, UUID callerSensorId) {
        List<BatchItemResult> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;

        for (int i = 0; i < commands.size(); i++) {
            RecordCommand command = commands.get(i);

            try {
                if (command.value() == null) {
                    results.add(new BatchItemResult(i, "rejected", null,
                            "VALIDATION_ERROR", "value es obligatorio"));
                    rejected++;
                    continue;
                }

                if (command.measuredAt() != null) {
                    Instant now = Instant.now();

                    if (command.measuredAt().isBefore(now.minus(24, ChronoUnit.HOURS))
                            || command.measuredAt().isAfter(now.plus(5, ChronoUnit.MINUTES))) {
                        results.add(new BatchItemResult(i, "rejected", null,
                                "VALIDATION_ERROR", "measuredAt fuera de rango admitido"));
                        rejected++;
                        continue;
                    }
                }

                UUID id = recordingService.record(command, callerSensorId);

                if (id == null) {
                    results.add(new BatchItemResult(i, "duplicate", null, null, null));
                    duplicates++;
                } else {
                    results.add(new BatchItemResult(i, "accepted", id, null, null));
                    accepted++;
                }

            } catch (Exception e) {
                results.add(new BatchItemResult(i, "rejected", null, "ERROR",
                        e.getMessage() != null ? e.getMessage() : "error desconocido"));
                rejected++;
            }
        }

        return new BatchResult(
                commands.size(), accepted, duplicates, rejected, results);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CurrentValue> currentValues(UUID environmentId) {
        return measurementRepository.latestByEnvironment(environmentId).stream()
                .map(measurement -> new CurrentValue(
                        measurement.getVariableId(),
                        variableCodeOf(measurement.getVariableId()),
                        measurement.getMeasuredValue(),
                        measurement.getMeasuredAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnvironmentCurrentValue> currentValuesForAll() {
        return measurementRepository.latestAll().stream()
                .map(row -> new EnvironmentCurrentValue(
                        row.environmentId(),
                        row.variableId(),
                        variableCodeOf(row.variableId()),
                        row.value(),
                        row.measuredAt(),
                        severityCode(row.environmentId(), row.environmentTypeId(),
                                row.variableId(), row.value(), row.measuredAt())))
                .toList();
    }

    /**
     * Severidad de la última medición con los umbrales vigentes del ambiente.
     *
     * <p>El estado del panel no puede depender de las alertas: reconocer una
     * alerta no hace que el ambiente vuelva a estar OK, y un cambio de umbral
     * debe reflejarse sin esperar a la siguiente medición. Por eso se evalúa la
     * lectura actual contra el umbral actual.
     */
    private String severityCode(UUID environmentId, UUID environmentTypeId,
                                UUID variableId, BigDecimal value, Instant measuredAt) {
        if (environmentTypeId == null || measuredAt == null) {
            return null;
        }

        return thresholdService.evaluateSeverity(
                        environmentId,
                        environmentTypeId,
                        variableId,
                        value,
                        measuredAt.atZone(ZoneOffset.UTC).toLocalDate())
                .map(ThresholdService.Evaluation::severityCode)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MeasurementView> history(HistoryQuery query) {
        Instant from = query.from() != null ? query.from() : Instant.EPOCH;
        Instant to = query.to() != null ? query.to() : Instant.now();
        long days = java.time.Duration.between(from, to).toDays();

        if (days <= 1) {
            // Raw measurements for short ranges
            return measurementRepository
                    .history(query.environmentId(), query.variableId(),
                            from, to, query.page(), query.limit())
                    .content().stream()
                    .map(measurement -> new MeasurementView(
                            measurement.getId(),
                            measurement.getVariableId(),
                            variableCodeOf(measurement.getVariableId()),
                            measurement.getMeasuredValue(),
                            measurement.getMeasuredAt()))
                    .toList();
        }

        // Aggregated data for larger ranges
        String table;
        if (days <= 7) {
            table = "measurement_agg_5m";
        } else if (days <= 90) {
            table = "measurement_agg_1h";
        } else {
            table = "measurement_agg_1d";
        }

        StringBuilder sql = new StringBuilder(
                "SELECT a.sensor_installation_id, a.variable_id, a.bucket_start, a.avg_value " +
                        "FROM environment_monitoring." + table + " a ");
        List<Object> params = new ArrayList<>();
        // Filter by environment via installation_projection
        if (query.environmentId() != null) {
            sql.append("JOIN environment_monitoring.installation_projection p " +
                    "ON p.sensor_installation_id = a.sensor_installation_id " +
                    "WHERE p.educational_environment_id = ? ");
            params.add(query.environmentId());
        } else {
            sql.append("WHERE 1=1 ");
        }
        if (query.variableId() != null) {
            sql.append("AND a.variable_id = ? ");
            params.add(query.variableId());
        }
        sql.append("AND a.bucket_start >= ? AND a.bucket_start <= ? ");
        params.add(java.sql.Timestamp.from(from));
        params.add(java.sql.Timestamp.from(to));
        sql.append("ORDER BY a.bucket_start LIMIT ? OFFSET ?");
        params.add(query.limit());
        params.add((query.page() - 1) * query.limit());

        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            java.sql.Timestamp ts = rs.getTimestamp("bucket_start");
            if (ts == null) return null;
            UUID varId = rs.getObject("variable_id", UUID.class);
            return new MeasurementView(
                    null,
                    varId,
                    varId != null ? variableCodeOf(varId) : null,
                    rs.getBigDecimal("avg_value"),
                    ts.toInstant());
        }, params.toArray()).stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return measurementRepository.count();
    }

    private String variableCodeOf(UUID variableId) {
        return variableCatalogPort.findById(variableId)
                .map(VariableCatalogPort.VariableRef::code)
                .orElse(variableId.toString());
    }
}
