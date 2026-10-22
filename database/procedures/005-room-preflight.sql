-- Read-only. Run in the selected application database before installation.
SELECT VERSION() AS database_version,DATABASE() AS selected_database,
 @@session.time_zone AS session_time_zone,@@session.sql_mode AS sql_mode;
SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN
 ('Nguoi_Dung','Phien_Dang_Nhap','Phong_Hop','Thanh_Vien_Phong','MMR_Phong_Van_Hanh','MMR_Yeu_Cau_Phong');
SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME
 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME IN ('Phien_Dang_Nhap','Thanh_Vien_Phong','MMR_Phong_Van_Hanh','MMR_Yeu_Cau_Phong')
 ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX;
SELECT ROUTINE_NAME,SECURITY_TYPE FROM information_schema.ROUTINES
 WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME LIKE 'mmr%v1';
SHOW GRANTS FOR CURRENT_USER();
