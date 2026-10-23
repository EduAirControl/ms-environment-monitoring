package com.eduaircontrol.msmonitoring.monitoring.infrastructure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eduaircontrol.msmonitoring.PostgresTestBase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifica los endpoints de mediciones y alertas con autenticacion real.
 */
class MonitoringControllerTest extends PostgresTestBase {

    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID ENVIRONMENT_TYPE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000081");
    private static final UUID INSTALLATION_ID =
            UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");
    private static final UUID TEMPERATURE =
            UUID.fromString("00000000-0000-4000-8000-000000000071");
    private static final UUID CO2 =
            UUID.fromString("00000000-0000-4000-8000-000000000073");
    private static final UUID VALID_FLAG =
            UUID.fromString("00000000-0000-4000-8000-000000000065");
    /** Sensor dueno de INSTALLATION_ID: es el sub del token de dispositivo. */
    private static final UUID SENSOR_ID =
            UUID.fromString("cccccccc-0000-4000-8000-000000000001");
    /** Instalacion de OTRO sensor, para comprobar que no se puede escribir en ella. */
    private static final UUID OTHER_INSTALLATION_ID =
            UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
    private static final UUID OTHER_SENSOR_ID =
            UUID.fromString("cccccccc-0000-4000-8000-000000000002");


    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();

        jdbc.update("""
                insert into environment_monitoring.educational_environment
                    (educational_environment_id, code, name, environment_type_id, floor)
                values (?, 'MON-TEST', 'Ambiente monitoreo', ?, 1)
                ON CONFLICT (educational_environment_id) DO NOTHING
                """, ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID);
        // Se limpia la proyeccion antes de sembrarla: ConsumerTestBase reutiliza el
        // mismo INSTALLATION_ID con otro sensor_id y, con ON CONFLICT DO NOTHING,
        // se quedaria su fila. Un solo borrado: si se repite entre los dos inserts,
        // el segundo borra lo que acaba de sembrar el primero.
        jdbc.update("DELETE FROM environment_monitoring.installation_projection");
        jdbc.update("""
                insert into environment_monitoring.installation_projection
                    (sensor_installation_id, educational_environment_id,
                     environment_type_id, sensor_id)
                values (?, ?, ?, ?)
                ON CONFLICT (sensor_installation_id) DO NOTHING
                """, INSTALLATION_ID, ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID, SENSOR_ID);
        jdbc.update("""
                insert into environment_monitoring.installation_projection
                    (sensor_installation_id, educational_environment_id,
                     environment_type_id, sensor_id)
                values (?, ?, ?, ?)
                ON CONFLICT (sensor_installation_id) DO NOTHING
                """, OTHER_INSTALLATION_ID, ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID,
                OTHER_SENSOR_ID);
        jdbc.update("DELETE FROM environment_monitoring.environment_measurement");
        jdbc.update("DELETE FROM environment_monitoring.environment_alert");

