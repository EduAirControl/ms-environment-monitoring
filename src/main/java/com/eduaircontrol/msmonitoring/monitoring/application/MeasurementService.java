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
        return measurementRepository
                .history(query.environmentId(), query.variableId(),
                        query.from(), query.to(), query.page(), query.limit())
                .content().stream()
                .map(measurement -> new MeasurementView(
                        measurement.getId(),
                        measurement.getVariableId(),
                        variableCodeOf(measurement.getVariableId()),
                        measurement.getMeasuredValue(),
                        measurement.getMeasuredAt()))
                .toList();
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
