package com.wealth.mod4_pledge;
import com.wealth.shared.domain.AssetClass;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
/**
* Terminos de un prestamo pignoraticio (lombardo) ya confirmados por el
cliente.
* <p>
* Los terminos los extrae Claude del texto del contrato (servicio Python),
el cliente los revisa
* en pantalla y el frontend los envia aqui. A partir de este punto todo es
calculo determinista.
*
* @param portfolioId cartera pignorada.
* @param contractId identificador del contrato.
* @param loanId identificador del prestamo.
* @param loanBalance saldo dispuesto (deuda actual), en EUR.
* @param ltvMax limite de disposicion (p. ej. 0.70).
* @param ltvMarginCall umbral de margin call (p. ej. 0.80). Debe ser mayor
que ltvMax.
* @param cureDays dias habiles para reponer garantias tras el aviso
(puede ser null).
* @param haircuts descuento por clase de activo (0.30 = cuenta el 70
%). Una clase ausente
* se trata como NO admitida (haircut 1): criterio
conservador.
*/
public record PledgeContractEntity(
    String portfolioId,
    String contractId,
    String loanId,
    BigDecimal loanBalance,
    BigDecimal ltvMax,
    BigDecimal ltvMarginCall,
    Integer cureDays,
    Map<AssetClass, BigDecimal> haircuts
) {
    public PledgeContractEntity {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new IllegalArgumentException("portfolioId es obligatorio");
        }
        if (loanBalance == null || loanBalance.signum() < 0) {
            throw new IllegalArgumentException("loanBalance debe ser >= 0");
        }
        
        requireFraction(ltvMax, "ltvMax");
        requireFraction(ltvMarginCall, "ltvMarginCall");
        
        if (ltvMarginCall.compareTo(ltvMax) <= 0) {
            throw new IllegalArgumentException("ltvMarginCall debe ser mayor que ltvMax");
        }

        Map<AssetClass, BigDecimal> copy = new EnumMap<>(AssetClass.class);
        if (haircuts != null) {
            haircuts.forEach((k, v) -> {
                if (v == null || v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("haircut de " + k + " debe estar entre 0 y 1");
                }
                copy.put(k, v);
            });
        }
        haircuts = Map.copyOf(copy);
    }

    /** 
     * Haircut aplicable; 1 (no admitido) si el contrato no menciona la clase. 
     */
    public BigDecimal haircutFor(AssetClass assetClass) {
        return haircuts.getOrDefault(assetClass, BigDecimal.ONE);
    }

    private static void requireFraction(BigDecimal v, String name) {
        if (v == null || v.signum() <= 0 || v.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(name + " debe estar entre 0 y 1");
        }
    }
}