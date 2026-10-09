package com.eduaircontrol.msmonitoring.monitoring.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Ultima medicion por (ambiente, variable) en una sola consulta.
 *
 * <p>El ambiente no vive en {@code environment_measurement}: se resuelve por la
 * proyeccion {@code installation_projection}. Por eso esta vista lo trae ya
 * enlazado, para no hacer una consulta por ambiente cuando el panel los necesita
 * todos a la vez.
 */
public interface EnvironmentCurrentProjection {

    UUID getEducationalEnvironmentId();

    UUID getVariableId();

    BigDecimal getMeasuredValue();

    Instant getMeasuredAt();
}
