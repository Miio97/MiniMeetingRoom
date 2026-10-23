"""Development generator: emits reviewable MySQL routines, never connects to a database."""
from pathlib import Path

OUT = Path(__file__).with_name('005-room-operations-v1.sql')
parts = ['''-- REVIEW / MANUAL INSTALL ONLY. Select the existing application database first.
-- MySQL 8.x, InnoDB, session time_zone='+00:00'. No table/data changes on installation.
-- First install only: intentionally no DROP/REPLACE and no hardcoded DEFINER.
-- Calling a public routine performs writes. Do not CALL with test inputs on Railway.
-- Public routines own their transaction. Never call inside an existing transaction.
-- Only the trusted Java server can supply verified PIN/admission and instance budgets.
DELIMITER $$

CREATE PROCEDURE mmr_internal_session_v1(
 IN p_session BIGINT, IN p_user BIGINT, IN p_token VARCHAR(255),
 IN p_now DATETIME(6), OUT o_expiry DATETIME(6))
SQL SECURITY INVOKER
MODIFIES SQL DATA
BEGIN
 DECLARE v_user BIGINT DEFAULT NULL;
 DECLARE v_token VARCHAR(255);
 DECLARE v_revoked DATETIME(6);
 DECLARE v_status VARCHAR(24);
 SELECT s.Ma_Nguoi_Dung,s.Token_Bam,s.Het_Han_Luc,s.Thu_Hoi_Luc,u.Trang_Thai
 INTO v_user,v_token,o_expiry,v_revoked,v_status
 FROM Phien_Dang_Nhap s JOIN Nguoi_Dung u ON u.Ma_Nguoi_Dung=s.Ma_Nguoi_Dung
 WHERE s.Ma_Phien=p_session FOR UPDATE;
 IF v_user IS NULL OR p_user IS NULL OR v_user<>p_user OR p_token IS NULL
 OR NOT(BINARY v_token=BINARY p_token) OR v_revoked IS NOT NULL
 OR o_expiry<=p_now OR v_status<>'HOAT_DONG' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SESSION_INVALID';
 END IF;
END$$

CREATE PROCEDURE mmr_internal_receipt_v1(
 IN p_session BIGINT, IN p_request VARCHAR(64), IN p_operation VARCHAR(32),
 IN p_body VARBINARY(32), IN p_now DATETIME(6),
 OUT o_found BOOLEAN, OUT o_room BIGINT, OUT o_member BIGINT, OUT o_opaque VARCHAR(24))
SQL SECURITY INVOKER
MODIFIES SQL DATA
BEGIN
 DECLARE v_operation VARCHAR(32) DEFAULT NULL;
 DECLARE v_body BINARY(32);
 DECLARE v_code VARCHAR(32);
 DECLARE v_expiry DATETIME(6);
 DECLARE v_count INT;
 IF p_request IS NULL OR p_request NOT REGEXP '^[A-Za-z0-9_-]{16,64}$'
 OR p_body IS NULL OR OCTET_LENGTH(p_body)<>32 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_REQUEST';
 END IF;
 SET o_room=NULL,o_member=NULL,o_opaque=NULL;
 SELECT Operation,Body_HMAC,Result_Code,Het_Han_Luc,Ma_Phong,Ma_Thanh_Vien_Phong,Participant_Opaque_ID
 INTO v_operation,v_body,v_code,v_expiry,o_room,o_member,o_opaque
 FROM MMR_Yeu_Cau_Phong WHERE Ma_Phien=p_session AND Request_ID=p_request FOR UPDATE;
 SET o_found=(v_operation IS NOT NULL);
 IF o_found THEN
  IF BINARY v_operation<>BINARY p_operation OR v_body<>p_body THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_ID_REUSED';
  END IF;
  IF v_expiry<=p_now THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_EXPIRED'; END IF;
  IF v_code<>'OK' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_INCOMPLETE'; END IF;
 ELSE
  -- Bounded to 1024 examined keys using the existing (Ma_Phien,Request_ID) PK.
  -- Session row is already locked, so concurrent commands cannot race this limit.
  SELECT COUNT(*) INTO v_count FROM
   (SELECT Request_ID FROM MMR_Yeu_Cau_Phong WHERE Ma_Phien=p_session LIMIT 1024) q;
  IF v_count>=1024 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_LIMIT'; END IF;
 END IF;
END$$

CREATE PROCEDURE mmr_internal_result_v1(IN p_room BIGINT, OUT o_result LONGTEXT)
SQL SECURITY INVOKER
READS SQL DATA
BEGIN
 SET o_result=NULL;
 SELECT JSON_OBJECT('databaseRoomId',r.Ma_Phong,'roomId',x.Room_Opaque_ID,
 'joinCode',r.Ma_Phong_Tham_Gia,'name',r.Ten_Phong,'pinHash',r.Ma_PIN_Bam,
 'hostUserId',r.Ma_Chu_Phong,'status',r.Trang_Thai,'locked',r.Da_Khoa,
 'capacity',r.So_Nguoi_Toi_Da,'instanceId',x.Instance_ID,'serverEpoch',x.Server_Epoch,
 'createdAt',r.Ngay_Tao)
 INTO o_result FROM Phong_Hop r JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=r.Ma_Phong
 WHERE r.Ma_Phong=p_room;
 IF o_result IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ROOM_NOT_FOUND'; END IF;
END$$
''']

