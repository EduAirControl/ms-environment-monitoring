package com.eduaircontrol.msmonitoring.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato del evento {@code SensorInstalled} / {@code SensorRemoved} /
 * {@code SensorInstallationDeleted} publicado por ms-sensor-management.
 *
 * <p>Es una copia local del contrato del productor (ADR-016: contrato duplicado a
 * proposito, sin dependencia entre servicios). Los tres eventos comparten sobre y
 * solo se distinguen por el routing key y por {@code removedAt}.
 *
 * <p>{@code deleted} no es un cierre: es el borrado fisico de la instalacion, y el
 * consumidor debe purgar la proyeccion y las mediciones en vez de marcar
 * {@code removed_at}.
 *
 * <p><b>El payload no lleva environmentTypeId.</b> El productor no lo posee: solo
 * valida que el UUID del ambiente tenga forma correcta (DEC-007). Por eso
 * {@code environment_type_id} queda NULL en la proyeccion y lo resuelve quien lo
 * necesite contra {@code environment_monitoring.educational_environment}.
 */
public record SensorInstallationEvent(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        String aggregateType,
        Instant occurredAt,
        Integer version,
        Payload payload,
        Metadata metadata) {

    public static final String INSTALLED_TYPE = "SensorInstalled";

    public static final String REMOVED_TYPE = "SensorRemoved";

    public static final String DELETED_TYPE = "SensorInstallationDeleted";

    public static final int SCHEMA_VERSION = 1;

    public record Payload(
            UUID sensorInstallationId,
            UUID sensorId,
            UUID educationalEnvironmentId,
            Instant installedAt,
            Instant removedAt) {
    }

    public record Metadata(UUID correlationId, UUID causationId, UUID userId) {
    }

    public SensorInstallationEvent {
        if (version == null || version <= 0) {
            version = SCHEMA_VERSION;
        }
    }

    /** Nombre del consumidor en inbox_message, para no colisionar con el de mediciones. */
    public static final String CONSUMER = "installation-projection";
}