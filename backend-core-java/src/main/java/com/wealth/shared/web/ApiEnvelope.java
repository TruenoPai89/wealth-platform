package com.wealth.shared.web;

/**
 * Envoltorio de respuesta estructurada {@code { "data": ..., "error": null }}.
 * <p>
 * Lo usan los endpoints nuevos (p. ej. {@code /api/v1/margin/analyze}). Los endpoints anteriores siguen devolviendo
 * su cuerpo tal cual y {@code { "error": "..." }} en los fallos: no se han tocado.
 *
 * @param data  carga útil (null si hay error).
 * @param error mensaje de error (null si todo fue bien).
 */
public record ApiEnvelope<T>(T data, String error) {

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(data, null);
    }

    public static <T> ApiEnvelope<T> fail(String error) {
        return new ApiEnvelope<>(null, error);
    }
}
