package com.eduaircontrol.msmonitoring.shared.security;

import com.eduaircontrol.msmonitoring.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autenticacion del servicio: unicamente JWT RS256 validado contra el JWKS de
 * ms-security. Los headers internos del gateway (X-User-*) no se aceptan
 * como identidad (R-001): el gateway reenvia siempre el Authorization
 * resuelto (incluso en el flujo BFF por cookie), asi que las peticiones
 * legitimas llegan con token y quien alcanze el puerto del servicio sin el
 * no obtiene ninguna autoridad (SecurityConfig responde 401).
 *
 * <p>Ademas publica el tenant del token (institutionId/campusId) en
 * {@link TenantContext} para que los adaptadores de persistencia filtren por
 * institucion (ADR-016). El contexto se limpia al terminar la peticion.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            final String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith(BEARER)) {
                filterChain.doFilter(request, response);
                return;
            }
            String token = authHeader.substring(BEARER.length());
            try {
                JwtService.AuthenticatedUser user = jwtService.authenticate(token);
                // El token se guarda como credencial: UserIdentityAdapter lo relee
                // para resolver el userId desde los claims.
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                user.email() != null ? user.email() : String.valueOf(user.userId()),
                                token,
                                user.roles().stream()
                                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                        .toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
                TenantContext.set(user.institutionId(), user.campusId());
            } catch (Exception e) {
                log.debug("Token invalido: {}", e.getMessage());
                // Sin autenticacion: SecurityConfig responde 401.
            }

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
