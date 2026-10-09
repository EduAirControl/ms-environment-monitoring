package com.eduaircontrol.msmonitoring.infrastructure.messaging.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduaircontrol.msmonitoring.infrastructure.messaging.ConsumerTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

/**
 * Test del consumidor de aulas.
 *
 * <p>Comprueba lo que la réplica {@code educational_environment} promete: que los
 * tres tipos de evento aterricen en el handler correcto, que la reentrega no
 * duplique ni falle y que el borrado quite la fila.
 *
 * <p>Los tres eventos comparten cola y binding {@code classroom.educational_environment.*}.
 * Con tres {@code @RabbitListener} sobre esa misma cola, RabbitMQ reparte los
 * mensajes al azar entre ellos y un "created" podria aterrizar en el handler de
 * "removed": el primer test fallaria de forma intermitente con ese diseno, que es
 * exactamente el fallo silencioso que hay que cazar.
 */
class ClassroomEnvironmentListenerTest extends ConsumerTestBase {

    private static final String CLASSROOM_EXCHANGE = "eduaircontrol.classroom.test";
    private static final String CLASSROOM_QUEUE =
            CLASSROOM_EXCHANGE + ".ms-environment-monitoring";

    /**
     * La aplicacion declara exchange, cola con DLX y binding de aulas al arrancar.
     * Sin esto montaria los de produccion y el listener no veria lo que publica
     * este test.
     */
    @org.springframework.test.context.DynamicPropertySource
    static void classroomTopology(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.messaging.classroom.exchange", () -> CLASSROOM_EXCHANGE);
        registry.add("app.messaging.classroom.queue", () -> CLASSROOM_QUEUE);
    }

    /**
     * La topologia (exchange, cola con DLX, binding) ya la declara la propia
     * aplicacion al arrancar con las propiedades de test. Redeclararla aqui sin los
     * argumentos de letra muerta daria PRECONDITION_FAILED: solo se vacia.
     *
     * <p>Se vacia una vez por test y no en cada publicacion, porque hay tests que
     * publican varios eventos seguidos y limpiar la cola los descartaria.
     */
    @org.junit.jupiter.api.BeforeEach
    void purgeClassroomQueue() {
        admin().purgeQueue(CLASSROOM_QUEUE, false);
    }

    private static String routingKeyOf(String eventType) {
        return switch (eventType) {
            case ClassroomEnvironmentEvent.CREATED_TYPE -> "classroom.educational_environment.created";
            case ClassroomEnvironmentEvent.UPDATED_TYPE -> "classroom.educational_environment.updated";
            case ClassroomEnvironmentEvent.REMOVED_TYPE -> "classroom.educational_environment.removed";
            default -> throw new IllegalArgumentException(eventType);
        };
    }

    /**
     * Publica un evento con la forma del contrato de ms-classroom-management: sobre
     * con eventId/eventType/aggregateId/occurredAt/version y payload con los datos
     * del ambiente.
     */
    private void publish(String eventType, UUID eventId, UUID environmentId,
                         String code, String name, UUID campusId,
                         UUID environmentTypeId, Integer floor) {
        String body = """
                {"eventId":"%s","eventType":"%s","aggregateId":"%s",\
                "aggregateType":"EducationalEnvironment","occurredAt":"%s","version":1,\
                "payload":{"environmentId":"%s","code":"%s","name":"%s",\
                "campusId":"%s","environmentTypeId":"%s","floor":%s,"status":"ACTIVE"}}"""
                .formatted(
                        eventId,
                        eventType,
                        environmentId,
                        Instant.now(),
                        environmentId,
                        code,
                        name,
                        campusId,
                        environmentTypeId,
                        floor);

        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .build();
        rabbit.send(CLASSROOM_EXCHANGE, routingKeyOf(eventType), message);
    }

