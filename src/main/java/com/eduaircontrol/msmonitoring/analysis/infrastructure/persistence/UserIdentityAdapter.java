package com.eduaircontrol.msmonitoring.analysis.infrastructure.persistence;

import com.eduaircontrol.msmonitoring.shared.contract.UserIdentityPort;
import com.eduaircontrol.msmonitoring.shared.security.JwtService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Identidad del solicitante.
 *
 * <p>Detrás del gateway (BFF) el id llega en el header {@code X-User-Id} y se publica
 * como nombre del {@link Authentication}. En acceso directo el id viaja dentro del JWT.
 */
@Component
@RequiredArgsConstructor
public class UserIdentityAdapter implements UserIdentityPort {

    private final JwtService jwtService;

    @Override
    public Optional<UUID> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        // 1. Header del gateway: el nombre del Authentication es el userId (UUID).
        try {
            return Optional.of(UUID.fromString(authentication.getName()));
        } catch (IllegalArgumentException ignored) {
            // no es un UUID → probar con el token
        }
        // 2. Fallback: token en credentials.
        return currentClaims().flatMap(jwtService::optionalUserId);
    }

    @Override
    public Optional<String> currentEmail() {
        return currentClaims()
                .map(claims -> claims.getSubject())
                .filter(email -> email != null && !email.isBlank());
    }

    /**
     * El token no se guarda como nombre de usuario: ahi solo queda el correo, que es
     * lo que Spring espera. Se vuelve a leer del encabezado cuando hace falta el id.
     */
    private Optional<io.jsonwebtoken.Claims> currentClaims() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getCredentials() instanceof String token)) {
            return Optional.empty();
        }
        try {
            return Optional.of(jwtService.parse(token));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
