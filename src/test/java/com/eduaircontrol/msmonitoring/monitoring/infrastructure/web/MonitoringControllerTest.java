package com.eduaircontrol.msmonitoring.monitoring.infrastructure.web;

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
        jdbc.update("""
                insert into environment_monitoring.installation_projection
                    (sensor_installation_id, educational_environment_id,
                     environment_type_id, sensor_id)
                values (?, ?, ?, ?)
                ON CONFLICT (sensor_installation_id) DO NOTHING
                """, INSTALLATION_ID, ENVIRONMENT_ID, ENVIRONMENT_TYPE_ID,
                UUID.randomUUID());
        jdbc.update("DELETE FROM environment_monitoring.environment_measurement");
        jdbc.update("DELETE FROM environment_monitoring.environment_alert");

        token = tokenFor(UUID.randomUUID(), "monitor@test.com");
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
     * declararse ADMIN. La via de headers queda excluida del endpoint de ingesta.
     */
    @Test
    void rejectsIngestFromForgedGatewayHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/measurements")
                        .header("X-User-Id", "esp32-test")
                        .header("X-User-Role", "ADMIN")
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

    private String ingestBody() {
        return "{\"sensorInstallationId\":\"" + INSTALLATION_ID + "\""
                + ",\"variableId\":\"" + TEMPERATURE + "\""
                + ",\"value\":21.5}";
    }
}
