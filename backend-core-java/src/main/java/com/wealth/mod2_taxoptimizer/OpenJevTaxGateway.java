package com.wealth.mod2_taxoptimizer;

import com.wealth.ledger.jev.JevRequest;
import com.wealth.ledger.jev.JevResponse;
import com.wealth.ledger.jev.OpenJevClient;
import org.springframework.stereotype.Component;

/**
 * Adaptador de producción de {@link TaxJevGateway}.
 * <p>
 * Delega en el {@link OpenJevClient} genérico de Spring para enviar la petición de
 * clasificación fiscal a OpenJEV System One y deserializar la respuesta como
 * {@link JevResponse}.
 * <p>
 * Este {@code @Component} es el único lugar donde el Módulo 2 toca directamente
 * la infraestructura HTTP de OpenJEV. El resto del módulo ({@link TaxClassificationService},
 * {@link TaxEvaluatorAdapter}) trabaja contra la interfaz {@link TaxJevGateway},
 * lo que facilita los tests unitarios sin Spring ni llamadas HTTP.
 */
@Component
public class OpenJevTaxGateway implements TaxJevGateway {

    private final OpenJevClient openJevClient;

    public OpenJevTaxGateway(OpenJevClient openJevClient) {
        this.openJevClient = openJevClient;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Llama a {@link OpenJevClient#call(Object, Class)} pasando el {@link JevRequest}
     * y esperando un {@link JevResponse}. Si OpenJEV falla, la excepción se propaga
     * al llamador ({@link TaxClassificationService#classify}) que la captura y aplica
     * el fallback conservador.
     */
    @Override
    public JevResponse classify(JevRequest request) {
        return openJevClient.call(request, JevResponse.class);
    }
}
