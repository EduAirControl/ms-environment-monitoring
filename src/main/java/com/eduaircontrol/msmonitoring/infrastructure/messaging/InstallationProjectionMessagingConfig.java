package com.eduaircontrol.msmonitoring.infrastructure.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia del consumidor de instalaciones.
 *
 * <p>Es un exchange propio ({@code eduaircontrol.sensor}), no el de mediciones: el
 * productor es otro servicio y el grano del mensaje es otro. Mezclarlos obligaria al
 * productor de instalaciones a conocer el dominio ambiental.
 *
 * <p>Exchange, colas y binding se declaran desde el consumidor y no desde el
 * productor: quien recibe el mensaje es quien sabe que lo necesita.
 */
@Configuration
public class InstallationProjectionMessagingConfig {

    public static final String DLQ_SUFFIX = ".dlq";

    /** Routing key con el que los fallos llegan a la cola de letras muertas. */
    public static final String DLQ_ROUTING_KEY = "sensor.installation.failed";

    @Bean
    public TopicExchange sensorEventsExchange(
            @Value("${app.messaging.installations-exchange:eduaircontrol.sensor}")
            String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean
    public TopicExchange sensorEventsDlxExchange(
            @Value("${app.messaging.installations-exchange:eduaircontrol.sensor}")
            String name) {
        return new TopicExchange(name + DLQ_SUFFIX, true, false);
    }

    /**
     * Cola de trabajo.
     *
     * <p>El exchange de letras muertas se deriva del nombre del <b>exchange</b>, no
     * del de la cola: si se derivara del nombre de la cola apuntaria a un exchange
     * inexistente y los mensajes fallidos se perderian en silencio.
     */
    @Bean
    public Queue installationProjectionQueue(
            @Value("${app.messaging.installations-queue:eduaircontrol.sensor.ms-environment-monitoring}")
            String name,
            TopicExchange sensorEventsExchange) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(sensorEventsExchange.getName() + DLQ_SUFFIX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    /** Cola de letras muertas: 7 dias de retencion para poder inspeccionarla. */
    @Bean
    public Queue installationProjectionDlq(
            @Value("${app.messaging.installations-queue:eduaircontrol.sensor.ms-environment-monitoring}")
            String name) {
        return QueueBuilder.durable(name + DLQ_SUFFIX)
                .ttl(604_800_000)
                .build();
    }

    @Bean
    public Binding installationProjectionBinding(
            Queue installationProjectionQueue,
            TopicExchange sensorEventsExchange,
            @Value("${app.messaging.installations-routing-key:sensor.installation.*}") String routingKey) {
        return BindingBuilder.bind(installationProjectionQueue)
                .to(sensorEventsExchange).with(routingKey);
    }

    @Bean
    public Binding installationProjectionDlqBinding(
            Queue installationProjectionDlq,
            TopicExchange sensorEventsDlxExchange) {
        return BindingBuilder.bind(installationProjectionDlq)
                .to(sensorEventsDlxExchange).with(DLQ_ROUTING_KEY);
    }

    /**
     * Factory propio, con 1 solo consumidor.
     *
     * <p>No se reutiliza el de mediciones a proposito: las instalaciones cambian por
     * horas o por dias, mientras que las mediciones llegan cada 30 s por nodo. Una
     * cola lenta de instalaciones no debe competir por los consumidores con el flujo
     * caliente.
     *
     * <p>ACK AUTO: el metodo retorna y confirma; si lanza, se hace nack. Con MANUAL
     * habria que confirmar a mano y basta olvidar un ack para perder instalaciones
     * sin que nada lo indique.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory installationProjectionContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${app.messaging.max-retries:4}") int maxRetries,
            @Value("${app.messaging.initial-retry-interval-ms:1000}") long initialIntervalMs) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(10);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(maxRetries)
                .backOffOptions(initialIntervalMs, 2.0, 10_000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}