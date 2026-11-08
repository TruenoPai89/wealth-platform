package com.wealth.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS global para la API REST (/api/**), de modo que el navegador deje al frontend de desarrollo llamar al backend.
 * Un unico sitio para todos los controllers: los modulos 2-4 no necesitan anotar nada.
 * <p>
 * Los origenes permitidos son PATRONES (wealth.cors.allowed-origin-patterns en application.yml, separados por comas).
 * Por defecto: localhost y 127.0.0.1 en cualquier puerto. Asi sigue funcionando si se abre el frontend por
 * http://127.0.0.1:3000 o si Next arranca en otro puerto porque el 3000 estaba ocupado. Para otros hosts (IP de
 * red, dominio) hay que anadirlos explicitamente. Sin credenciales: la API aun no tiene auth.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    public CorsConfig(@Value("${wealth.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
                      String[] allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
