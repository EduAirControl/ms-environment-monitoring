-- Agregacion de mediciones a 5 minutos (ADR-020).
--
-- Un bucket = ventana de 5 minutos por (instalacion, variable). Pre-calcula
-- avg/min/max/count para que las consultas historicas de hasta 7 dias lean miles
-- de filas en vez de millones. Poblado por el job de agregacion (idempotente).
CREATE TABLE environment_monitoring.measurement_agg_5m (
    sensor_installation_id UUID NOT NULL,
    variable_id            UUID NOT NULL,
    bucket_start           TIMESTAMPTZ NOT NULL,   -- truncado a 5 min
    avg_value              NUMERIC(12,4) NOT NULL,
    min_value              NUMERIC(12,4) NOT NULL,
    max_value              NUMERIC(12,4) NOT NULL,
    sample_count           INTEGER NOT NULL,
    CONSTRAINT pk_measurement_agg_5m PRIMARY KEY (sensor_installation_id, variable_id, bucket_start)
);

CREATE INDEX ix_measurement_agg_5m_bucket
    ON environment_monitoring.measurement_agg_5m (bucket_start);