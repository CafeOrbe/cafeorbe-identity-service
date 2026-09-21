package com.cafeorbe.identity.model;

import com.cafeorbe.contracts.Rol;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50)
    private String nombre;

    @Column(name = "nombre_normalizado", nullable = false, length = 50)
    private String nombreNormalizado;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Rol rol;

    @Column(name = "evento_publicado", nullable = false)
    private boolean eventoPublicado;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected Usuario() {
    }

    public Usuario(String nombre, Rol rol) {
        this.id = UUID.randomUUID();
        this.nombre = nombre;
        this.nombreNormalizado = normalizar(nombre);
        this.rol = rol;
        this.creadoEn = Instant.now();
    }

    public static String normalizar(String nombre) {
        return nombre.trim().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public Rol getRol() {
        return rol;
    }

    public boolean isEventoPublicado() {
        return eventoPublicado;
    }

    public void marcarEventoPublicado() {
        this.eventoPublicado = true;
    }
}