def public(name, params, declarations, body):
    parts.append(f'''\nCREATE PROCEDURE {name}(
{params})
SQL SECURITY INVOKER
MODIFIES SQL DATA
proc: BEGIN
 DECLARE v_started BOOLEAN DEFAULT FALSE;
 DECLARE v_now DATETIME(6);
 DECLARE v_expiry DATETIME(6);
 DECLARE v_replay BOOLEAN DEFAULT FALSE;
 DECLARE v_room BIGINT DEFAULT NULL;
 DECLARE v_member BIGINT DEFAULT NULL;
 DECLARE v_saved_opaque VARCHAR(24);
 DECLARE v_result LONGTEXT;
{declarations}
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  IF v_started THEN ROLLBACK; END IF;
  RESIGNAL;
 END;
 IF @@session.autocommit<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AUTOCOMMIT_REQUIRED';
 END IF;
 IF p_instance IS NULL OR p_instance='' OR p_epoch IS NULL OR p_epoch='' OR p_epoch='legacy' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_INSTANCE';
 END IF;
 SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
 START TRANSACTION;
 SET v_started=TRUE,v_now=CURRENT_TIMESTAMP(6);
{body}
 COMMIT;
 SET v_started=FALSE;
 SELECT 'OK' AS code,v_replay AS replay,v_result AS result;
END$$
''')

principal = ''' IN p_session BIGINT, IN p_user BIGINT, IN p_token_hash VARCHAR(255),
 IN p_request VARCHAR(64), IN p_body_hmac VARBINARY(32),
 IN p_instance VARCHAR(64), IN p_epoch VARCHAR(24),'''
auth = ' CALL mmr_internal_session_v1(p_session,p_user,p_token_hash,v_now,v_expiry);'
def receipt(op):
    return f" CALL mmr_internal_receipt_v1(p_session,p_request,'{op}',p_body_hmac,v_now,v_replay,v_room,v_member,v_saved_opaque);"
def save(op, opaque):
    return f''' INSERT INTO MMR_Yeu_Cau_Phong(Ma_Phien,Request_ID,Operation,Body_HMAC,Result_Code,
 Ma_Phong,Ma_Thanh_Vien_Phong,Participant_Opaque_ID,Ngay_Tao,Het_Han_Luc)
 VALUES(p_session,p_request,'{op}',p_body_hmac,'OK',v_room,v_member,{opaque},v_now,v_expiry);'''

