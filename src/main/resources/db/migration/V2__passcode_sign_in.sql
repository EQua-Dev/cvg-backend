-- Sign-in moves from SMS codes to phone + passcode.
-- A NULL passcode_hash means the member still uses the default: the last 4 digits of their phone.
-- An admin reset sets it back to NULL.

ALTER TABLE member
    ADD COLUMN passcode_hash            VARCHAR(100),
    ADD COLUMN failed_passcode_attempts SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN passcode_locked_until    TIMESTAMPTZ;

DROP TABLE otp_challenge;
