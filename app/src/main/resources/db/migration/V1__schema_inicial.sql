CREATE TABLE search_job (
    id          uuid PRIMARY KEY,
    nombre      text        NOT NULL CHECK (char_length(nombre) >= 3),
    tipo_persona text       NOT NULL CHECK (tipo_persona IN ('nat', 'jur')),
    categoria   text        NOT NULL CHECK (categoria IN ('penal', 'civil', 'laboral', 'familiar', 'extincionDominio')),
    status      text        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    error       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    finished_at timestamptz
);

-- Cada fila es una unidad de trabajo (1 mensaje = 1 petición HTTP) y a la vez el outbox:
-- published_at NULL = pendiente de publicar en RabbitMQ.
CREATE TABLE work_item (
    id              uuid PRIMARY KEY,
    job_id          uuid        NOT NULL REFERENCES search_job (id) ON DELETE CASCADE,
    parent_id       uuid        REFERENCES work_item (id) ON DELETE CASCADE,
    type            text        NOT NULL CHECK (type IN ('SEARCH', 'LIST', 'RESOLVE_SUJETOS', 'DETALLE', 'ACTUACIONES', 'SUJETOS')),
    level           text        CHECK (level IN ('RAIZ', 'DEPARTAMENTO', 'MUNICIPIO', 'ENTIDAD', 'ESPECIALIDAD', 'DESPACHO')),
    codigo          text        NOT NULL DEFAULT '',
    codigo_label    text,
    pagina          integer     NOT NULL DEFAULT 1 CHECK (pagina >= 1),
    id_proceso      bigint,
    status          text        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'EXPANDED', 'EXCEEDED', 'FAILED', 'CANCELLED')),
    attempts        integer     NOT NULL DEFAULT 0,
    priority        smallint    NOT NULL DEFAULT 0,
    published_at    timestamptz,
    locked_at       timestamptz,
    last_error      text,
    total_registros integer,
    total_paginas   integer,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_work_item_outbox ON work_item (created_at)
    WHERE status = 'PENDING' AND published_at IS NULL;
CREATE INDEX idx_work_item_stuck ON work_item (locked_at)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_work_item_job_status ON work_item (job_id, status);
CREATE INDEX idx_work_item_parent ON work_item (parent_id);

CREATE TABLE proceso_hit (
    job_id                uuid    NOT NULL REFERENCES search_job (id) ON DELETE CASCADE,
    id_proceso            bigint  NOT NULL,
    id_conexion           bigint,
    llave_proceso         text,
    fecha_proceso         text,
    fecha_ultima_actuacion text,
    despacho              text,
    departamento          text,
    sujetos_procesales    text,
    ciudad                text,
    categoria             text,
    sujetos_truncados     boolean NOT NULL DEFAULT false,
    detalle               jsonb,
    ultima_actuacion      jsonb,
    sujetos_completos     jsonb,
    detail_status         text    NOT NULL DEFAULT 'PENDING',
    found_at              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, id_proceso)
);

-- Auditoría de peticiones salientes: evidencia de qué tan amable fue el sistema con el servidor.
CREATE TABLE request_log (
    id           bigserial   PRIMARY KEY,
    ts           timestamptz NOT NULL DEFAULT now(),
    job_id       uuid,
    work_item_id uuid,
    endpoint     text        NOT NULL,
    http_status  integer,
    outcome      text        NOT NULL,
    duration_ms  integer,
    wait_ms      integer
);

CREATE INDEX idx_request_log_ts ON request_log (ts);