public('mmr_create_room_v1', principal+'''
 IN p_room_opaque VARCHAR(24), IN p_join_code VARCHAR(20), IN p_name VARCHAR(100),
 IN p_pin_hash VARCHAR(255), IN p_capacity INT, IN p_locked BOOLEAN,
 IN p_max_participants INT, IN p_admission_allowed BOOLEAN''',
 ' DECLARE v_current_opaque VARCHAR(24);\n DECLARE v_instance VARCHAR(64);\n DECLARE v_epoch VARCHAR(24);',
auth+'\n'+receipt('CREATE_ROOM')+'''
 IF NOT v_replay THEN
  IF p_admission_allowed IS NULL OR NOT p_admission_allowed THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SERVER_CAPACITY';
  END IF;
  IF p_max_participants IS NULL OR p_max_participants<2 OR p_max_participants>50
  OR p_capacity IS NULL OR p_capacity<2 OR p_capacity>p_max_participants
  OR p_name IS NULL OR CHAR_LENGTH(TRIM(p_name))=0 OR p_locked IS NULL
  OR p_room_opaque IS NULL OR p_room_opaque NOT REGEXP '^r_[A-Za-z0-9_-]{22}$'
  OR p_join_code IS NULL OR p_join_code NOT REGEXP '^MMR-[A-HJ-NP-Z2-9]{16}$' THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_ROOM';
  END IF;
  INSERT INTO Phong_Hop(Ma_Phong_Tham_Gia,Ten_Phong,Ma_PIN_Bam,Ma_Chu_Phong,
   Trang_Thai,Da_Khoa,So_Nguoi_Toi_Da,Ngay_Tao)
  VALUES(p_join_code,TRIM(p_name),p_pin_hash,p_user,'CHO',p_locked,p_capacity,v_now);
  SET v_room=LAST_INSERT_ID();
  INSERT INTO MMR_Phong_Van_Hanh(Ma_Phong,Room_Opaque_ID,Instance_ID,Server_Epoch,Ngay_Tao)
  VALUES(v_room,p_room_opaque,p_instance,p_epoch,v_now);
'''+save('CREATE_ROOM','p_room_opaque')+'''
 ELSE
  SELECT Room_Opaque_ID,Instance_ID,Server_Epoch INTO v_current_opaque,v_instance,v_epoch
  FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=v_room FOR UPDATE;
  IF v_current_opaque IS NULL OR v_saved_opaque IS NULL
  OR BINARY v_current_opaque<>BINARY v_saved_opaque OR BINARY v_instance<>BINARY p_instance
  OR BINARY v_epoch<>BINARY p_epoch THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_ALREADY_ENDED';
  END IF;
 END IF;
 CALL mmr_internal_result_v1(v_room,v_result);''')

room_declarations = ''' DECLARE v_opaque VARCHAR(24) DEFAULT NULL;
 DECLARE v_instance VARCHAR(64);
 DECLARE v_epoch VARCHAR(24);
 DECLARE v_host BIGINT;
 DECLARE v_status VARCHAR(24);
 DECLARE v_locked BOOLEAN;
 DECLARE v_capacity INT;
 DECLARE v_count INT;
 DECLARE v_duplicate INT;
 DECLARE v_active BOOLEAN DEFAULT FALSE;'''
