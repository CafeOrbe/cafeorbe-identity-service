-- SEG-01: acceso con cuenta de Google. La cuenta (sub) más el rol identifican al usuario; el nombre ya no es
-- único entre cuentas de Google, por eso su clave de búsqueda pasa a ser 'google:{sub}'.
alter table usuario add column google_sub varchar(255);
alter table usuario add column email varchar(320);
alter table usuario alter column nombre_normalizado set data type varchar(300);
alter table usuario add constraint uq_usuario_google_rol unique (google_sub, rol);
