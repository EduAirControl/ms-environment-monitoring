package com.eduaircontrol.msmonitoring.monitoring.application;

import com.eduaircontrol.msmonitoring.monitoring.domain.model.EnvironmentAlert;
import com.eduaircontrol.msmonitoring.monitoring.infrastructure.persistence.PushTokenDao;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envio de notificaciones push (Expo) cuando se genera una alerta.
 *
 * <p>Se envia a los dispositivos registrados de la institucion del ambiente. Es
 * <b>best-effort</b> y corre tras el commit y fuera del hilo de la peticion: un
 * fallo de push nunca debe tumbar la ingesta ni la transaccion de la alerta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpoPushService {

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";

    private final PushTokenDao pushTokenDao;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    @Value("${app.push.enabled:true}")
    private boolean enabled;

    @Value("${app.push.timeout-seconds:5}")
    private long timeoutSeconds;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "expo-push");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Programa el push de una alerta recien creada. */
    public void alertRaised(EnvironmentAlert alert) {
        if (!enabled || alert == null) {
            return;
        }
        Runnable send = () -> send(alert);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.submit(send);
                }
            });
        } else {
            executor.submit(send);
        }
    }

    private void send(EnvironmentAlert alert) {
        try {
            UUID institutionId = institutionOf(alert.getEducationalEnvironmentId());
            List<String> tokens = pushTokenDao.tokensByInstitution(institutionId);
            if (tokens.isEmpty()) {
                return;
            }
            String environmentName = environmentName(alert.getEducationalEnvironmentId());
            String variableCode = variableCode(alert.getVariableId());
            String title = "Alerta ambiental";
            String body = String.format("%s fuera de rango en %s (%s)",
                    variableCode, environmentName, alert.getSeverityCode());

            List<Map<String, Object>> messages = tokens.stream()
                    .map(token -> Map.<String, Object>of(
                            "to", token,
                            "title", title,
                            "body", body,
                            "sound", "default",
                            "priority", "high",
                            "data", Map.of(
                                    "type", "alert",
                                    "alertId", alert.getId().toString(),
                                    "environmentId",
                                    alert.getEducationalEnvironmentId().toString())))
                    .toList();

            String payload = jsonMapper.writeValueAsString(messages);
            HttpRequest request = HttpRequest.newBuilder(URI.create(EXPO_PUSH_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Expo push respondio {}: {}", response.statusCode(), response.body());
            } else {
                log.debug("Push de alerta {} enviado a {} dispositivo(s)",
                        alert.getId(), tokens.size());
            }
        } catch (Exception e) {
            log.warn("No se pudo enviar el push de la alerta {}: {}", alert.getId(), e.getMessage());
        }
    }

    private UUID institutionOf(UUID environmentId) {
        return jdbc.query("""
                        select institution_id from environment_monitoring.educational_environment
                        where educational_environment_id = ?
                        """,
                        (rs, rowNum) -> rs.getObject("institution_id", UUID.class), environmentId)
                .stream().findFirst().orElse(null);
    }

    private String environmentName(UUID environmentId) {
        return jdbc.query("""
                        select name from environment_monitoring.educational_environment
                        where educational_environment_id = ?
                        """,
                        (rs, rowNum) -> rs.getString("name"), environmentId)
                .stream().findFirst().orElse("ambiente");
    }

    private String variableCode(UUID variableId) {
        return jdbc.query("""
                        select code from environment_monitoring.variable where variable_id = ?
                        """,
                        (rs, rowNum) -> rs.getString("code"), variableId)
                .stream().findFirst().orElse("variable");
    }
}
