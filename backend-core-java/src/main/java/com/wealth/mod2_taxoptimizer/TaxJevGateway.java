package com.wealth.mod2_taxoptimizer;

import com.wealth.ledger.jev.JevRequest;
import com.wealth.ledger.jev.JevResponse;

/**
 * Interfaz de gateway entre el Módulo 2 (Optimizador Fiscal) y OpenJEV System One.
 * <p>
 * Esta interfaz de un solo método permite:
 * <ol>
 *   <li>Desacoplar {@link TaxClassificationService} de la clase concreta
 *       {@link com.wealth.ledger.jev.OpenJevClient}, que es un {@code @Component} de Spring
 *       con dependencias de infraestructura (RestClient, {@code @Value}).</li>
 *   <li>Sustituirla fácilmente en tests unitarios con una implementación anónima o lambda
 *       <strong>sin necesidad de Mockito</strong> ni de Spring context.</li>
 * </ol>
 * <p>
 * <strong>Implementaciones:</strong>
 * <ul>
 *   <li>{@link OpenJevTaxGateway} — adaptador de producción que delega en
 *       {@link com.wealth.ledger.jev.OpenJevClient}.</li>
 *   <li>Implementaciones anónimas en tests — devuelven {@link JevResponse}s configuradas.</li>
 * </ul>
 */
@FunctionalInterface
public interface TaxJevGateway {

    /**
     * Envía una petición de clasificación a OpenJEV y devuelve la respuesta.
     *
     * @param request petición estructurada con estado, modelo y preguntas de clasificación.
     * @return respuesta de clasificación de OpenJEV; nunca {@code null} en producción
     *         (si OpenJEV falla, el adaptador lanza una excepción que
     *         {@link TaxClassificationService} captura y convierte en fallback conservador).
     * @throws com.wealth.ledger.jev.OpenJevClient.OpenJevException si OpenJEV no está disponible.
     */
    JevResponse classify(JevRequest request);
}
