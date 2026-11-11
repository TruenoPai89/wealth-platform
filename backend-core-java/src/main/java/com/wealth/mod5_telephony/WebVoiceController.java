package com.wealth.mod5_telephony;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * GET /api/v1/voice/web-session?userId=CLI-001 → token de conversacion de ElevenLabs para hablar con el agente desde
 * el navegador (WebRTC). El token es de un solo uso y caduca; la API key nunca sale del servidor.
 */
@RestController
@RequestMapping("/api/v1/voice")
public class WebVoiceController {

    private final WebVoiceService webVoice;

    public WebVoiceController(WebVoiceService webVoice) {
        this.webVoice = webVoice;
    }

    @GetMapping("/web-session")
    public ResponseEntity<WebVoiceService.WebSession> webSession(@RequestParam("userId") String userId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(webVoice.startSession(userId));
    }

    @ExceptionHandler(TelephonyException.class)
    public ResponseEntity<Map<String, String>> voiceError(TelephonyException e) {
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
