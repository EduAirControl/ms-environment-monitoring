package com.eduaircontrol.msmonitoring.infrastructure.messaging;

import com.eduaircontrol.msmonitoring.PostgresTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Base de los tests de mensajeria: PostgreSQL y RabbitMQ reales, con los listeners
 * montados por la propia aplicacion.
 *
 * <p>Se prueba contra el listener de verdad y no invocando el metodo a mano, porque
 * lo que importa es el comportamiento del contenedor: acknowledgement, routing y
 * destino de los fallos.
 *
 * <p>Cada test declara su propia topologia via {@code @DynamicPropertySource}
 * (instalaciones, aulas) y publica en su exchange. Aqui solo vive lo comun:
 * los contenedores y los identificadores de referencia.
 */
public abstract class ConsumerTestBase extends PostgresTestBase {

    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-alpine");

    protected static final UUID ENVIRONMENT_ID =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    protected static final UUID ENVIRONMENT_TYPE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000081");
    protected static final UUID INSTALLATION_ID =
            UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected InboxRepository inbox;

    @Autowired
    protected ConnectionFactory connectionFactory;

    @Autowired
    protected org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;

    @BeforeAll
    static void startRabbit() {
        if (!RABBIT.isRunning()) {
            RABBIT.start();
        }
    }

    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // Los tests de consumidor SI necesitan los listeners en marcha: el perfil
        // de test los deja parados para que el resto cargue sin broker.
        registry.add("app.messaging.listeners.auto-startup", () -> "true");
    }

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM environment_monitoring.environment_measurement");
        jdbc.update("DELETE FROM environment_monitoring.inbox_message");
        jdbc.update("DELETE FROM environment_monitoring.installation_projection");
        jdbc.update("DELETE FROM environment_monitoring.educational_environment");

        // Ambiente y proyeccion de referencia: los listeners los necesitan para
        // atribuir lecturas y resolver el tipo de ambiente.
        jdbc.update("""
                insert into environment_monitoring.educational_environment
                    (educational_environment_id, code, name, environment_type_id, floor)
                values (?, 'TEST-ENV', 'Ambiente de prueba', ?, 1)
                ON CONFLICT (educational_environment_id) DO NOTHING
                """, ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID);
        jdbc.update("""
                insert into environment_monitoring.installation_projection
                    (sensor_installation_id, educational_environment_id,
                     environment_type_id, sensor_id)
                values (?, ?, ?, ?)
                ON CONFLICT (sensor_installation_id) DO NOTHING
                """, INSTALLATION_ID, ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, UUID.randomUUID());
    }

    protected RabbitAdmin admin() {
        return new RabbitAdmin(connectionFactory);
    }

    protected int inboxCount() {
        Integer n = jdbc.queryForObject(
                "select count(*) from environment_monitoring.inbox_message", Integer.class);
        return n == null ? 0 : n;
    }

    protected long queueDepth(String queue) {
        return rabbit.execute(channel -> {
            try {
                var ok = channel.queueDeclarePassive(queue);
                return (long) ok.getMessageCount();
            } catch (Exception e) {
                return 0L;
            }
        });
    }

    protected boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(100);
        }
        return condition.getAsBoolean();
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
