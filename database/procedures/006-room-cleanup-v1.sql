-- First install only. Select the existing database; execute as a script with DELIMITER support.
-- MySQL >= 8.0.21, InnoDB. No schema/index changes, no procedure calls, no data mutations here.
-- SQL SECURITY INVOKER: server needs SELECT/DELETE, caller installer needs CREATE ROUTINE.
SELECT VERSION() AS mysql_version, DATABASE() AS selected_database;
-- Review these read-only results against docs/room-cleanup.md before executing CREATE bodies.
SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('Phong_Hop','MMR_Phong_Van_Hanh','Tin_Nhan','Tep_Dinh_Kem');
SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('Phong_Hop','MMR_Phong_Van_Hanh','Tin_Nhan','Tep_Dinh_Kem')
ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX;
SELECT ROUTINE_NAME,SECURITY_TYPE FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME IN ('mmr_cleanup_fence_v1','mmr_cleanup_batch_v1','mmr_cleanup_commit_v1');
DELIMITER $$
CREATE PROCEDURE mmr_cleanup_fence_v1(IN p_room BIGINT, IN p_instance VARCHAR(64), IN p_generation VARCHAR(24))
SQL SECURITY INVOKER
BEGIN
 DECLARE v_state VARCHAR(32) DEFAULT NULL;
 DECLARE v_instance VARCHAR(64) DEFAULT NULL;
 DECLARE v_generation VARCHAR(24) DEFAULT NULL;
 IF p_room IS NULL OR p_room<1 OR p_instance IS NULL OR CHAR_LENGTH(p_instance)<1 OR p_generation IS NULL OR CHAR_LENGTH(p_generation)<1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_CLEANUP_FENCE';
 END IF;
 -- Same room/runtime row lock order as Java reopen. Held only during this one bounded CALL.
 SELECT p.Trang_Thai,x.Instance_ID,x.Room_Opaque_ID INTO v_state,v_instance,v_generation
 FROM Phong_Hop p JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=p.Ma_Phong
 WHERE p.Ma_Phong=p_room FOR UPDATE;
 IF v_state IS NULL OR v_state<>'DA_DONG' OR BINARY v_instance<>BINARY p_instance OR BINARY v_generation<>BINARY p_generation THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STALE_CLEANUP';
 END IF;
END$$

CREATE PROCEDURE mmr_cleanup_batch_v1(IN p_room BIGINT, IN p_instance VARCHAR(64), IN p_generation VARCHAR(24), IN p_limit INT)
SQL SECURITY INVOKER
BEGIN
 DECLARE v_files JSON;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 IF @@session.autocommit<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CLEANUP_REQUIRES_AUTOCOMMIT'; END IF;
 IF p_limit IS NULL OR p_limit<1 OR p_limit>100 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_CLEANUP_BATCH'; END IF;
 SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
 START TRANSACTION;
 CALL mmr_cleanup_fence_v1(p_room,p_instance,p_generation);
 SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id',q.Ma_Tep,'messageId',q.Ma_Tin_Nhan,'key',q.Duong_Dan_Luu_Tru)),JSON_ARRAY()) INTO v_files
 FROM (SELECT f.Ma_Tep,f.Ma_Tin_Nhan,f.Duong_Dan_Luu_Tru
       FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON t.Ma_Tin_Nhan=f.Ma_Tin_Nhan
       WHERE t.Ma_Phong=p_room ORDER BY f.Ma_Tep LIMIT p_limit) q;
 COMMIT;
 SELECT JSON_OBJECT('files',v_files) AS result;
END$$

CREATE PROCEDURE mmr_cleanup_commit_v1(IN p_room BIGINT, IN p_instance VARCHAR(64), IN p_generation VARCHAR(24), IN p_deleted JSON, IN p_messages INT)
SQL SECURITY INVOKER
BEGIN
 DECLARE v_messages INT DEFAULT 0;
 DECLARE v_files INT DEFAULT 0;
 DECLARE v_empty BOOLEAN DEFAULT FALSE;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 IF @@session.autocommit<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CLEANUP_REQUIRES_AUTOCOMMIT'; END IF;
 IF p_deleted IS NULL OR JSON_TYPE(p_deleted)<>'ARRAY' OR JSON_LENGTH(p_deleted)>100 OR OCTET_LENGTH(p_deleted)>65536
    OR p_messages IS NULL OR p_messages<1 OR p_messages>500 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_CLEANUP_BATCH';
 END IF;
 IF EXISTS(SELECT 1 FROM JSON_TABLE(p_deleted,'$[*]' COLUMNS(
     id BIGINT PATH '$.id' ERROR ON EMPTY ERROR ON ERROR,
     message_id BIGINT PATH '$.messageId' ERROR ON EMPTY ERROR ON ERROR,
     object_key VARCHAR(500) PATH '$.key' ERROR ON EMPTY ERROR ON ERROR)) j
     WHERE j.id IS NULL OR j.id<1 OR j.message_id IS NULL OR j.message_id<1 OR j.object_key IS NULL
        OR j.object_key NOT LIKE CONCAT('rooms/',p_room,'/files/%')) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_CLEANUP_BATCH';
 END IF;
 SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
 START TRANSACTION;
 CALL mmr_cleanup_fence_v1(p_room,p_instance,p_generation);
 -- Acknowledgement is exact: immutable ID + message + key + room. Already absent is idempotent.
 DELETE f FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON t.Ma_Tin_Nhan=f.Ma_Tin_Nhan
 JOIN JSON_TABLE(p_deleted,'$[*]' COLUMNS(id BIGINT PATH '$.id',message_id BIGINT PATH '$.messageId',object_key VARCHAR(500) PATH '$.key')) j
 ON j.id=f.Ma_Tep AND j.message_id=f.Ma_Tin_Nhan AND BINARY j.object_key=BINARY f.Duong_Dan_Luu_Tru
 WHERE t.Ma_Phong=p_room;
 SET v_files=ROW_COUNT();
 -- Keyset bounded set; files with unacknowledged metadata retain their messages.
 DELETE FROM Tin_Nhan WHERE Ma_Phong=p_room AND Ma_Tin_Nhan IN
 (SELECT id FROM (SELECT t.Ma_Tin_Nhan AS id FROM Tin_Nhan t
   WHERE t.Ma_Phong=p_room AND NOT EXISTS(SELECT 1 FROM Tep_Dinh_Kem f WHERE f.Ma_Tin_Nhan=t.Ma_Tin_Nhan)
   ORDER BY t.Ma_Tin_Nhan LIMIT p_messages) AS batch_ids);
 SET v_messages=ROW_COUNT();
 SELECT NOT EXISTS(SELECT 1 FROM Tin_Nhan WHERE Ma_Phong=p_room) INTO v_empty;
 COMMIT;
 -- SQL emptiness alone never grants reopen: Java also verifies worker leases/manifests/objects.
 SELECT JSON_OBJECT('files',v_files,'messages',v_messages,'empty',v_empty) AS result;
END$$
DELIMITER ;
SELECT ROUTINE_NAME,SECURITY_TYPE FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME IN ('mmr_cleanup_fence_v1','mmr_cleanup_batch_v1','mmr_cleanup_commit_v1');
