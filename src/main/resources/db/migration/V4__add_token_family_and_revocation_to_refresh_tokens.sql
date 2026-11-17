-- V4: Bổ sung family_id và revoked_at để theo dõi chuỗi token và phát hiện tái sử dụng (Token Reuse Detection)
ALTER TABLE refresh_tokens ADD COLUMN family_id UUID;
ALTER TABLE refresh_tokens ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;

-- Gán family_id mặc định cho các token đã tồn tại từ trước
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
