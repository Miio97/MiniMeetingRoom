-- Optional rollback schema only. Does NOT restore deleted history or enable the retired writer.
CREATE TABLE Thong_Ke_Ket_Noi (
    Ma_Thong_Ke BIGINT AUTO_INCREMENT PRIMARY KEY,

    Ma_Phong BIGINT NOT NULL,

    Ma_Nguoi_Dung BIGINT NOT NULL,

    Do_Tre_Ms DECIMAL(8,2),

    Do_Rung_Ms DECIMAL(8,2),

    Ty_Le_Mat_Goi_Tin DECIMAL(5,2),

    Bitrate_Kbps INT,

    FPS SMALLINT,

    Chieu_Rong SMALLINT,

    Chieu_Cao SMALLINT,

    Trang_Thai_Ket_Noi ENUM(
        'TOT',
        'TRUNG_BINH',
        'KEM'
    ),

    Thoi_Diem_Lay_Mau TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT FK_ThongKeKetNoi_Phong
        FOREIGN KEY (Ma_Phong)
        REFERENCES Phong_Hop(Ma_Phong)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT FK_ThongKeKetNoi_NguoiDung
        FOREIGN KEY (Ma_Nguoi_Dung)
        REFERENCES Nguoi_Dung(Ma_Nguoi_Dung)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    CONSTRAINT CK_ThongKe_TyLeMatGoi
        CHECK (
            Ty_Le_Mat_Goi_Tin IS NULL
            OR
            (Ty_Le_Mat_Goi_Tin >= 0 AND Ty_Le_Mat_Goi_Tin <= 100)
        ),

    CONSTRAINT CK_ThongKe_Bitrate
        CHECK (
            Bitrate_Kbps IS NULL
            OR Bitrate_Kbps >= 0
        ),

    CONSTRAINT CK_ThongKe_FPS
        CHECK (
            FPS IS NULL
            OR FPS >= 0
        )
) ENGINE = InnoDB;