room_lock = '''
 SELECT x.Room_Opaque_ID,x.Instance_ID,x.Server_Epoch,r.Ma_Chu_Phong,r.Trang_Thai,r.Da_Khoa,r.So_Nguoi_Toi_Da
 INTO v_opaque,v_instance,v_epoch,v_host,v_status,v_locked,v_capacity
 FROM Phong_Hop r JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=r.Ma_Phong
 WHERE r.Ma_Phong=p_room FOR UPDATE;
 IF v_opaque IS NULL OR BINARY v_instance<>BINARY p_instance OR BINARY v_epoch<>BINARY p_epoch THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ROOM_NOT_FOUND';
 END IF;
 IF p_expected_opaque IS NULL OR BINARY v_opaque<>BINARY p_expected_opaque THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STALE_ROOM';
 END IF;
 IF v_replay AND (v_room IS NULL OR v_room<>p_room) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_ID_REUSED';
 END IF;
 SET v_room=p_room;
'''
public('mmr_join_room_v1', principal+'''
 IN p_room BIGINT, IN p_expected_opaque VARCHAR(24), IN p_participant VARCHAR(24),
 IN p_admission_verified BOOLEAN, IN p_max_participants INT''',room_declarations,
auth+'\n'+receipt('JOIN_ROOM')+room_lock+'''
 IF v_replay THEN
  SELECT EXISTS(SELECT 1 FROM Thanh_Vien_Phong WHERE Ma_Thanh_Vien_Phong=v_member
   AND Ma_Phong=v_room AND Ma_Nguoi_Dung=p_user AND Roi_Phong_Luc IS NULL) INTO v_active;
  IF NOT v_active OR v_status='DA_DONG' THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_ALREADY_ENDED';
  END IF;
 ELSE
  IF p_admission_verified IS NULL OR NOT p_admission_verified THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ADMISSION_FORBIDDEN';
  END IF;
  IF v_status='DA_DONG' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ROOM_CLOSED'; END IF;
  IF v_host<>p_user AND v_status='CHO' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ROOM_NOT_STARTED'; END IF;
  IF v_host<>p_user AND v_locked THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ROOM_LOCKED'; END IF;
  IF p_max_participants IS NULL OR p_max_participants<2 OR p_max_participants>50
  OR v_capacity>p_max_participants THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ROOM_CAPACITY_POLICY'; END IF;
  IF p_participant IS NULL OR p_participant NOT REGEXP '^p_[A-Za-z0-9_-]{22}$' THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_REQUEST';
  END IF;
  -- Room row locking serializes competing admissions; READ COMMITTED is required.
  SELECT COUNT(*),COALESCE(SUM(Ma_Nguoi_Dung=p_user),0) INTO v_count,v_duplicate
  FROM Thanh_Vien_Phong WHERE Ma_Phong=v_room AND Roi_Phong_Luc IS NULL;
  IF v_duplicate>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ALREADY_JOINED'; END IF;
  IF v_count>=LEAST(50,v_capacity) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ROOM_FULL'; END IF;
  INSERT INTO Thanh_Vien_Phong(Ma_Phong,Ma_Nguoi_Dung,Vai_Tro,Tham_Gia_Luc)
  VALUES(v_room,p_user,IF(v_host=p_user,'CHU_PHONG','THANH_VIEN'),v_now);
  SET v_member=LAST_INSERT_ID(),v_saved_opaque=p_participant,v_active=TRUE;
  IF v_status<>'DANG_HOAT_DONG' THEN UPDATE Phong_Hop SET Trang_Thai='DANG_HOAT_DONG' WHERE Ma_Phong=v_room; END IF;
'''+save('JOIN_ROOM','p_participant')+'''
 END IF;
 CALL mmr_internal_result_v1(v_room,v_result);
 SET v_result=JSON_SET(v_result,'$.membershipId',v_member,'$.participantId',v_saved_opaque,'$.membershipActive',v_active);''')

