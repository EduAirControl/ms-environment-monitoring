-- Proyeccion local de instalaciones (DEC-006 §4.3).
--
-- El servicio no posee sensor_installation, educational_environment ni
-- environment_type. Para evaluar umbrales necesita resolver
-- sensor_installation_id -> educational_environment_id -> environment_type_id
-- sin llamar a otros servicios en cada medicion.
--
-- Se alimenta por eventos (SensorInstalled/SensorRemoved, exchange
-- eduaircontrol.sensor) desde ms-sensor-management. Es un cache, no la fuente de
-- verdad: si falta una fila, la medicion se procesa sin evaluacion de umbral y se
-- registra el hueco.
--
-- CONSECUENCIA PRACTICA: sin fila aqui, GET /environments/{id}/current,
-- GET /environments/{id}/measurements y el dashboard devuelven vacio aunque las
-- mediciones esten guardadas. Los JOIN de lectura exigen esta tabla.
--
-- environment_type_id es NULLABLE a proposito (migracion 011). Ninguna consulta lo
-- lee: ThresholdAdapter y MeasurementEventListener resuelven el tipo contra
-- environment_monitoring.educational_environment, y el resto filtra solo por
-- educational_environment_id. El evento SensorInstalled no lo trae porque este
-- servicio no lo posee (DEC-007: valida el ambiente solo como UUID).
CREATE TABLE environment_monitoring.installation_projection (
    sensor_installation_id     UUID PRIMARY KEY,
    educational_environment_id UUID NOT NULL,
    environment_type_id        UUID,
    sensor_id                  UUID NOT NULL,
    removed_at                 TIMESTAMPTZ,
    synced_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
