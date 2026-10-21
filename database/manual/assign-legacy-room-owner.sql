-- REVIEW ONLY. Never run automatically. No schema changes and no DELETE statements.
-- First confirm which closed legacy rooms and physical storage belong to this instance.
-- Obtain the existing instance.id from the server's private meeting config; do not publish that config.
-- Replace BOTH occurrences of IN (0) with the same explicit reviewed room ID list.
-- Default execution changes nothing: room 0 is invalid and the transaction ends with ROLLBACK.
SET @owner_instance = 'REPLACE_WITH_INSTANCE_ID';

START TRANSACTION;

SELECT p.Ma_Phong, p.Ten_Phong, p.Ma_Chu_Phong, p.Trang_Thai, x.Instance_ID
FROM Phong_Hop p
LEFT JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=p.Ma_Phong
WHERE p.Ma_Phong IN (0)
FOR UPDATE;

INSERT INTO MMR_Phong_Van_Hanh
    (Ma_Phong, Room_Opaque_ID, Instance_ID, Server_Epoch, Ngay_Tao)
SELECT p.Ma_Phong, CONCAT('l_', LEFT(REPLACE(UUID(), '-', ''), 22)),
       @owner_instance, 'legacy', UTC_TIMESTAMP()
FROM Phong_Hop p
WHERE p.Ma_Phong IN (0)
  AND p.Trang_Thai='DA_DONG'
  AND @owner_instance <> 'REPLACE_WITH_INSTANCE_ID'
  AND @owner_instance REGEXP '^[A-Za-z0-9_-]{1,64}$'
  AND NOT EXISTS (SELECT 1 FROM MMR_Phong_Van_Hanh x WHERE x.Ma_Phong=p.Ma_Phong);

-- COMMIT only after explicit approval of ownership and the resulting cleanup.
-- Once committed, the matching instance can immediately clean these closed rooms.
ROLLBACK;
