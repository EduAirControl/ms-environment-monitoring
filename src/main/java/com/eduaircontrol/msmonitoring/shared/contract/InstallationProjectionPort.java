package com.eduaircontrol.msmonitoring.shared.contract;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Estado de una instalacion de sensor en la replica local
 * {@code environment_monitoring.installation_projection}.
 *
 * <p>Esta proyeccion es la unica fuente que este servicio tiene sobre si una
 * instalacion existe y sigue activa: no posee {@code sensor_installation}. Sin
 * consultarla, cualquier installationId que llegue por HTTP se aceptaria, y un
 * nodo que siga enviando tras ser desvinculado o purgado llenaria la base de
 * mediciones que los JOIN con la proyeccion nunca muestran.
 *
 * <p>La replica se alimenta de los eventos de ms-sensor-management
 * ({@code sensor.installation.*}), asi que puede estar atrasada por segundos; el
 * unico caso en que eso importa es el de un borrado recien publicado.
 *
 * <p>{@code sensorId} es el que permite comprobar que un token de dispositivo solo
 * escribe en su propia instalacion: sin el, cualquier installationId del cuerpo
 * se aceptaria.
 */
public interface InstallationProjectionPort {

    Optional<InstallationState> findState(UUID sensorInstallationId);

    record InstallationState(UUID sensorInstallationId, UUID sensorId, Instant removedAt) {

        /** Activas solo las que existen en la proyeccion y jamas se retiraron. */
        public boolean isActive() {
            return removedAt == null;
        }
    }
}
