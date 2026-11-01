
package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentMeasurement;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.MeasurementUseCase.RecordCommand;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.out.MeasurementRepository;
import com.eduaircontrol.msmonitoring.shared.contract.InstallationProjectionPort;
import com.eduaircontrol.msmonitoring.shared.tenant.TenantGuard;
import com.eduaircontrol.msmonitoring.shared.exception.ForbiddenException;
import com.eduaircontrol.msmonitoring.shared.exception.GoneException;
import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import com.eduaircontrol.msmonitoring.shared.exception.ValidationException;
import java.time.Clock;
import java.time.Duration;
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

    private static final Duration PAST_WINDOW = Duration.ofHours(24);
    private static final Duration FUTURE_WINDOW = Duration.ofMinutes(5);

    @Transactional
    public UUID record(RecordCommand command, UUID callerSensorId) {
        if (command.value() == null) {
            throw new IllegalArgumentException("value es obligatorio");
        }

        requireActiveInstallation(command.sensorInstallationId(), callerSensorId);
        requireMeasuredAtInWindow(command.measuredAt());

        Instant measuredAt = command.measuredAt() != null
                ? command.measuredAt()
                : clock.instant();

        EnvironmentMeasurement measurement = EnvironmentMeasurement.builder()
                .id(UUID.randomUUID())
                .sensorInstallationId(command.sensorInstallationId())
                .variableId(command.variableId())
                .measuredValue(command.value())
                .measuredAt(measuredAt)
                .qualityFlagId(qualityFlagId("VALID"))
                .createdAt(clock.instant())
                .build();

        // Un duplicado es un exito sin efecto: no se reevaluan alertas.
        if (!measurementRepository.insertIfAbsent(measurement)) {
            return null;
        }

        UUID savedId = measurement.getId();

        // La medición y su evaluación de alertas comparten la transacción.
        alertingService.evaluate(
                savedId,
                command.sensorInstallationId(),
                command.variableId(),
                command.value(),
                measuredAt);

        return savedId;
    }

    /**
     * Acota la fecha de la medicion a {@code [ahora-24h, ahora+5min]} (ADR-021).
     *
     * <p>Sin esto el cliente controla por completo {@code measuredAt} y puede retroactivar
     * o adelantar datos a voluntad. Una fecha ausente se acepta y toma la hora del servidor.
     */
    public void requireMeasuredAtInWindow(Instant measuredAt) {
        if (measuredAt == null) {
            return;
        }
        Instant now = clock.instant();
        if (measuredAt.isBefore(now.minus(PAST_WINDOW))
                || measuredAt.isAfter(now.plus(FUTURE_WINDOW))) {
            throw new ValidationException("measuredAt fuera de rango admitido");
        }
    }

    /**
     * Comprueba que la instalacion exista, este activa y pertenezca al llamador.
     *
     * <p>Un lote lo exige antes de procesar cualquier item: 403 y 410 describen a la
     * instalacion, no a una lectura, y el nodo debe dejar de enviar en vez de borrar datos.
     */
    public void requireActiveInstallation(
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
