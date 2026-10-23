package com.eduaircontrol.msmonitoring.infrastructure.messaging.classroom;

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
 * Topología de mensajería para eventos de aulas (classroom).
 *
 * <p>Exchange, colas y binding se declaran desde el consumidor y no desde el
 * productor: quien recibe el mensaje es quien sabe que lo necesita.
 */
@Configuration
public class ClassroomMessagingConfig {

    public static final String DLQ_SUFFIX = ".dlq";

    /** Routing key con la que los fallos llegan a la cola de letras muertas. */
    public static final String DLQ_ROUTING_KEY = "classroom.educational_environment.failed";

    @Bean
    public TopicExchange classroomEventsExchange(
            @Value("${app.messaging.classroom.exchange:eduaircontrol.classroom}") String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean
    public TopicExchange classroomEventsDlxExchange(
            @Value("${app.messaging.classroom.exchange:eduaircontrol.classroom}") String name) {
        return new TopicExchange(name + ".dlq", true, false);
    }

    /**
     * Cola de trabajo para eventos de aulas.
     */
    @Bean
    public Queue classroomEventsQueue(
            @Value("${app.messaging.classroom.queue:eduaircontrol.classroom.ms-environment-monitoring}") String name,
            TopicExchange classroomEventsExchange) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(classroomEventsExchange.getName() + ".dlq")
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    /** Cola de letras muertas: 7 días de retención para poder inspeccionarla. */
    @Bean
    public Queue classroomEventsDlq(
            @Value("${app.messaging.classroom.queue:eduaircontrol.classroom.ms-environment-monitoring}") String name) {
        return QueueBuilder.durable(name + DLQ_SUFFIX)
                .ttl(604_800_000) // 7 días
                .build();
    }

    @Bean
    public Binding classroomBinding(
            Queue classroomEventsQueue,
            TopicExchange classroomEventsExchange,
            @Value("${app.messaging.classroom.routing-key:classroom.educational_environment.*}") String routingKey) {
        return BindingBuilder.bind(classroomEventsQueue).to(classroomEventsExchange).with(routingKey);
    }

    @Bean
    public Binding classroomDlqBinding(
            Queue classroomEventsDlq,
            TopicExchange classroomEventsDlxExchange) {
        return BindingBuilder.bind(classroomEventsDlq)
                .to(classroomEventsDlxExchange).with(DLQ_ROUTING_KEY);
    }

    /**
     * Factory propia, con 1 solo consumidor.
     *
     * <p>No se reutiliza el de mediciones a propósito: las instalaciones cambian por
     * horas o por días, mientras que las mediciones llegan cada 30 s por nodo. Una
     * cola lenta de instalaciones no debe competir por los consumidores con el flujo
     * caliente.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory classroomListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${app.messaging.max-retries:4}") int maxRetries,
            @Value("${app.messaging.initial-retry-interval-ms:1000}") long initialIntervalMs) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(10);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(maxRetries)
                .backOffOptions(initialIntervalMs, 2.0, 10000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
