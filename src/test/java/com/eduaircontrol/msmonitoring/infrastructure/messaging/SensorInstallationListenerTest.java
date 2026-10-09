package com.eduaircontrol.msmonitoring.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

/**
 * Test del consumidor de instalaciones.
 *
 * <p>Comprueba lo que impide que las mediciones se vean: que un SensorInstalled
 * aterrice en installation_projection, que una reentrega no duplique fila y que un
 * cierre marque removed_at (que es lo que hace que los JOIN con
 * {@code removed_at is null} dejen de contar esa instalacion).
 *
 * <p>Reutiliza la topologia Exchange/Queue de la base: si el productor cambiara la
 * routing key, este test dejaria de recibir el mensaje y fallaria, que es
 * exactamente el fallo silencioso que hay que cazar.
 */
class SensorInstallationListenerTest extends ConsumerTestBase {

    private static final String INSTALLATIONS_EXCHANGE = "eduaircontrol.sensor.test";
    private static final String INSTALLATIONS_QUEUE =
            INSTALLATIONS_EXCHANGE + ".ms-environment-monitoring";

    /**
     * La aplicacion declara exchange, cola y binding de instalaciones al arrancar.
     * Sin esto montaria los de produccion y el listener no veria lo que publica
     * este test.
     */
    @org.springframework.test.context.DynamicPropertySource
    static void installationTopology(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.messaging.installations-exchange", () -> INSTALLATIONS_EXCHANGE);
        registry.add("app.messaging.installations-queue", () -> INSTALLATIONS_QUEUE);
    }

    private void publishInstallation(UUID eventId, UUID installationId, UUID environmentId,
                                    Instant removedAt) {
        // La topologia (exchange, cola con DLX, binding) ya la declara la propia
        // aplicacion al arrancar con las propiedades de test. Redeclararla aqui
        // sin los argumentos de letra muerta daria PRECONDITION_FAILED.
        admin().purgeQueue(INSTALLATIONS_QUEUE, false);

        String body = """
                {"eventId":"%s","eventType":"%s","aggregateId":"%s",\
                "aggregateType":"SensorInstallation","occurredAt":"%s","version":1,\
                "payload":{"sensorInstallationId":"%s","sensorId":"%s",\
                "educationalEnvironmentId":"%s","installedAt":"%s"%s},\
                "metadata":{"correlationId":null,"causationId":null,"userId":null}}"""
                .formatted(
                        eventId,
                        removedAt == null ? "SensorInstalled" : "SensorRemoved",
                        installationId,
                        Instant.now(),
                        installationId,
                        UUID.randomUUID(),
                        environmentId,
                        Instant.now().minusSeconds(600),
                        removedAt == null ? "" : ",\"removedAt\":\"" + removedAt + "\"");

        Message message = MessageBuilder.withBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .build();
        rabbit.send(INSTALLATIONS_EXCHANGE,
                removedAt == null ? "sensor.installation.created" : "sensor.installation.closed",
                message);
    }

    /**
     * Filas de MI instalacion, no todas: ConsumerTestBase deja una fila propia y
     * contar todo haria que el await pasara antes de que llegara el evento.
     */
    private int rowsOf(java.util.UUID installationId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from environment_monitoring.installation_projection"
                        + " where sensor_installation_id = ?",
                Integer.class, installationId);
        return n == null ? 0 : n;
    }

    private boolean projected(java.util.UUID installationId) {
        return rowsOf(installationId) >= 1;
    }

    private java.util.UUID environmentTypeOf(java.util.UUID installationId) {
        List<java.util.UUID> types = jdbc.query(
                "select environment_type_id from environment_monitoring.installation_projection"
                        + " where sensor_installation_id = ?",
                (rs, rowNum) -> rs.getObject("environment_type_id", java.util.UUID.class),
                installationId);
        return types.isEmpty() ? null : types.get(0);
    }

    @Test
    void installedEventLandsInTheProjection() {
        UUID installationId = UUID.randomUUID();
        publishInstallation(UUID.randomUUID(), installationId, ENVIRONMENT_ID, null);

        assertThat(await(() -> projected(installationId), 10_000)).isTrue();
        assertThat(environmentTypeOf(installationId)).isEqualTo(ENVIRONMENT_TYPE_ID);
    }

    @Test
    void redeliveryDoesNotDuplicateTheRow() {
        UUID eventId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        publishInstallation(eventId, installationId, ENVIRONMENT_ID, null);

        assertThat(await(() -> projected(installationId), 10_000)).isTrue();
        assertThat(rowsOf(installationId)).isEqualTo(1);

        // Mismo eventId: el inbox debe cortarlo.
        publishInstallation(eventId, installationId, ENVIRONMENT_ID, null);
        sleep(2000);

        assertThat(rowsOf(installationId)).isEqualTo(1);
    }

    @Test
    void removalMarksRemovedAt() {
        UUID installationId = UUID.randomUUID();
        publishInstallation(UUID.randomUUID(), installationId, ENVIRONMENT_ID, null);
        assertThat(await(() -> projected(installationId), 10_000)).isTrue();

        publishInstallation(UUID.randomUUID(), installationId, ENVIRONMENT_ID, Instant.now());
        assertThat(await(() -> {
            Instant removed = jdbc.queryForObject(
                    "select removed_at from environment_monitoring.installation_projection"
                            + " where sensor_installation_id = ?",
                    (rs, rowNum) -> rs.getTimestamp("removed_at") == null ? null
                            : rs.getTimestamp("removed_at").toInstant(),
                    installationId);
            return removed != null;
        }, 10_000)).isTrue();
    }

    @Test
    void unknownEnvironmentDoesNotBlockTheProjection() {
        UUID installationId = UUID.randomUUID();
        UUID ambienteInexistente = UUID.randomUUID();
        publishInstallation(UUID.randomUUID(), installationId, ambienteInexistente, null);

        // No debe fallar: environment_type_id queda NULL y la fila se guarda igual.
        assertThat(await(() -> projected(installationId), 10_000)).isTrue();
        assertThat(environmentTypeOf(installationId)).isNull();
    }


}