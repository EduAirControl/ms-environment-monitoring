package com.eduaircontrol.msmonitoring.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduaircontrol.msmonitoring.PostgresTestBase;
import com.eduaircontrol.msmonitoring.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Override de umbrales por ambiente (enmienda a ADR-014).
 *
 * <p>El PUT crea un override WARNING por ambiente que tiene prioridad sobre el
 * umbral del tipo (sembrado: CLASSROOM TEMP warning 18-28).
 */
class ThresholdConfigServiceTest extends PostgresTestBase {

    private static final UUID CLASSROOM =
            UUID.fromString("00000000-0000-4000-8000-000000000081");
    private static final UUID TEMPERATURE =
            UUID.fromString("00000000-0000-4000-8000-000000000071");

    @Autowired
    private ThresholdConfigService configService;

    @Autowired
    private ThresholdService thresholdService;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void seedEnvironment() {
        jdbc.update("""
                insert into environment_monitoring.educational_environment
                    (educational_environment_id, code, name, environment_type_id, floor)
                values (?, ?, 'Ambiente umbrales', ?, 1)
                ON CONFLICT (educational_environment_id) DO NOTHING
                """, environmentId, "THRESH-" + environmentId.toString().substring(0, 8),
                CLASSROOM);
        jdbc.update("""
                delete from environment_monitoring.variable_threshold
                where educational_environment_id = ?
                """, environmentId);
    }

    @Test
    void withoutOverrideTheTypeThresholdApplies() {
        var thresholds = configService.effective(environmentId);

        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && "TYPE".equals(t.source())
                        && t.minValue().compareTo(new BigDecimal("18")) == 0
                        && t.maxValue().compareTo(new BigDecimal("28")) == 0);
    }

    @Test
    void effectiveIncludesInfoAndWarningRanges() {
        var thresholds = configService.effective(environmentId);

        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && "INFO".equals(t.severityCode())
                        && t.minValue().compareTo(new BigDecimal("20")) == 0
                        && t.maxValue().compareTo(new BigDecimal("26")) == 0);
        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && "WARNING".equals(t.severityCode())
                        && t.minValue().compareTo(new BigDecimal("18")) == 0
                        && t.maxValue().compareTo(new BigDecimal("28")) == 0);
    }

    @Test
    void updateAllSavesInfoAndWarningTogether() {
        configService.updateAll(environmentId, java.util.List.of(
                new ThresholdConfigService.ThresholdUpdate(
                        "temperature", "INFO", new BigDecimal("22"), new BigDecimal("24")),
                new ThresholdConfigService.ThresholdUpdate(
                        "temperature", "WARNING", new BigDecimal("20"), new BigDecimal("26"))));

        var thresholds = configService.effective(environmentId);
        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && "INFO".equals(t.severityCode())
                        && t.minValue().compareTo(new BigDecimal("22")) == 0
                        && t.maxValue().compareTo(new BigDecimal("24")) == 0);
        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && "WARNING".equals(t.severityCode())
                        && t.minValue().compareTo(new BigDecimal("20")) == 0
                        && t.maxValue().compareTo(new BigDecimal("26")) == 0);

        // 21 sale del rango normal nuevo (22-24) pero sigue dentro del warning.
        var warning = thresholdService.evaluate(
                environmentId, CLASSROOM, TEMPERATURE, new BigDecimal("21"), LocalDate.now());
        assertThat(warning).isPresent();
        assertThat(warning.get().severityCode()).isEqualTo("WARNING");

        var critical = thresholdService.evaluate(
                environmentId, CLASSROOM, TEMPERATURE, new BigDecimal("27"), LocalDate.now());
        assertThat(critical).isPresent();
        assertThat(critical.get().severityCode()).isEqualTo("CRITICAL");
    }

    @Test
    void normalRangeMustBeContainedInWarningRange() {
        assertThatThrownBy(() -> configService.update(
                environmentId, "temperature", "INFO",
                new BigDecimal("10"), new BigDecimal("26")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateCreatesOverrideThatBeatsTheTypeThreshold() {
        configService.updateWarning(environmentId, "temperature",
                new BigDecimal("19"), new BigDecimal("27"));

        var thresholds = configService.effective(environmentId);
        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && "ENVIRONMENT".equals(t.source())
                        && t.minValue().compareTo(new BigDecimal("19")) == 0
                        && t.maxValue().compareTo(new BigDecimal("27")) == 0);

        // La evaluacion de alertas usa el override: 28 ya es CRITICAL
        // (fuera del WARNING 19-27 del ambiente), no WARNING del tipo.
        var evaluation = thresholdService.evaluate(
                environmentId, CLASSROOM, TEMPERATURE, new BigDecimal("28"), LocalDate.now());
        assertThat(evaluation).isPresent();
        assertThat(evaluation.get().severityCode()).isEqualTo("CRITICAL");
    }

    @Test
    void updateClosesThePreviousOverride() {
        configService.updateWarning(environmentId, "temperature",
                new BigDecimal("19"), new BigDecimal("27"));
        configService.updateWarning(environmentId, "temperature",
                new BigDecimal("19.5"), new BigDecimal("26.5"));

        var open = jdbc.queryForObject("""
                select count(*) from environment_monitoring.variable_threshold
                where educational_environment_id = ? and valid_to is null
                """, Long.class, environmentId);
        assertThat(open).isEqualTo(1);

        var thresholds = configService.effective(environmentId);
        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && t.minValue().compareTo(new BigDecimal("19.5")) == 0
                        && t.maxValue().compareTo(new BigDecimal("26.5")) == 0);
    }

    @Test
    void minGreaterThanMaxIsRejected() {
        assertThatThrownBy(() -> configService.updateWarning(
                environmentId, "temperature", new BigDecimal("30"), new BigDecimal("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownVariableIsRejected() {
        assertThatThrownBy(() -> configService.updateWarning(
                environmentId, "not-a-variable", BigDecimal.ONE, BigDecimal.TEN))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unknownEnvironmentIsRejected() {
        assertThatThrownBy(() -> configService.effective(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Regresion: el override de OTRO ambiente del mismo tipo no debe aplicarse a
     * este. La fila del override lleva environment_type_id ademas del ambiente, y
     * la consulta lo trataba como umbral del tipo, con lo que un override se
     * filtraba a todos los ambientes de ese tipo.
     */
    @Test
    void overrideOfAnotherEnvironmentOfTheSameTypeDoesNotLeak() {
        UUID other = UUID.randomUUID();
        jdbc.update("""
                insert into environment_monitoring.educational_environment
                    (educational_environment_id, code, name, environment_type_id, floor)
                values (?, ?, 'Otro ambiente', ?, 1)
                ON CONFLICT (educational_environment_id) DO NOTHING
                """, other, "OTHER-" + other.toString().substring(0, 8), CLASSROOM);
        configService.updateWarning(other, "temperature",
                new BigDecimal("19"), new BigDecimal("27"));

        // Este ambiente no tiene override: debe seguir viendo el umbral del TIPO.
        var thresholds = configService.effective(environmentId);
        assertThat(thresholds).anyMatch(t ->
                "temperature".equals(t.variableCode())
                        && "TYPE".equals(t.source())
                        && t.minValue().compareTo(new BigDecimal("18")) == 0
                        && t.maxValue().compareTo(new BigDecimal("28")) == 0);
    }
}
