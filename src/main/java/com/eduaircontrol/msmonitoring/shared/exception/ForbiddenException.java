package com.eduaircontrol.msmonitoring.shared.exception;

/**
 * El llamador no tiene permiso sobre el recurso que pide.
 *
 * <p>Es el caso de un token de dispositivo que intenta escribir en una instalacion
 * que no es suya: la instalacion existe y esta activa, pero pertenece a otro
 * sensor. Se responde 403 y no 404 para no revelar si existe.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
