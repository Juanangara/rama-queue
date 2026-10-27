package io.ramaqueue.mock.chaos;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/** Aplica latencia, límite por minuto y bloqueos a las rutas de la API (no a /__admin ni a actuator). */
@Component
public class ChaosFilter extends OncePerRequestFilter {

    private final ChaosState caos;

    public ChaosFilter(ChaosState caos) {
        this.caos = caos;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v2/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        caos.entrar();
        try {
            if (caos.admitir(System.currentTimeMillis()) == ChaosState.Decision.DENEGAR) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("text/plain;charset=UTF-8");
                response.getOutputStream().write("Forbidden".getBytes(StandardCharsets.UTF_8));
                caos.registrarEstado(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            dormir();
            chain.doFilter(request, response);
            caos.registrarEstado(response.getStatus());
        } finally {
            caos.salir();
        }
    }

    private void dormir() {
        int jitter = caos.jitterMs();
        long espera = caos.latenciaMs() + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0);
        if (espera <= 0) {
            return;
        }
        try {
            Thread.sleep(espera);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
