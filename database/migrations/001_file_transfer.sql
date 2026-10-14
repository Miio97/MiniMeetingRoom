-- Apply once to an existing Mini_Meeting_Room database before enabling file transfer.
-- For a new database, schema.sql already includes these additions: do not apply both.
-- Metadata is inserted only after the stored file has been verified (AVAILABLE).
-- Existing attachment rows retain NULL Transfer_Id; their bytes/checksums need separate validation.
-- Select the application's existing database in your MySQL client before running.
-- No USE statement: deployments may use a different database name (e.g. Railway).

ALTER TABLE Tep_Dinh_Kem
    ADD COLUMN Transfer_Id VARCHAR(36) NULL,
    ADD CONSTRAINT UQ_TepDinhKem_Transfer UNIQUE (Transfer_Id);

CREATE INDEX IX_ThanhVienPhong_QuyenTep
    ON Thanh_Vien_Phong (Ma_Phong, Ma_Nguoi_Dung, Roi_Phong_Luc);

CREATE INDEX IX_TinNhan_PhongTep
    ON Tin_Nhan (Ma_Phong, Ma_Tin_Nhan);