public('mmr_exit_room_v1',principal+'''
 IN p_room BIGINT, IN p_expected_opaque VARCHAR(24), IN p_membership BIGINT,
 IN p_mode VARCHAR(12), IN p_next_session BIGINT, IN p_next_user BIGINT,
 IN p_next_token_hash VARCHAR(255)''',room_declarations+'''
 DECLARE v_target_expiry DATETIME(6);
 DECLARE v_successor BIGINT DEFAULT NULL;''', '''
 -- Use the same session lock order as RoomRepository.transferHost.
 IF p_mode='TRANSFER' THEN
  IF p_next_session IS NULL OR p_next_user IS NULL OR p_next_user=p_user THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PARTICIPANT_NOT_FOUND';
  END IF;
  IF p_session<p_next_session THEN
'''+auth+'''
   CALL mmr_internal_session_v1(p_next_session,p_next_user,p_next_token_hash,v_now,v_target_expiry);
  ELSE
   CALL mmr_internal_session_v1(p_next_session,p_next_user,p_next_token_hash,v_now,v_target_expiry);
'''+auth+'''
  END IF;
 ELSE
'''+auth+'''
 END IF;
'''+receipt('EXIT_ROOM')+room_lock+'''
 IF v_replay THEN
  IF v_saved_opaque IS NULL OR BINARY v_saved_opaque<>BINARY v_opaque THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REQUEST_ALREADY_ENDED';
  END IF;
 ELSE
  IF p_mode IS NULL OR p_mode NOT IN ('LEAVE','CLOSE','TRANSFER') THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='INVALID_REQUEST';
  END IF;
  SELECT Ma_Thanh_Vien_Phong INTO v_member FROM Thanh_Vien_Phong
  WHERE Ma_Thanh_Vien_Phong=p_membership AND Ma_Phong=v_room AND Ma_Nguoi_Dung=p_user
  AND Roi_Phong_Luc IS NULL FOR UPDATE;
  IF v_member IS NULL OR v_status='DA_DONG' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='NOT_A_MEMBER'; END IF;
  IF p_mode IN ('CLOSE','TRANSFER') AND v_host<>p_user THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOST_REQUIRED';
  END IF;
  IF p_mode='LEAVE' AND v_host=p_user THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOST_DECISION_REQUIRED';
  END IF;
  IF p_mode='TRANSFER' THEN
   SELECT Ma_Thanh_Vien_Phong INTO v_successor FROM Thanh_Vien_Phong
   WHERE Ma_Phong=v_room AND Ma_Nguoi_Dung=p_next_user AND Roi_Phong_Luc IS NULL LIMIT 1 FOR UPDATE;
   IF v_successor IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PARTICIPANT_NOT_FOUND'; END IF;
   UPDATE Phong_Hop SET Ma_Chu_Phong=p_next_user WHERE Ma_Phong=v_room;
   UPDATE Thanh_Vien_Phong SET Vai_Tro=IF(Ma_Nguoi_Dung=p_next_user,'CHU_PHONG','THANH_VIEN')
   WHERE Ma_Phong=v_room AND Roi_Phong_Luc IS NULL;
  END IF;
  IF p_mode='CLOSE' THEN
   UPDATE Phong_Hop SET Trang_Thai='DA_DONG',Ngay_Dong=v_now WHERE Ma_Phong=v_room;
   UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc=v_now,Ly_Do_Roi='PHONG_DONG'
   WHERE Ma_Phong=v_room AND Roi_Phong_Luc IS NULL;
  ELSE
   UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc=v_now,Ly_Do_Roi='TU_ROI' WHERE Ma_Thanh_Vien_Phong=v_member;
  END IF;
'''+save('EXIT_ROOM','v_opaque')+'''
 END IF;
 CALL mmr_internal_result_v1(v_room,v_result);
 SET v_result=JSON_SET(v_result,'$.membershipId',v_member,'$.cleanupRequired',
  JSON_UNQUOTE(JSON_EXTRACT(v_result,'$.status'))='DA_DONG');''')

parts.append('\nDELIMITER ;\n')
OUT.write_text('\n'.join(parts),encoding='utf-8')
print(OUT)

bundle = OUT.with_name('005-install-all.sql')
preflight = OUT.with_name('005-room-preflight.sql').read_text(encoding='utf-8')
verification = '''
-- Installation verification: expect exactly six routines, all SECURITY_TYPE=INVOKER.
SELECT ROUTINE_NAME, ROUTINE_TYPE, SECURITY_TYPE
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME IN (
 'mmr_internal_session_v1','mmr_internal_receipt_v1','mmr_internal_result_v1',
 'mmr_create_room_v1','mmr_join_room_v1','mmr_exit_room_v1')
ORDER BY ROUTINE_NAME;
SELECT COUNT(*) AS installed_routines, 6 AS expected_routines
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_TYPE='PROCEDURE' AND ROUTINE_NAME IN (
 'mmr_internal_session_v1','mmr_internal_receipt_v1','mmr_internal_result_v1',
 'mmr_create_room_v1','mmr_join_room_v1','mmr_exit_room_v1');
'''
bundle.write_text('''-- MINI MEETING ROOM: SINGLE-FILE ROUTINE INSTALLER
-- Select the existing application database before running this entire script.
-- Use a MySQL script runner supporting DELIMITER (e.g. Workbench / mysql CLI).
-- First install only. Stop on the first error; do not force continuation.
-- Does not include rollback, DROP, fixture data, or application CALL statements.
-- Do not also execute 005-room-operations-v1.sql: its definitions are included here.
-- Creating routines does not enable their use in the current Java application.
-- Local validation used MariaDB; MySQL production execution remains unverified.

-- STEP 1: read-only environment inspection.
'''+preflight+'\n-- STEP 2: install six routines.\n'+OUT.read_text(encoding='utf-8')+
'\n-- STEP 3: read-only installation verification.\n'+verification,encoding='utf-8')
print(bundle)
