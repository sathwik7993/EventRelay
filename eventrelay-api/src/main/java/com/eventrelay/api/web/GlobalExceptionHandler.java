package com.eventrelay.api.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(),
                        (a, b) -> a));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", fields);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return body(status, status.name(), ex.getReason(), null);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code,
                                                      String message, Object details) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", code);
        map.put("message", message);
        if (details != null) {
            map.put("details", details);
        }
        map.put("timestamp", OffsetDateTime.now().toString());
        return ResponseEntity.status(status).body(map);
    }
}
