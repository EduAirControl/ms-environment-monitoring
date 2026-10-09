package com.eduaircontrol.msmonitoring.infrastructure.messaging.classroom;

import com.eduaircontrol.msmonitoring.infrastructure.messaging.InboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor de eventos de ambiente educativo desde ms-classroom-management.
 *
 * <p>Mantiene la réplica local {@code environment_monitoring.educational_environment}
 * sincronizada con ms-classroom-management.
 *
 * <p>Un solo listener por cola y despacho por {@code eventType}: los tres eventos
 * comparten el binding {@code classroom.educational_environment.*}, así que tres
 * {@code @RabbitListener} sobre la misma cola se repartirían los mensajes al azar
 * (un "created" aterrizaría en el handler de "removed").
 *
 * <p>Se declara en la MISMA transacción que la marca en {@code inbox_message}: o
 * quedan ambos o ninguno, de modo que no puede quedar registrado un evento cuya
 * réplica no se escribió.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassroomEnvironmentListener {

    private final ClassroomEnvironmentDao classroomEnvironmentDao;
    private final InboxRepository inbox;
    private final JsonMapper jsonMapper;

    @RabbitListener(
            queues = "${app.messaging.classroom.queue:eduaircontrol.classroom.ms-environment-monitoring}",
            containerFactory = "classroomListenerContainerFactory",
            autoStartup = "${app.messaging.listeners.auto-startup:true}")
    @Transactional
    public void onClassroomEvent(Message message) {
        ClassroomEnvironmentEvent event = parse(message);
        UUID eventId = resolveEventId(message, event);

        if (inbox.claim(eventId, ClassroomEnvironmentEvent.CONSUMER, event.eventType()) == 0) {
            log.debug("[CLASSROOM] Evento {} ya procesado; se descarta la reentrega", eventId);
            return;
        }

        ClassroomEnvironmentEvent.Payload payload = event.payload();
        switch (event.eventType()) {
            case ClassroomEnvironmentEvent.REMOVED_TYPE -> {
                classroomEnvironmentDao.markRemoved(payload.environmentId());
                log.info("[CLASSROOM] Ambiente {} ({}) retirado de la replica",
                        payload.environmentId(), payload.code());
            }
            case ClassroomEnvironmentEvent.CREATED_TYPE, ClassroomEnvironmentEvent.UPDATED_TYPE -> {
                classroomEnvironmentDao.upsert(
                        payload.environmentId(),
                        payload.code(),
                        payload.name(),
                        payload.campusId(),
                        payload.environmentTypeId(),
                        payload.floor());
                log.debug("[CLASSROOM] Ambiente {} ({}) sincronizado",
                        payload.environmentId(), payload.code());
            }
            default -> throw new IllegalArgumentException(
                    "Tipo de evento de aula desconocido: " + event.eventType());
        }
    }

    private ClassroomEnvironmentEvent parse(Message message) {
        String body = message.getBody() == null ? null
                : new String(message.getBody(), StandardCharsets.UTF_8);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Evento de aula vacio");
        }
        ClassroomEnvironmentEvent event = jsonMapper.readValue(body, ClassroomEnvironmentEvent.class);
        if (event == null || event.payload() == null || event.payload().environmentId() == null) {
            throw new IllegalArgumentException("Evento sin environmentId: " + body);
        }
        return event;
    }

    /**
     * El {@code eventId} del cuerpo es el que vale, porque es el que el contrato
     * declara unico e idempotente. El metadato del broker queda de respaldo.
     */
    private UUID resolveEventId(Message message, ClassroomEnvironmentEvent event) {
        if (event.eventId() != null) {
            return event.eventId();
        }
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            try {
                return UUID.fromString(messageId);
            } catch (IllegalArgumentException e) {
                log.warn("[CLASSROOM] messageId no es un UUID ({}); no se puede deduplicar", messageId);
            }
        }
        throw new IllegalArgumentException(
                "Evento sin eventId ni messageId: no se puede deduplicar");
    }
}
