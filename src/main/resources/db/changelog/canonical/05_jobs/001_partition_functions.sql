-- Funciones auxiliares para gestion de particiones (ADR-020).
-- Encapsulan el SQL dinamico dentro de PL/pgSQL para que el codigo Java
-- no construya SQL dinamico (SonarCloud S2077).

-- Crea la particion mensual si no existe.
CREATE OR REPLACE FUNCTION environment_monitoring.ensure_partition(
    partition_name TEXT,
    from_ts TEXT,
    to_ts TEXT
) RETURNS VOID AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'environment_monitoring'
          AND c.relname = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE environment_monitoring.%I PARTITION OF environment_monitoring.environment_measurement FOR VALUES FROM (%L) TO (%L)',
            partition_name, from_ts, to_ts);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Elimina la particion si su fecha de fin es anterior al umbral de retencion.
CREATE OR REPLACE FUNCTION environment_monitoring.drop_partition_if_expired(
    partition_name TEXT,
    retention_days INTEGER
) RETURNS BOOLEAN AS $$
DECLARE
    partition_end TIMESTAMPTZ;
BEGIN
    -- Validar nombre de particion (whitelist)
    IF partition_name !~ '^environment_measurement_[0-9]{4}_[0-9]{2}$' THEN
        RETURN FALSE;
    END IF;

    -- Calcular el fin de la particion a partir del nombre
    partition_end := to_timestamp(
        substring(partition_name from 28 for 4) || '-' ||
        substring(partition_name from 33 for 2) || '-01',
        'YYYY-MM-DD') + interval '1 month';

    IF partition_end < now() - (retention_days || ' days')::interval THEN
        EXECUTE format('DROP TABLE IF EXISTS environment_monitoring.%I', partition_name);
        RETURN TRUE;
    END IF;
    RETURN FALSE;
END;
$$ LANGUAGE plpgsql;
