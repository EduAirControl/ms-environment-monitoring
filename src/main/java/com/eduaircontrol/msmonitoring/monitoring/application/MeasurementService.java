package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentMeasurement;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.MeasurementUseCase;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.out.MeasurementRepository;
import com.eduaircontrol.msmonitoring.shared.contract.InstallationProjectionPort;
import com.eduaircontrol.msmonitoring.shared.contract.VariableCatalogPort;
import com.eduaircontrol.msmonitoring.shared.exception.ForbiddenException;
import com.eduaircontrol.msmonitoring.shared.exception.GoneException;
import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro y consulta de mediciones (HU-MON-001 … HU-MON-004).
 *
 * <p>El registro es idempotente por (instalacion, variable, instante): una
 * reingesta no duplica. La calidad por defecto es VALID; el backfill y el
 * consumidor resuelven el flag que corresponda.
 */
@Service
@RequiredArgsConstructor
public class MeasurementService implements MeasurementUseCase {

    private final MeasurementRepository measurementRepository;
    private final VariableCatalogPort variableCatalogPort;
    private final InstallationProjectionPort installationProjectionPort;
    private final MeasurementAlertingService alertingService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional
    public UUID record(RecordCommand command, UUID callerSensorId) {
        if (command.value() == null) {
            throw new IllegalArgumentException("value es obligatorio");
        }
        requireActiveInstallation(command.sensorInstallationId(), callerSensorId);
        Instant measuredAt = command.measuredAt() != null ? command.measuredAt() : clock.instant();
        if (measurementRepository.existsByInstallationAndVariableAndInstant(
                command.sensorInstallationId(), command.variableId(), measuredAt)) {
            return null;
        }
        EnvironmentMeasurement measurement = EnvironmentMeasurement.builder()
                .id(UUID.randomUUID())
                .sensorInstallationId(command.sensorInstallationId())
                .variableId(command.variableId())
                .measuredValue(command.value())
                .measuredAt(measuredAt)
                .qualityFlagId(qualityFlagId("VALID"))
                .createdAt(clock.instant())
                .build();
        UUID savedId = measurementRepository.save(measurement).getId();

        // La evaluacion de umbrales corre en la misma transaccion que el registro:
        // o queda la medicion con su alerta, o no queda ninguna de las dos.
        alertingService.evaluate(savedId, command.sensorInstallationId(),
                command.variableId(), command.value(), measuredAt);

        return savedId;
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
                        row.measuredAt()))
                .toList();
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

    /**
     * Rechaza mediciones contra una instalacion que ya no admite datos.
     *
     * <p>Es lo que evita que un nodo que sigue enviando tras ser desvinculado
     * ({@code POST /sensor-installations/{id}/remove}) o purgado
     * ({@code DELETE /sensor-installations/{id}}) llene la base de mediciones que
     * ninguna consulta devuelve: sin fila en la proyeccion no hay ambiente al que
     * atribuirlas.
     *
     * <p>410 y no 404: la instalacion existio, pero no volvera a estar activa.
     */
    /**
     * Comprueba que la instalacion exista, siga activa y, si quien llama es un
     * dispositivo, que sea SU instalacion.
     *
     * <p>Sin esa ultima comprobacion cualquier token de dispositivo podia escribir
     * en cualquier instalacion: solo hacia falta saber un installationId, que es
     * publico en la respuesta de alta. Con {@code callerSensorId} nulo (un
     * operador humano) no se exige pertenencia.
     */
    private void requireActiveInstallation(UUID sensorInstallationId, UUID callerSensorId) {
        InstallationProjectionPort.InstallationState state =
                installationProjectionPort.findState(sensorInstallationId)
                        .orElseThrow(() -> new GoneException(
                                "La instalacion no existe o fue purgada: " + sensorInstallationId
                                        + ". Reaprovisiona el dispositivo."));
        if (callerSensorId != null && !callerSensorId.equals(state.sensorId())) {
            throw new ForbiddenException(
                    "El token no corresponde al sensor de esta instalacion: " + sensorInstallationId);
        }
        if (!state.isActive()) {
            throw new GoneException(
                    "La instalacion esta cerrada y no admite mediciones: " + sensorInstallationId
                            + ". Reaprovisiona el dispositivo.");
        }
    }

    private String variableCodeOf(UUID variableId) {
        return variableCatalogPort.findById(variableId)
                .map(VariableCatalogPort.VariableRef::code)
                .orElse(variableId.toString());
    }

    private UUID qualityFlagId(String code) {
        return jdbc.query("""
                        select quality_flag_id from environment_monitoring.quality_flag
                        where code = ?
                        """,
                        (rs, rowNum) -> rs.getObject("quality_flag_id", UUID.class),
                        code)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("quality_flag no sembrado: " + code));
    }
}
