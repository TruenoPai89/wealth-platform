package com.wealth.ledger.jev;

import java.util.List;
import java.util.Map;

/**
 * Contrato de petición enviado a OpenJEV System One (POST /v1/systemone).
 * <p>
 * OpenJEV actúa como motor de clasificación y evaluación de escenarios (System One AI).
 * <strong>JAMÁS realiza aritmética financiera</strong>; recibe contexto estructurado,
 * clasifica y devuelve decisiones cualitativas que el backend Java usa para gobernar
 * la lógica de negocio.
 * <p>
 * <strong>Campos:</strong>
 * <ul>
 *   <li>{@code state} — contexto serializado del escenario actual (p.ej. JSON resumido
 *       del lote fiscal, fechas, ISIN). OpenJEV lo usa para "entender" la situación.</li>
 *   <li>{@code model} — identificador del modelo o perfil de evaluación a usar
 *       (p.ej. {@code "tax-classifier-v1"}, {@code "two-month-risk-v1"}).</li>
 *   <li>{@code questions} — mapa de preguntas de clasificación, cada una con tipo,
 *       instrucciones y criterios de evaluación. OpenJEV responde a cada pregunta
 *       en {@link JevResponse#decisions()}.</li>
 * </ul>
 *
 * @param state     contexto del escenario en texto plano o JSON serializado como String.
 * @param model     identificador del modelo/perfil OpenJEV a utilizar.
 * @param questions mapa {@code nombrePregunta → Question} con las clasificaciones pedidas.
 *
 * @see JevResponse
 * @see Question
 */
public record JevRequest(
        String state,
        String model,
        Map<String, Question> questions) {

    /**
     * Una pregunta de clasificación individual enviada a OpenJEV.
     *
     * @param type         tipo de respuesta esperado: {@code "boolean"}, {@code "category"},
     *                     {@code "ranked_list"}, {@code "confidence_score"}.
     * @param instructions instrucción en lenguaje natural para OpenJEV sobre qué evaluar.
     * @param criteria     lista de criterios o valores posibles que OpenJEV debe considerar.
     */
    public record Question(
            String type,
            String instructions,
            List<String> criteria) {}
}
