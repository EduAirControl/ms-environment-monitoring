package com.eduaircontrol.msmonitoring.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduaircontrol.msmonitoring.PostgresTestBase;
import com.eduaircontrol.msmonitoring.monitoring.domain.port.in.MeasurementUseCase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Evaluacion de umbrales sobre la ingesta HTTP.
 *
 * <p>Antes la evaluacion vivia en un consumidor de eventos y las mediciones que
 * entraban por {@code POST /api/v1/measurements} se guardaban sin alertar nunca.
 * Aqui se comprueba que el registro de una medicion dispara la alerta.
 *
 * <p>Usa los umbrales sembrados de CLASSROOM: CO2 normal 0-800, warning 0-1000.
 */
class MeasurementAlertingServiceTest extends PostgresTestBase {

    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID ENVIRONMENT_TYPE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000081");
    private static final UUID INSTALLATION_ID =
            UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");
    private static final UUID CO2 =
            UUID.fromString("00000000-0000-4000-8000-000000000073");

    @Autowired
    private MeasurementAlertingService alertingService;

    @Autowired
    private MeasurementService measurementService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM environment_monitoring.environment_alert");
        jdbc.update("DELETE FROM environment_monitoring.installation_projection");
        jdbc.update("""
                insert into environment_monitoring.educational_environment
                    (educational_environment_id, code, name, environment_type_id, floor)
                values (?, 'ALERT-INGEST', 'Ambiente ingesta', ?, 1)
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

    private long alertCount() {
        Long n = jdbc.queryForObject(
                "select count(*) from environment_monitoring.environment_alert", Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void recordingAMeasurementOverTheThresholdRaisesAnAlert() {
        // CO2 en 1200 supera el warning (0-1000) -> CRITICAL.
        measurementService.record(new MeasurementUseCase.RecordCommand(
                INSTALLATION_ID, CO2, new BigDecimal("1200"), Instant.now()));

        assertThat(alertCount()).isEqualTo(1);
    }

    @Test
    void recordingAMeasurementInsideTheNormalRangeRaisesNothing() {
        // CO2 en 400 esta dentro del rango INFO (0-800).
        measurementService.record(new MeasurementUseCase.RecordCommand(
                INSTALLATION_ID, CO2, new BigDecimal("400"), Instant.now()));

        assertThat(alertCount()).isZero();
    }

    @Test
    void reEvaluatingTheSameMeasurementDoesNotDuplicateTheAlert() {
        UUID measurementId = measurementService.record(new MeasurementUseCase.RecordCommand(
                INSTALLATION_ID, CO2, new BigDecimal("1200"), Instant.now()));
        assertThat(alertCount()).isEqualTo(1);

        alertingService.evaluate(measurementId, INSTALLATION_ID, CO2,
                new BigDecimal("1200"), Instant.now());

        assertThat(alertCount()).isEqualTo(1);
    }

    @Test
    void withoutInstallationProjectionTheMeasurementIsKeptButNoAlertIsRaised() {
        jdbc.update("DELETE FROM environment_monitoring.installation_projection");

        UUID measurementId = measurementService.record(new MeasurementUseCase.RecordCommand(
                INSTALLATION_ID, CO2, new BigDecimal("1200"), Instant.now()));

        // La medicion se guarda igual: no poder atribuirla no debe tirar la ingesta.
        Long stored = jdbc.queryForObject(
                "select count(*) from environment_monitoring.environment_measurement"
                        + " where environment_measurement_id = ?",
                Long.class, measurementId);
        assertThat(stored).isEqualTo(1);
        assertThat(alertCount()).isZero();
    }
}
