package com.eduaircontrol.msmonitoring.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autenticación del servicio.
 *
 * <p>Prioriza los headers internos que añade el api-gateway (ADR-006/017):
 * {@code X-User-Id} (UUID) y {@code X-User-Role}. Si no vienen (llamada directa),
 * cae al JWT HS256 interino. Así funciona tanto detrás del BFF como en acceso directo.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /**
     * Autoridad que marca una autenticacion proveniente de los headers internos
     * del gateway. No la otorga el token: la anade el filtro siempre que se use
     * la via de headers, asi que no se puede forjar desde el token ni desde los
     * propios headers.
     */
    public static final String HEADER_AUTHORITY = "ROLE_HEADER";

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. Token primero. Si hay Bearer se valida y NO se cae a los headers,
        // aunque el token sea invalido: de lo contrario cualquier peticion se
        // saltaria la validacion mandando unos headers inventados.
        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER)) {
            String token = authHeader.substring(BEARER.length());
            try {
                JwtService.AuthenticatedUser user = jwtService.authenticate(token);
                // El token se guarda como credencial: UserIdentityAdapter lo relee
                // para resolver el userId cuando no llega por el header del gateway.
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                user.email() != null ? user.email() : String.valueOf(user.userId()),
                                token,
                                user.roles().stream()
                                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                        .toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.debug("Token invalido: {}", e.getMessage());
                // Sin autenticacion: SecurityConfig responde 401.
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Sin Bearer: headers internos del gateway. Se marcan con ROLE_HEADER
        // para que los puntos sensibles puedan exigir un token real.
        String userId = request.getHeader("X-User-Id");
        String role = request.getHeader("X-User-Role");
        if (userId != null && !userId.isBlank()) {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority(HEADER_AUTHORITY));
            addAuthorities(authorities, request.getHeader("X-User-Roles"), role);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, authorities));
        }

        filterChain.doFilter(request, response);
    }

    /**
     * El gateway manda todos los roles en {@code X-User-Roles} (coma-separados) y deja el
     * primero en {@code X-User-Role} por compatibilidad. Con un usuario de varios roles el
     * header simple bastaba para perder SUPER_ADMIN al cruzar el gateway.
     */
    private static void addAuthorities(List<SimpleGrantedAuthority> authorities,
            String rolesHeader, String singleRole) {
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            for (String r : rolesHeader.split(",")) {
                if (!r.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + r.trim()));
                }
            }
            return;
        }
        if (singleRole != null && !singleRole.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + singleRole));
        }
    }
}

