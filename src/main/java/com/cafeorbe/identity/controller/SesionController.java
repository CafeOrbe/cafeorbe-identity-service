package com.cafeorbe.identity.controller;

import com.cafeorbe.contracts.Rol;
import com.cafeorbe.identity.service.SesionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/sesion")
public class SesionController {

    public record SesionRequest(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 50, message = "El nombre no puede superar 50 caracteres")
            String nombre,
            @NotNull(message = "El rol es obligatorio")
            Rol rol) {
    }

    public record UsuarioResponse(UUID id, String nombre, Rol rol) {
    }

    public record SesionResponse(String token, UsuarioResponse usuario, boolean nuevo) {
    }

    private final SesionService sesiones;

    public SesionController(SesionService sesiones) {
        this.sesiones = sesiones;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SesionResponse iniciar(@Valid @RequestBody SesionRequest peticion) {
        var sesion = sesiones.iniciarSesion(peticion.nombre(), peticion.rol());
        var u = sesion.usuario();
        return new SesionResponse(sesion.token(), new UsuarioResponse(u.getId(), u.getNombre(), u.getRol()), sesion.nuevo());
    }
}
