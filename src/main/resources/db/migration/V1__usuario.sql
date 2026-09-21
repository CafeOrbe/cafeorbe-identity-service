create table usuario (
    id                 uuid                     primary key,
    nombre             varchar(50)              not null,
    nombre_normalizado varchar(50)              not null,
    rol                varchar(20)              not null,
    evento_publicado   boolean                  not null default false,
    creado_en          timestamp with time zone not null,
    constraint uq_usuario_nombre_rol unique (nombre_normalizado, rol)
);
