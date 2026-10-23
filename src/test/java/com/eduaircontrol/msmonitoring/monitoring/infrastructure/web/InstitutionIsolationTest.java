package com.eduaircontrol.msmonitoring.monitoring.infrastructure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eduaircontrol.msmonitoring.PostgresTestBase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Aislamiento por institucion (ADR-016): un token de la institucion A no ve
 * ambientes, mediciones, alertas ni el dashboard de la institucion B.
 *
 * <p>Los tokens llevan el claim {@code institutionId}; los de dispositivo o los de
 * sistema (sin claim) no se filtran, que es el comportamiento previo.
 */
class InstitutionIsolationTest extends PostgresTestBase {

    private static final UUID INSTITUTION_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INSTITUTION_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ENV_A = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000a1");
    private static final UUID ENV_B = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000b1");
    private static final UUID INSTALL_A = UUID.fromString("cccccccc-0000-4000-8000-0000000000a1");
    private static final UUID INSTALL_B = UUID.fromString("dddddddd-0000-4000-8000-0000000000b1");
    private static final UUID ALERT_A = UUID.fromString("eeeeeeee-0000-4000-8000-0000000000a1");
    private static final UUID ALERT_B = UUID.fromString("ffffffff-0000-4000-8000-0000000000b1");
    private static final UUID ENVIRONMENT_TYPE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000081");
    private static final UUID TEMPERATURE =
            UUID.fromString("00000000-0000-4000-8000-000000000071");
    private static final UUID VALID_FLAG =
            UUID.fromString("00000000-0000-4000-8000-000000000065");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        jdbc.update("DELETE FROM environment_monitoring.environment_measurement");
        jdbc.update("DELETE FROM environment_monitoring.environment_alert");
        jdbc.update("DELETE FROM environment_monitoring.installation_projection");
        jdbc.update("DELETE FROM environment_monitoring.educational_environment "
                + "WHERE educational_environment_id IN (?, ?)", ENV_A, ENV_B);

        seedEnvironment(ENV_A, "ISO-A", "Ambiente A", INSTITUTION_A);
        seedEnvironment(ENV_B, "ISO-B", "Ambiente B", INSTITUTION_B);

        seedInstallation(INSTALL_A, ENV_A);
        seedInstallation(INSTALL_B, ENV_B);

        seedMeasurement(INSTALL_A);
        seedMeasurement(INSTALL_B);

        seedAlert(ALERT_A, ENV_A);
        seedAlert(ALERT_B, ENV_B);
    }

    private void seedEnvironment(UUID id, String code, String name, UUID institutionId) {
        jdbc.update("""
                insert into environment_monitoring.educational_environment
                    (educational_environment_id, code, name, campus_id, institution_id,
                     environment_type_id, floor)
                values (?, ?, ?, ?, ?, ?, 1)
                """, id, code, name, UUID.randomUUID(), institutionId, ENVIRONMENT_TYPE_ID);
    }

    private void seedInstallation(UUID installationId, UUID environmentId) {
        jdbc.update("""
                insert into environment_monitoring.installation_projection
                    (sensor_installation_id, educational_environment_id,
                     environment_type_id, sensor_id)
                values (?, ?, ?, ?)
                """, installationId, environmentId, ENVIRONMENT_TYPE_ID, UUID.randomUUID());
    }

    private void seedMeasurement(UUID installationId) {
        jdbc.update("""
                insert into environment_monitoring.environment_measurement
                    (environment_measurement_id, sensor_installation_id, variable_id,
                     measured_value, measured_at, quality_flag_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), installationId, TEMPERATURE,
                new BigDecimal("23.0"), Timestamp.from(Instant.now()), VALID_FLAG);
    }

    private void seedAlert(UUID alertId, UUID environmentId) {
        jdbc.update("""
                insert into environment_monitoring.environment_alert
                    (environment_alert_id, educational_environment_id, variable_id,
                     variable_threshold_id, triggering_measurement_id, raised_at, severity_code)
                values (?, ?, ?, ?, ?, now(), 'WARNING')
                """, alertId, environmentId, TEMPERATURE, UUID.randomUUID(), UUID.randomUUID());
    }

    private String tokenFor(UUID institutionId) {
        return com.eduaircontrol.msmonitoring.shared.security.TestTokenMint.mint(
                UUID.randomUUID(), "user@test.com", List.of("USER"), institutionId, null);
    }

    @Test
    void dashboardSummaryOnlyCountsOwnInstitution() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + tokenFor(INSTITUTION_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.environments").value(1))
                .andExpect(jsonPath("$.counts.measurements").value(1));
    }

    @Test
    void alertsAreScopedToInstitution() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokenFor(INSTITUTION_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].environmentId").value(ENV_A.toString()));

        mockMvc.perform(get("/api/v1/alerts")
                        .header("Authorization", "Bearer " + tokenFor(INSTITUTION_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].environmentId").value(ENV_B.toString()));
    }

    @Test
    void currentValuesOnlyOwnInstitution() throws Exception {
        mockMvc.perform(get("/api/v1/environments/current")
                        .header("Authorization", "Bearer " + tokenFor(INSTITUTION_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].environmentId").value(ENV_A.toString()));
    }
}
