package com.wealth.mod5_telephony;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fichas DFI/KID de los fondos para el agente de voz (herramienta {@code fund-dfi}). Busca por ISIN exacto o por
 * nombre/alias hablado ("el msci world", "oro"), sin acentos ni mayusculas.
 * <p>
 * IMPORTANTE: nunca inventa datos. Fondo desconocido -> 404 con la lista de fondos disponibles; varios candidatos ->
 * 409 con los candidatos, para que el agente pregunte cual. Los datos vienen de un mock ILUSTRATIVO
 * (bank-simulator-mocks/api-json-responses/fund_dfi.json) y cada respuesta lo declara en {@code notice}.
 * TODO(DFI real): sustituir el fichero por el KID oficial de cada fondo (fuente regulada) al conectar datos reales.
 */
@Service
public class FundDfiService {

    static final String NOTICE = "Datos ilustrativos de demostración; no son el DFI oficial del fondo.";
    private static final int MAX_QUERY_LENGTH = 80;
    private static final java.util.Set<String> FILLER_WORDS =
            java.util.Set.of("el", "la", "los", "las", "un", "una", "de", "del", "fondo", "fondos", "etf");

    /** Ficha que recibe el agente. {@code srri} en escala 1-7 (indicador de riesgo del DFI). */
    public record FundDfi(String isin, String name, String terPct, String srri, String policy, String notice) {
    }

    record Entry(String name, List<String> aliases, String terPct, int srri, String policy) {
    }

    private final ObjectMapper objectMapper;
    private final Path file;

    public FundDfiService(ObjectMapper objectMapper,
                          @Value("${wealth.telephony.dfi-file:../bank-simulator-mocks/api-json-responses/fund_dfi.json}")
                          String file) {
        this.objectMapper = objectMapper;
        this.file = Path.of(file);
    }

    public FundDfi find(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Indica el ISIN o el nombre del fondo (isin_or_name).");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("El nombre del fondo es demasiado largo.");
        }
        Map<String, Entry> funds = load();

        Entry exact = funds.get(query.trim().toUpperCase(Locale.ROOT));
        if (exact != null) {
            return toFicha(query.trim().toUpperCase(Locale.ROOT), exact);
        }

        // El agente habla ("el msci world", "el fondo de oro"): se ignoran articulos y palabras de relleno.
        List<String> tokens = java.util.Arrays.stream(normalize(query).split("\\s+"))
                .filter(t -> !FILLER_WORDS.contains(t))
                .toList();
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Indica el ISIN o el nombre del fondo (isin_or_name).");
        }
        List<String> matches = new ArrayList<>();
        funds.forEach((isin, entry) -> {
            String haystack = normalize(entry.name() + " " + String.join(" ", entry.aliases()));
            if (tokens.stream().allMatch(haystack::contains)) {
                matches.add(isin);
            }
        });

        if (matches.size() == 1) {
            return toFicha(matches.get(0), funds.get(matches.get(0)));
        }
        if (matches.isEmpty()) {
            throw new TelephonyException(HttpStatus.NOT_FOUND,
                    "No hay ficha DFI para «" + query.trim() + "». Fondos disponibles: " + names(funds, funds.keySet()) + ".");
        }
        throw new TelephonyException(HttpStatus.CONFLICT,
                "«" + query.trim() + "» coincide con varios fondos: " + names(funds, matches) + ". Pregunta cuál.");
    }

    private Map<String, Entry> load() {
        try {
            Map<String, Entry> funds = new LinkedHashMap<>();
            Map<String, Object> raw = objectMapper.readValue(Files.readString(file), new TypeReference<>() {
            });
            raw.forEach((key, value) -> {
                if (!key.startsWith("_")) { // "_comment" y similares no son fondos
                    funds.put(key, objectMapper.convertValue(value, Entry.class));
                }
            });
            return funds;
        } catch (IOException e) {
            throw new TelephonyException(HttpStatus.SERVICE_UNAVAILABLE, "Las fichas DFI no están disponibles ahora mismo.");
        }
    }

    private static FundDfi toFicha(String isin, Entry e) {
        return new FundDfi(isin, e.name(), e.terPct() + "%", e.srri() + "/7", e.policy(), NOTICE);
    }

    private static String names(Map<String, Entry> funds, Iterable<String> isins) {
        List<String> names = new ArrayList<>();
        isins.forEach(isin -> names.add(funds.get(isin).name()));
        return String.join(", ", names);
    }

    /** Minusculas, sin acentos y solo letras/numeros/&: "Renta Fija" y "renta  fija" comparan igual. */
    static String normalize(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9&]+", " ").trim();
    }
}
