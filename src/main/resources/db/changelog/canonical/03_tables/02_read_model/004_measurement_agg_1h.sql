-- Agregacion de mediciones a 1 hora (ADR-020).
--
-- Un bucket = ventana de 1 hora por (instalacion, variable). Construido a partir
-- de measurement_agg_5m (cascada). Usado para consultas historicas de hasta 90 dias.
CREATE TABLE environment_monitoring.measurement_agg_1h (
    sensor_installation_id UUID NOT NULL,
    variable_id            UUID NOT NULL,
    bucket_start           TIMESTAMPTZ NOT NULL,   -- truncado a 1 h
    avg_value              NUMERIC(12,4) NOT NULL,
    min_value              NUMERIC(12,4) NOT NULL,
    max_value              NUMERIC(12,4) NOT NULL,
    sample_count           INTEGER NOT NULL,
    CONSTRAINT pk_measurement_agg_1h PRIMARY KEY (sensor_installation_id, variable_id, bucket_start)
);

CREATE INDEX ix_measurement_agg_1h_bucket
    ON environment_monitoring.measurement_agg_1h (bucket_start);