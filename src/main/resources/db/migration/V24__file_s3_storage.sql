-- Historical LOCAL rows and all lifecycle/reference rules stay unchanged.
ALTER TABLE file_asset DROP CHECK ck_file_storage;
ALTER TABLE file_asset ADD CONSTRAINT ck_file_storage CHECK (storage_kind IN ('LOCAL','S3')
    AND REGEXP_LIKE(storage_id, '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$', 'c')
    AND REGEXP_LIKE(storage_key, '^[a-f0-9]{32}[.]pdf$', 'c'));
CREATE INDEX ix_file_tombstone ON file_asset(storage_id,status,id);
