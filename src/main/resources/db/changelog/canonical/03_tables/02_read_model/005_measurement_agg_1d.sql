-- Agregacion de mediciones a 1 dia (ADR-020).
--
-- Un bucket = ventana de 1 dia por (instalacion, variable). Construido a partir
-- de measurement_agg_1h (cascada). Usado para consultas historicas de mas de 90 dias.
CREATE TABLE environment_monitoring.measurement_agg_1d (
    sensor_installation_id UUID NOT NULL,
    variable_id            UUID NOT NULL,
    bucket_start           TIMESTAMPTZ NOT NULL,   -- truncado a 1 dia
    avg_value              NUMERIC(12,4) NOT NULL,
    min_value              NUMERIC(12,4) NOT NULL,
    max_value              NUMERIC(12,4) NOT NULL,
    sample_count           INTEGER NOT NULL,
    CONSTRAINT pk_measurement_agg_1d PRIMARY KEY (sensor_installation_id, variable_id, bucket_start)
);

CREATE INDEX ix_measurement_agg_1d_bucket
    ON environment_monitoring.measurement_agg_1d (bucket_start);