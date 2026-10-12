package com.eduaircontrol.msmonitoring.shared.exception;

/**
 * La instalacion referenciada por una medicion ya no admite datos.
 *
 * <p>Se responde 410 y no 404: la instalacion existio, pero o se cerro
 * ({@code removed_at}) o se borro fisicamente, y en ninguno de los dos casos
 * volvera a aceptar lecturas. Es el caso del ESP32 que sigue enviando con un
 * installationId que alguien desvinculo o purgo desde la app o la web.
 */
public class GoneException extends RuntimeException {

    public GoneException(String message) {
        super(message);
    }
}
