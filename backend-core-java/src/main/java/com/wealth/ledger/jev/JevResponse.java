package com.wealth.ledger.jev;

import java.util.Map;
import java.util.Optional;

/**
 * Contrato de respuesta de OpenJEV System One (POST /v1/systemone).
 * <p>
 * OpenJEV devuelve un mapa de {@link #decisions()} donde cada clave corresponde a una
 * pregunta enviada en {@link JevRequest#questions()} y el valor es la clasificación
 * resultante (un {@code String}, {@code Boolean}, {@code Double} u otro tipo primitivo
 * deserializado por Jackson desde el JSON de respuesta).
 * <p>
 * <strong>Regla crítica de uso:</strong> los valores de {@code decisions} son
 * <em>clasificaciones cualitativas</em>, no cantidades monetarias. Toda la aritmética
 * financiera (plusvalías, impuestos, tramos IRPF) se ejecuta en el backend Java
 * con {@code BigDecimal}, usando estas clasificaciones únicamente como gobernadores
 * de lógica de negocio (ej. "¿aplicar optimización por tax-loss harvesting?",
 * "¿hay riesgo de la regla de los 2 meses?").
 *
 * @param decisions mapa de clasificaciones; las claves coinciden con las de
 *                  {@link JevRequest#questions()}. Nunca {@code null}, puede estar vacío
 *                  si OpenJEV no procesó ninguna pregunta.
 * @param status    estado del procesamiento: {@code "ok"}, {@code "partial"} o {@code "error"}.
 *                  Un status distinto de {@code "ok"} indica que alguna clasificación
 *                  puede ser unreliable y el backend debe aplicar su fallback conservador.
 *
 * @see JevRequest
 */
public record JevResponse(
        Map<String, Object> decisions,
        String status) {

    /** Status que indica que OpenJEV procesó todas las preguntas correctamente. */
    public static final String STATUS_OK = "ok";

    /** Status que indica procesamiento parcial (alguna pregunta no pudo clasificarse). */
    public static final String STATUS_PARTIAL = "partial";

    /** Status que indica un error en OpenJEV (usar fallback conservador en el backend). */
    public static final String STATUS_ERROR = "error";

    /**
     * Extrae una decisión como String. Devuelve {@link Optional#empty()} si la clave
     * no existe o el valor es {@code null}.
     *
     * @param key nombre de la pregunta/decisión.
     * @return valor de la decisión como String, o vacío.
     */
    public Optional<String> decisionAsString(String key) {
        Object value = decisions != null ? decisions.get(key) : null;
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    /**
     * Extrae una decisión booleana. Devuelve {@code false} si la clave no existe,
     * el valor es {@code null} o no es parseable como booleano.
     *
     * @param key nombre de la pregunta/decisión.
     * @return valor booleano de la decisión; {@code false} como fallback conservador.
     */
    public boolean decisionAsBoolean(String key) {
        Object value = decisions != null ? decisions.get(key) : null;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    /**
     * Indica si la respuesta de OpenJEV es fiable (status == "ok").
     * Si no es fiable, el backend debe aplicar lógica conservadora por defecto.
     */
    public boolean isReliable() {
        return STATUS_OK.equals(status);
    }
}