        // El sub del token es el sensorId: asi se puede exigir pertenencia.
        token = tokenFor(SENSOR_ID, "monitor@test.com");
    }

    /** Token de dispositivo: el unico que puede enviar mediciones. */
    private String tokenFor(UUID userId, String email) {
        return com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                userId, email, "DEVICE");
    }

    @Test
    void ingestsMeasurementViaHttp() throws Exception {
        String body = "{\"sensorInstallationId\":\"" + INSTALLATION_ID + "\""
                + ",\"variableId\":\"" + TEMPERATURE + "\""
                + ",\"value\":22.5}";

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void returnsCurrentValues() throws Exception {
        jdbc.update("""
                insert into environment_monitoring.environment_measurement
                    (environment_measurement_id, sensor_installation_id, variable_id,
                     measured_value, measured_at, quality_flag_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), INSTALLATION_ID, TEMPERATURE,
                new BigDecimal("23.0"), Timestamp.from(Instant.now()), VALID_FLAG);

        mockMvc.perform(get("/api/v1/environments/{id}/current", ENVIRONMENT_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].variableCode").value("temperature"));
    }

    @Test
    void listsAlertsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Siembra una alerta y devuelve su id. */
    private UUID seedAlert() {
        UUID alertId = UUID.randomUUID();
        jdbc.update("""
                insert into environment_monitoring.environment_alert
                    (environment_alert_id, educational_environment_id, variable_id,
                     variable_threshold_id, triggering_measurement_id, raised_at, severity_code)
                values (?, ?, ?, ?, ?, now(), 'WARNING')
                """, alertId, ENVIRONMENT_ID, TEMPERATURE, UUID.randomUUID(), UUID.randomUUID());
        return alertId;
    }

    /**
     * El panel web borra la notificacion con DELETE /api/v1/alerts/{id} (H-05).
     * Debe responder 204 y la alerta no puede reaparecer en el listado.
     */
    @Test
    void deletesAlertViaHttp() throws Exception {
        UUID alertId = seedAlert();

        mockMvc.perform(delete("/api/v1/alerts/{id}", alertId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Borrar dos veces no falla (idempotente): el segundo DELETE tambien es 204. */
    @Test
    void deletingAnAlertTwiceIsIdempotent() throws Exception {
        UUID alertId = seedAlert();

        mockMvc.perform(delete("/api/v1/alerts/{id}", alertId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/alerts/{id}", alertId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    /** Un id inexistente no es un no-op silencioso: responde 404. */
    @Test
    void deletingAnUnknownAlertReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/alerts/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /**
     * El panel web pinta el ranking de ambientes: sin este endpoint tendria que
     * hacer una peticion por ambiente. Aqui se comprueba que una sola llamada
     * trae la ultima medicion de cada (ambiente, variable).
     */
    @Test
    void returnsCurrentValuesForAllEnvironments() throws Exception {
        UUID otherEnv = UUID.randomUUID();
        UUID otherInstallation = UUID.randomUUID();
        jdbc.update("""
                insert into environment_monitoring.educational_environment
                    (educational_environment_id, code, name, environment_type_id, floor)
                values (?, 'MON-TEST-2', 'Ambiente dos', ?, 1)
                ON CONFLICT (educational_environment_id) DO NOTHING
                """, otherEnv, ENVIRONMENT_TYPE_ID);
        jdbc.update("""
                insert into environment_monitoring.installation_projection
                    (sensor_installation_id, educational_environment_id,
                     environment_type_id, sensor_id)
                values (?, ?, ?, ?)
                ON CONFLICT (sensor_installation_id) DO NOTHING
                """, otherInstallation, otherEnv, ENVIRONMENT_TYPE_ID, UUID.randomUUID());

        Instant older = Instant.now().minusSeconds(3600);
        jdbc.update("""
                insert into environment_monitoring.environment_measurement
                    (environment_measurement_id, sensor_installation_id, variable_id,
                     measured_value, measured_at, quality_flag_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), INSTALLATION_ID, TEMPERATURE,
                new BigDecimal("23.0"), Timestamp.from(older), VALID_FLAG);
        // La mas reciente del MISMO (ambiente, variable) debe ganar.
        jdbc.update("""
                insert into environment_monitoring.environment_measurement
                    (environment_measurement_id, sensor_installation_id, variable_id,
                     measured_value, measured_at, quality_flag_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), INSTALLATION_ID, TEMPERATURE,
                new BigDecimal("25.5"), Timestamp.from(Instant.now()), VALID_FLAG);
        jdbc.update("""
                insert into environment_monitoring.environment_measurement
                    (environment_measurement_id, sensor_installation_id, variable_id,
                     measured_value, measured_at, quality_flag_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), otherInstallation, CO2,
                new BigDecimal("600"), Timestamp.from(Instant.now()), VALID_FLAG);

        mockMvc.perform(get("/api/v1/environments/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.environmentId=='" + ENVIRONMENT_ID
                        + "' && @.variableCode=='temperature' && @.value==25.5)]").exists())
                .andExpect(jsonPath("$[?(@.environmentId=='" + otherEnv
                        + "' && @.variableCode=='co2')]").exists());
    }

    @Test
    void requiresAuthenticationForMeasurements() throws Exception {
        mockMvc.perform(get("/api/v1/environments/{id}/current", ENVIRONMENT_ID))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Un rol de usuario normal no basta para ingerir: solo DEVICE o ADMIN.
     */
    @Test
    void rejectsIngestFromAnOrdinaryUserToken() throws Exception {
        String userToken = com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                UUID.randomUUID(), "user@test.com", "USER");

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBody()))
                .andExpect(status().isForbidden());
    }

    /**
     * Este es el hueco que usaba el firmware: forjar el header del gateway y
     * declararse ADMIN. Con JWT-only los headers no autentican nada: sin token,
     * la peticion es 401.
     */
    @Test
    void rejectsIngestFromForgedGatewayHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/measurements")
                        .header("X-User-Id", "esp32-test")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBody()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Regresion de R-001: un token valido de usuario normal acompañado de headers
     * forjados de ADMIN sigue sin poder ingerir. Los headers se ignoran por
     * completo; el unico rol efectivo es el del token.
     */
    @Test
    void forgedAdminHeadersDoNotElevateAUserToken() throws Exception {
        String userToken = com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                UUID.randomUUID(), "user@test.com", "USER");

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + userToken)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "ADMIN")
                        .header("X-User-Roles", "ADMIN,SUPER_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsIngestFromAnAdminToken() throws Exception {
        String adminToken = com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                UUID.randomUUID(), "admin@test.com", "ADMIN");

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBody()))
                .andExpect(status().isCreated());
    }

    /**
     * SUPER_ADMIN cuenta como administrador, igual que en el resto de servicios.
     */
    @Test
    void acceptsIngestFromASuperAdminToken() throws Exception {
        String superAdminToken = com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                UUID.randomUUID(), "sa@test.com", "SUPER_ADMIN");

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBody()))
                .andExpect(status().isCreated());
    }

    /**
     * Un nodo que sigue enviando tras ser desvinculado no debe llenar la base de
     * mediciones que ninguna consulta devuelve: sin fila activa en la proyeccion no
     * hay ambiente al que atribuirlas.
     *
     * <p>Usa una instalacion propia, no la constante: el setUp usa ON CONFLICT DO
     * NOTHING y no limpiaria un removed_at que aqui se marcase, dejando el
     * INSTALLATION_ID cerrado para el resto de los tests de la clase.
     */
    @Test
    void rejectsIngestAgainstAClosedInstallation() throws Exception {
        UUID closedInstallation = UUID.randomUUID();
        jdbc.update("""
                insert into environment_monitoring.installation_projection
                    (sensor_installation_id, educational_environment_id,
                     environment_type_id, sensor_id, removed_at)
                values (?, ?, ?, ?, now())
                """, closedInstallation, ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID,
                // Es la instalacion del propio sensor, cerrada: lo que le pasa a un
                // ESP32 al que desvinculan. Si fuera de otro sensor daria 403.
                SENSOR_ID);

        String body = "{\"sensorInstallationId\":\"" + closedInstallation + "\""
                + ",\"variableId\":\"" + TEMPERATURE + "\""
                + ",\"value\":21.5}";

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isGone());
    }

    /** Mismo rechazo para una instalacion que nunca existio o fue purgada. */
    @Test
    void rejectsIngestAgainstAnUnknownInstallation() throws Exception {
        String body = "{\"sensorInstallationId\":\"" + UUID.randomUUID() + "\""
                + ",\"variableId\":\"" + TEMPERATURE + "\""
                + ",\"value\":21.5}";

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isGone());
    }

    private String ingestBody() {
        return ingestBodyFor(INSTALLATION_ID);
    }

    private String ingestBodyFor(java.util.UUID installationId) {
        return "{\"sensorInstallationId\":\"" + installationId + "\""
                + ",\"variableId\":\"" + TEMPERATURE + "\""
                + ",\"value\":21.5}";
    }

    /**
     * Un token de dispositivo solo puede escribir en SU instalacion. Sin esta
     * comprobacion bastaba saber un installationId (es publico en la respuesta de
     * alta) para envenenar la medicion de cualquier aula.
     */
    @Test
    void deviceTokenCannotWriteToAnotherSensorsInstallation() throws Exception {
        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBodyFor(OTHER_INSTALLATION_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deviceTokenCanWriteToItsOwnInstallation() throws Exception {
        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBody()))
                .andExpect(status().isCreated());
    }

    /** Un operador humano no esta ligado a ningun sensor: puede cargar en cualquiera. */
    @Test
    void adminTokenCanWriteToAnyInstallation() throws Exception {
        String adminToken = com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                UUID.randomUUID(), "admin@test.com", "ADMIN");

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestBodyFor(OTHER_INSTALLATION_ID)))
                .andExpect(status().isCreated());
    }

    /** La fecha de la medicion no es libre: sin acotarla se puede retroactivar. */
    @Test
    void rejectsMeasuredAtOutOfRange() throws Exception {
        String tooOld = "{\"sensorInstallationId\":\"" + INSTALLATION_ID + "\"" 
                + ",\"variableId\":\"" + TEMPERATURE + "\"" 
                + ",\"value\":21.0,\"measuredAt\":\"2020-01-01T00:00:00Z\"}";
        String tooFar = "{\"sensorInstallationId\":\"" + INSTALLATION_ID + "\"" 
                + ",\"variableId\":\"" + TEMPERATURE + "\"" 
                + ",\"value\":21.0,\"measuredAt\":\"2099-01-01T00:00:00Z\"}";

        for (String body : new String[] {tooOld, tooFar}) {
            mockMvc.perform(post("/api/v1/measurements")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * Este era el hueco abierto: los headers del gateway se aceptaban sin mas y
     * cualquiera en la red podia leerlo todo declarandose ADMIN. Con JWT-only
     * ningun endpoint acepta headers como identidad: sin token, 401.
     */
    @Test
    void rejectsForgedGatewayHeadersOnEveryEndpoint() throws Exception {
        String[] paths = {
                "/api/v1/environments/current",
                "/api/v1/environments/" + ENVIRONMENT_ID + "/current",
                "/api/v1/environments/" + ENVIRONMENT_ID + "/measurements",
                "/api/v1/alerts",
                "/api/v1/analyses",
                "/api/v1/analyses/latest?environmentId=" + ENVIRONMENT_ID,
                "/api/v1/dashboard/summary",
                "/api/v1/dashboard/series",
        };
        for (String path : paths) {
            mockMvc.perform(get(path)
                            .header("X-User-Id", "00000000-0000-4000-8000-000000000001")
                            .header("X-User-Role", "ADMIN"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/alerts/" + UUID.randomUUID() + "/acknowledge")
                        .header("X-User-Id", "00000000-0000-4000-8000-000000000001")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    /** Con un token real (aunque sea de usuario normal) las lecturas siguen abiertas. */
    @Test
    void allowsReadsWithARealToken() throws Exception {
        String userToken = com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                UUID.randomUUID(), "reader@test.com", "USER");

        mockMvc.perform(get("/api/v1/environments/current")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }
}
