-- Incremental migration for review. DO NOT automatically apply at startup.
-- Run against the already-selected existing database; no CREATE DATABASE / USE / data changes.
-- Existing room and membership rows remain unchanged.
CREATE TABLE MMR_Phong_Van_Hanh (
    Ma_Phong BIGINT NOT NULL PRIMARY KEY,
    Room_Opaque_ID VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    Instance_ID VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    Server_Epoch VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    Ngay_Tao TIMESTAMP NOT NULL,
    CONSTRAINT UQ_MMR_Runtime_Room UNIQUE (Room_Opaque_ID),
    CONSTRAINT FK_MMR_Runtime_Room FOREIGN KEY (Ma_Phong) REFERENCES Phong_Hop(Ma_Phong)
        ON UPDATE CASCADE ON DELETE CASCADE,
    INDEX IX_MMR_Runtime_Owner (Instance_ID, Server_Epoch)
) ENGINE=InnoDB;

CREATE TABLE MMR_Yeu_Cau_Phong (
    Ma_Phien BIGINT NOT NULL,
    Request_ID VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    Operation VARCHAR(32) NOT NULL,
    Body_HMAC BINARY(32) NOT NULL,
    Result_Code VARCHAR(32) NOT NULL,
    Ma_Phong BIGINT NULL,
    Ma_Thanh_Vien_Phong BIGINT NULL,
    Participant_Opaque_ID VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    Ngay_Tao TIMESTAMP NOT NULL,
    Het_Han_Luc TIMESTAMP NOT NULL,
    PRIMARY KEY (Ma_Phien, Request_ID),
    CONSTRAINT FK_MMR_Request_Session FOREIGN KEY (Ma_Phien) REFERENCES Phien_Dang_Nhap(Ma_Phien)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT FK_MMR_Request_Room FOREIGN KEY (Ma_Phong) REFERENCES Phong_Hop(Ma_Phong)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT FK_MMR_Request_Membership FOREIGN KEY (Ma_Thanh_Vien_Phong)
        REFERENCES Thanh_Vien_Phong(Ma_Thanh_Vien_Phong) ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB;
