package com.eduaircontrol.msmonitoring.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mantiene la proyeccion local de instalaciones al dia con los eventos que publica
 * ms-sensor-management (DEC-006 4.3).
 *
 * <p>Sin esta proyeccion las mediciones que publica el ESP32 se guardan pero no se
 * ven: {@code latestByEnvironment}, {@code history} y
 * {@code MeasurementAggregationDao} hacen JOIN contra
 * {@code environment_monitoring.installation_projection}, y el servicio no posee
 * {@code sensor_installation}.
 *
 * <p>Se declara en la MISMA transaccion que la marca en {@code inbox_message}: o
 * quedan ambos o ninguno, de modo que no puede quedar registrado un evento cuya
 * proyeccion no se escribio.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensorInstallationListener {

    private final InboxRepository inbox;
    private final InstallationProjectionDao projectionDao;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    @RabbitListener(
            queues = "${app.messaging.installations-queue}",
            containerFactory = "installationProjectionContainerFactory",
            autoStartup = "${app.messaging.listeners.auto-startup:true}")
    @Transactional
    public void onSensorInstallation(Message message) {
        SensorInstallationEvent event = parse(message);
        UUID eventId = resolveEventId(message, event);

        if (inbox.claim(eventId, SensorInstallationEvent.CONSUMER, event.eventType()) == 0) {
            log.debug("Evento {} ya procesado; se descarta la reentrega", eventId);
            return;
        }

        SensorInstallationEvent.Payload payload = event.payload();
        UUID environmentTypeId = environmentTypeOf(payload.educationalEnvironmentId());

        projectionDao.upsert(
                payload.sensorInstallationId(),
                payload.educationalEnvironmentId(),
                environmentTypeId,
                payload.sensorId(),
                payload.removedAt());

        log.debug("Evento {} procesado: instalacion {} -> ambiente {} (retirada: {})",
                eventId, payload.sensorInstallationId(), payload.educationalEnvironmentId(),
                payload.removedAt() != null);
    }

    private SensorInstallationEvent parse(Message message) {
        String body = message.getBody() == null ? null
                : new String(message.getBody(), StandardCharsets.UTF_8);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Evento de instalacion vacio");
        }
        SensorInstallationEvent event = jsonMapper.readValue(body, SensorInstallationEvent.class);
        if (event == null || event.payload() == null
                || event.payload().sensorInstallationId() == null) {
            throw new IllegalArgumentException("Evento sin sensorInstallationId: " + body);
        }
        return event;
    }

    /**
     * El {@code eventId} del cuerpo es el que vale, porque es el que el contrato
     * declara unico e idempotente. El metadato del broker queda de respaldo.
     */
    private UUID resolveEventId(Message message, SensorInstallationEvent event) {
        if (event.eventId() != null) {
            return event.eventId();
        }
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            try {
                return UUID.fromString(messageId);
            } catch (IllegalArgumentException e) {
                log.warn("messageId no es un UUID ({}); no se puede deduplicar", messageId);
            }
        }
        throw new IllegalArgumentException(
                "Evento sin eventId ni messageId: no se puede deduplicar");
    }

    /**
     * Tipo del ambiente desde la replica local.
     *
     * <p>Devuelve null si el ambiente aun no esta replicado. No es un error: la
     * proyeccion guardara environment_type_id NULL y las consultas que la necesitan
     * (umbrales, alertas) lo resuelven por {@code educational_environment}. Fallar
     * aqui mandaria a la cola de letras muertas una instalacion perfectamente valida
     * solo porque su ambiente todavia no llego.
     */
    private UUID environmentTypeOf(UUID environmentId) {
        if (environmentId == null) {
            return null;
        }
        return jdbc.query(
                        "select environment_type_id from environment_monitoring.educational_environment"
                                + " where educational_environment_id = ?",
                        (rs, rowNum) -> rs.getObject("environment_type_id", UUID.class),
                        environmentId)
                .stream().findFirst().orElse(null);
    }
}