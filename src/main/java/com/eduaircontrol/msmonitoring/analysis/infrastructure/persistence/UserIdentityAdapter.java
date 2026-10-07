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
 * Identidad del solicitante leida del token.
 *
 * <p>El servicio no tiene directorio de usuarios, y no deberia: el ID viaja en el
 * JWT desde que el emisor lo incluye. Asi el unico enlace con el dominio identity
 * es el token, no una consulta remota ni una tabla replicada.
 */
@Component
@RequiredArgsConstructor
public class UserIdentityAdapter implements UserIdentityPort {

    private final JwtService jwtService;

    @Override
    public Optional<UUID> currentUserId() {
        return currentClaims().map(jwtService::optionalUserId).orElseGet(Optional::empty);
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