    private boolean exists(UUID environmentId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from environment_monitoring.educational_environment"
                        + " where educational_environment_id = ?",
                Integer.class, environmentId);
        return n != null && n >= 1;
    }

    private String nameOf(UUID environmentId) {
        return jdbc.queryForObject(
                "select name from environment_monitoring.educational_environment"
                        + " where educational_environment_id = ?",
                String.class, environmentId);
    }

    private UUID typeOf(UUID environmentId) {
        return jdbc.queryForObject(
                "select environment_type_id from environment_monitoring.educational_environment"
                        + " where educational_environment_id = ?",
                (rs, rowNum) -> rs.getObject("environment_type_id", UUID.class),
                environmentId);
    }

    @Test
    void createdEventLandsInTheReplica() {
        UUID environmentId = UUID.randomUUID();
        publish(ClassroomEnvironmentEvent.CREATED_TYPE, UUID.randomUUID(), environmentId,
                "A-101", "Aula 101", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);

        assertThat(await(() -> exists(environmentId), 10_000)).isTrue();
        assertThat(nameOf(environmentId)).isEqualTo("Aula 101");
        assertThat(typeOf(environmentId)).isEqualTo(ENVIRONMENT_TYPE_ID);
    }

    @Test
    void updatedEventOverwritesTheRow() {
        UUID environmentId = UUID.randomUUID();
        publish(ClassroomEnvironmentEvent.CREATED_TYPE, UUID.randomUUID(), environmentId,
                "A-101", "Aula 101", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);
        assertThat(await(() -> exists(environmentId), 10_000)).isTrue();

        publish(ClassroomEnvironmentEvent.UPDATED_TYPE, UUID.randomUUID(), environmentId,
                "A-101", "Aula 101 renovada", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 2);

        assertThat(await(() -> "Aula 101 renovada".equals(nameOf(environmentId)), 10_000)).isTrue();
        assertThat(exists(environmentId)).isTrue();
    }

    @Test
    void removedEventTakesTheRowOutOfTheReplica() {
        UUID environmentId = UUID.randomUUID();
        publish(ClassroomEnvironmentEvent.CREATED_TYPE, UUID.randomUUID(), environmentId,
                "A-101", "Aula 101", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);
        assertThat(await(() -> exists(environmentId), 10_000)).isTrue();

        publish(ClassroomEnvironmentEvent.REMOVED_TYPE, UUID.randomUUID(), environmentId,
                "A-101", "Aula 101", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);

        assertThat(await(() -> !exists(environmentId), 10_000)).isTrue();
    }

    @Test
    void redeliveryOfTheSameEventIsIgnored() {
        UUID eventId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        publish(ClassroomEnvironmentEvent.CREATED_TYPE, eventId, environmentId,
                "A-101", "Aula 101", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);

        assertThat(await(() -> exists(environmentId), 10_000)).isTrue();
        Integer claimed = jdbc.queryForObject(
                "select count(*) from environment_monitoring.inbox_message where message_id = ?",
                Integer.class, eventId);
        assertThat(claimed).isEqualTo(1);

        // Mismo eventId: el inbox debe cortarlo sin fallar.
        publish(ClassroomEnvironmentEvent.CREATED_TYPE, eventId, environmentId,
                "A-101", "Aula 101", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);
        sleep(2000);

        assertThat(exists(environmentId)).isTrue();
        Integer after = jdbc.queryForObject(
                "select count(*) from environment_monitoring.inbox_message where message_id = ?",
                Integer.class, eventId);
        assertThat(after).isEqualTo(1);
    }

    /**
     * Regresion del bug de los tres listeners: cada tipo debe caer en su handler.
     * Se publican los tres y se comprueba el resultado final de cada uno.
     */
    @Test
    void eachEventTypeReachesItsOwnHandler() {
        UUID created = UUID.randomUUID();
        UUID updated = UUID.randomUUID();
        UUID removed = UUID.randomUUID();

        publish(ClassroomEnvironmentEvent.CREATED_TYPE, UUID.randomUUID(), created,
                "A-1", "Solo creado", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);
        publish(ClassroomEnvironmentEvent.UPDATED_TYPE, UUID.randomUUID(), updated,
                "A-2", "Solo actualizado", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);
        publish(ClassroomEnvironmentEvent.CREATED_TYPE, UUID.randomUUID(), removed,
                "A-3", "Para borrar", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);

        assertThat(await(() -> exists(created) && exists(updated) && exists(removed), 10_000)).isTrue();

        publish(ClassroomEnvironmentEvent.REMOVED_TYPE, UUID.randomUUID(), removed,
                "A-3", "Para borrar", ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, 1);

        assertThat(await(() -> exists(created) && exists(updated) && !exists(removed), 10_000)).isTrue();
    }


}
