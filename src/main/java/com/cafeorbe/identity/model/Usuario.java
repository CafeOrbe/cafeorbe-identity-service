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

    /** Clave de búsqueda: el nombre normalizado, o {@code google:{sub}} en las cuentas de Google. */
    @Column(name = "nombre_normalizado", nullable = false, length = 300)
    private String nombreNormalizado;

    /** SEG-01: identificador estable de la cuenta de Google ({@code sub}); nulo en el acceso por nombre. */
    @Column(name = "google_sub")
    private String googleSub;

    @Column(name = "email", length = 320)
    private String email;

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

    /**
     * Usuario que entra con Google. Dos personas pueden llamarse igual, así que la clave única no es el nombre
     * sino la cuenta de Google ({@code sub}) y el rol.
     */
    public static Usuario deGoogle(String sub, String email, String nombre, Rol rol) {
        var usuario = new Usuario(nombre, rol);
        usuario.nombreNormalizado = claveGoogle(sub);
        usuario.googleSub = sub;
        usuario.email = email;
        return usuario;
    }

    public static String claveGoogle(String sub) {
        return "google:" + sub;
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

    public String getGoogleSub() {
        return googleSub;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEventoPublicado() {
        return eventoPublicado;
    }

    public void marcarEventoPublicado() {
        this.eventoPublicado = true;
    }
}
