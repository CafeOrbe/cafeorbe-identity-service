package com.cafeorbe.identity.repository;

import com.cafeorbe.contracts.Rol;
import com.cafeorbe.identity.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByNombreNormalizadoAndRol(String nombreNormalizado, Rol rol);
}
