CREATE TABLE file_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    storage_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_name VARCHAR(200) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pdf_page_count INT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_storage (storage_id, storage_key),
    KEY ix_file_owner (owner_id, id),
    KEY ix_file_expiry (storage_id, status, expires_at, id),
    CONSTRAINT fk_file_owner FOREIGN KEY (owner_id) REFERENCES account(id),
    CONSTRAINT ck_file_storage CHECK (storage_kind = 'LOCAL'
        AND REGEXP_LIKE(storage_id, '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$', 'c')
        AND REGEXP_LIKE(storage_key, '^[a-f0-9]{32}[.]pdf$', 'c')),
    CONSTRAINT ck_file_name CHECK (CHAR_LENGTH(original_name) BETWEEN 1 AND 200),
    CONSTRAINT ck_file_size CHECK (byte_size BETWEEN 1 AND 20971520),
    CONSTRAINT ck_file_pages CHECK (pdf_page_count BETWEEN 1 AND 500),
    CONSTRAINT ck_file_hash CHECK (REGEXP_LIKE(sha256, '^[a-f0-9]{64}$', 'c')),
    CONSTRAINT ck_file_status CHECK (status IN ('UPLOADING', 'AVAILABLE', 'DELETING', 'DELETED')),
    CONSTRAINT ck_file_lifecycle CHECK ((status = 'DELETED' AND deleted_at IS NOT NULL)
        OR (status <> 'DELETED' AND deleted_at IS NULL)),
    CONSTRAINT ck_file_upload_expiry CHECK (status <> 'UPLOADING' OR expires_at IS NOT NULL),
    CONSTRAINT ck_file_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
