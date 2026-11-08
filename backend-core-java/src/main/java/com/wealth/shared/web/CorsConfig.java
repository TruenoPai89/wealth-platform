package com.wealth.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS global para la API REST (/api/**), de modo que el navegador deje al frontend de desarrollo
 * (Next.js en http://localhost:3000) llamar al backend. Un unico sitio para todos los controllers:
 * los modulos 2-4 no necesitan anotar nada. Origenes configurables en application.yml
 * (wealth.cors.allowed-origins, separados por comas). Sin credenciales: la API aun no tiene auth.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${wealth.cors.allowed-origins:http://localhost:3000}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
