package com.cafeorbe.identity.controller;

import com.cafeorbe.identity.service.SesionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ManejadorDeErrores {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        String mensaje = campos.values().stream().findFirst().orElse("Datos inválidos");
        return ResponseEntity.badRequest().body(new ApiError(400, mensaje, campos));
    }

    /** SEG-01 / SEG-05: token de Google o de sesión no válido. El motivo exacto solo va a la auditoría. */
    @ExceptionHandler(SesionService.SesionInvalidaException.class)
    public ResponseEntity<ApiError> sesionInvalida(SesionService.SesionInvalidaException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(401, "Sesión requerida o expirada", Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> cuerpoIlegible(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "La solicitud no es válida: revisa el nombre y el rol", Map.of()));
    }
}
