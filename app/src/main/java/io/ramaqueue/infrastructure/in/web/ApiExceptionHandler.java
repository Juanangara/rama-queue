package io.ramaqueue.infrastructure.in.web;

import io.ramaqueue.domain.model.EntradaInvalidaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(EntradaInvalidaException.class)
    ResponseEntity<Map<String, String>> entradaInvalida(EntradaInvalidaException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
