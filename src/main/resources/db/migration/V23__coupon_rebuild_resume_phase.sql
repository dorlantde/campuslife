ALTER TABLE coupon_dispatch_gate ADD COLUMN resume_phase VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL;
-- B exposed no rebuilding operator. If a pre-existing technical row is rebuilding,
-- preserve closure conservatively rather than inventing its former admission phase.
UPDATE coupon_dispatch_gate SET resume_phase='PAUSED' WHERE phase='REBUILDING';
ALTER TABLE coupon_dispatch_gate ADD CONSTRAINT ck_coupon_gate_resume CHECK(
 (phase='REBUILDING' AND resume_phase IS NOT NULL AND resume_phase IN ('OPEN','PAUSED'))
 OR (phase IN ('OPEN','PAUSED') AND resume_phase IS NULL));
