package com.wealth.mod5_telephony;

import org.springframework.http.HttpStatus;

/** Error de telefonia con el estado HTTP que debe recibir el cliente. El mensaje va en espanol: se muestra en la UI. */
public class TelephonyException extends RuntimeException {

    private final HttpStatus status;

    public TelephonyException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
