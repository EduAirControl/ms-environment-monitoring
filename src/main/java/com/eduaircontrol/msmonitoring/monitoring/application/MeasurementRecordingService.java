
package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentMeasurement;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.MeasurementUseCase.RecordCommand;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.out.MeasurementRepository;
import com.eduaircontrol.msmonitoring.shared.contract.InstallationProjectionPort;
import com.eduaircontrol.msmonitoring.shared.tenant.TenantGuard;
import com.eduaircontrol.msmonitoring.shared.exception.ForbiddenException;
import com.eduaircontrol.msmonitoring.shared.exception.GoneException;
import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeasurementRecordingService {

    private final MeasurementRepository measurementRepository;
    private final InstallationProjectionPort installationProjectionPort;
    private final TenantGuard tenantGuard;
    private final MeasurementAlertingService alertingService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public UUID record(RecordCommand command, UUID callerSensorId) {
        if (command.value() == null) {
            throw new IllegalArgumentException("value es obligatorio");
        }

        requireActiveInstallation(command.sensorInstallationId(), callerSensorId);

        Instant measuredAt = command.measuredAt() != null
                ? command.measuredAt()
                : clock.instant();

        if (measurementRepository.existsByInstallationAndVariableAndInstant(
                command.sensorInstallationId(),
                command.variableId(),
                measuredAt)) {
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

        // La medición y su evaluación de alertas comparten la transacción.
        alertingService.evaluate(
                savedId,
                command.sensorInstallationId(),
                command.variableId(),
                command.value(),
                measuredAt);

        return savedId;
    }

    private void requireActiveInstallation(
            UUID sensorInstallationId,
            UUID callerSensorId) {

        InstallationProjectionPort.InstallationState state =
                installationProjectionPort.findState(sensorInstallationId)
                        .orElseThrow(() -> new GoneException(
                                "La instalacion no existe o fue purgada: "
                                        + sensorInstallationId
                                        + ". Reaprovisiona el dispositivo."));

        if (callerSensorId != null && !callerSensorId.equals(state.sensorId())) {
            throw new ForbiddenException(
                    "El token no corresponde al sensor de esta instalacion: "
                            + sensorInstallationId);
        }

        if (callerSensorId == null) {
            // Operador humano: no puede cargar datos en una instalacion de otra institucion.
            tenantGuard.requireEnvironment(state.educationalEnvironmentId());
        }

        if (!state.isActive()) {
            throw new GoneException(
                    "La instalacion esta cerrada y no admite mediciones: "
                            + sensorInstallationId
                            + ". Reaprovisiona el dispositivo.");
        }
    }

    private UUID qualityFlagId(String code) {
        return jdbc.query("""
                        select quality_flag_id
                        from environment_monitoring.quality_flag
                        where code = ?
                        """,
                        (rs, rowNum) -> rs.getObject("quality_flag_id", UUID.class),
                        code)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "quality_flag no sembrado: " + code));
    }
}
