package com.wealth.mod5_telephony;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * API de la llamada de voz con el asesor de IA (ElevenLabs Conversational AI + Twilio).
 * <ul>
 *   <li>POST /trigger-call: lanza la llamada saliente al telefono del cliente (202: la llamada es asincrona).</li>
 *   <li>GET  /calls/{conversationId}/status: estado real de la llamada (dialing, in_call, ended, failed), para la UI.</li>
 *   <li>GET  /agent-context/{userId}: contexto ultracompacto de la cartera para el agente de voz (webhook / server
 *       tool de ElevenLabs). Protegido por la cabecera X-Telephony-Secret si hay secreto configurado.</li>
 *   <li>GET  /fund-dfi?isin_or_name=: ficha DFI de un fondo (TER, SRRI, politica) como segunda herramienta del agente.
 *       Misma cabecera secreta.</li>
 * </ul>
 * CORS: lo cubre la configuracion global de shared/web/CorsConfig (/api/**, GET y POST).
 */
@RestController
@RequestMapping("/api/v1/telephony")
public class TelephonyController {

    static final String SECRET_HEADER = "X-Telephony-Secret";

    /** {@code userId}: identificador del cliente (CLI-001) o de su cartera (PF-001). */
    public record TriggerCallRequest(String phoneNumber, String userId) {
    }

    private final TelephonyService telephony;
    private final FundDfiService fundDfi;

    public TelephonyController(TelephonyService telephony, FundDfiService fundDfi) {
        this.telephony = telephony;
        this.fundDfi = fundDfi;
    }

    @PostMapping("/trigger-call")
    public ResponseEntity<TelephonyService.CallStarted> triggerCall(@RequestBody TriggerCallRequest request) {
        return ResponseEntity.accepted().body(telephony.triggerCall(request.phoneNumber(), request.userId()));
    }

    @GetMapping("/calls/{conversationId}/status")
    public TelephonyService.CallStatus callStatus(@PathVariable String conversationId) {
        return telephony.callStatus(conversationId);
    }

    @GetMapping("/agent-context/{userId}")
    public ResponseEntity<AgentContext> agentContext(@PathVariable String userId,
                                                     @RequestHeader(value = SECRET_HEADER, required = false) String secret) {
        telephony.verifyWebhookSecret(secret);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // datos personales: nada de caches intermedias
                .body(telephony.agentContext(userId));
    }

    /** Ficha DFI/KID de un fondo (TER, riesgo SRRI, politica) por ISIN o nombre hablado. Herramienta del agente de voz. */
    @GetMapping("/fund-dfi")
    public ResponseEntity<FundDfiService.FundDfi> fundDfi(
            @RequestParam("isin_or_name") String isinOrName,
            @RequestHeader(value = SECRET_HEADER, required = false) String secret) {
        telephony.verifyWebhookSecret(secret);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(fundDfi.find(isinOrName));
    }

    @ExceptionHandler(TelephonyException.class)
    public ResponseEntity<Map<String, String>> telephonyError(TelephonyException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
