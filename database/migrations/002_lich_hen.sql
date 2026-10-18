-- Áp dụng một lần cho database đã có trước khi sử dụng tính năng lịch hẹn.
-- Đối với database tạo mới, schema.sql đã bao gồm bảng Lich_Hen: không cần chạy file này.
-- Chọn database tương ứng của ứng dụng trước khi thực thi. Không chứa câu lệnh USE.

CREATE TABLE IF NOT EXISTS Lich_Hen (
    Ma_Lich_Hen BIGINT AUTO_INCREMENT PRIMARY KEY,
    Ma_Nguoi_Tao BIGINT NOT NULL,
    Tieu_De VARCHAR(100) NOT NULL,
    Mo_Ta VARCHAR(1000) NULL,
    Bat_Dau_Luc TIMESTAMP NOT NULL,
    Ket_Thuc_Luc TIMESTAMP NOT NULL,
    Lap_Lai ENUM('KHONG', 'HANG_NGAY', 'HANG_TUAN', 'HANG_THANG') NOT NULL DEFAULT 'KHONG',
    Ma_Phong BIGINT NULL,
    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Ngay_Xoa TIMESTAMP NULL,

    CONSTRAINT FK_LichHen_NguoiTao
        FOREIGN KEY (Ma_Nguoi_Tao)
        REFERENCES Nguoi_Dung(Ma_Nguoi_Dung)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT FK_LichHen_PhongHop
        FOREIGN KEY (Ma_Phong)
        REFERENCES Phong_Hop(Ma_Phong)
        ON UPDATE CASCADE
        ON DELETE SET NULL
) ENGINE = InnoDB;

CREATE INDEX IX_LichHen_NguoiTao_BatDau
    ON Lich_Hen (Ma_Nguoi_Tao, Bat_Dau_Luc);
