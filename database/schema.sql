-- =========================================================
-- DATABASE: MINI MEETING ROOM
-- =========================================================

CREATE DATABASE IF NOT EXISTS Mini_Meeting_Room
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE Mini_Meeting_Room;


-- =========================================================
-- 1. BANG NGUOI_DUNG
-- Luu thong tin tai khoan nguoi dung
-- =========================================================

CREATE TABLE Nguoi_Dung (
    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,

    Ten_Dang_Nhap VARCHAR(50) NOT NULL,
    Mat_Khau_Bam VARCHAR(255) NOT NULL,
    Ten_Hien_Thi VARCHAR(100) NOT NULL,
    Email VARCHAR(255),

    Trang_Thai ENUM(
        'HOAT_DONG',
        'VO_HIEU_HOA',
        'BI_KHOA'
    ) NOT NULL DEFAULT 'HOAT_DONG',

    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    Ngay_Cap_Nhat TIMESTAMP NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    Lan_Dang_Nhap_Cuoi TIMESTAMP NULL,

    CONSTRAINT UQ_NguoiDung_TenDangNhap
        UNIQUE (Ten_Dang_Nhap),

    CONSTRAINT UQ_NguoiDung_Email
        UNIQUE (Email)
) ENGINE = InnoDB;


-- =========================================================
-- 2. BANG PHIEN_DANG_NHAP
-- Luu cac phien dang nhap cua nguoi dung
-- =========================================================

CREATE TABLE Phien_Dang_Nhap (
    Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY,

    Ma_Nguoi_Dung BIGINT NOT NULL,

    Token_Bam VARCHAR(255) NOT NULL,

    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    Het_Han_Luc TIMESTAMP NOT NULL,

    Thu_Hoi_Luc TIMESTAMP NULL,

    Thong_Tin_Client VARCHAR(255),

    CONSTRAINT UQ_PhienDangNhap_Token
        UNIQUE (Token_Bam),

    CONSTRAINT FK_PhienDangNhap_NguoiDung
        FOREIGN KEY (Ma_Nguoi_Dung)
        REFERENCES Nguoi_Dung(Ma_Nguoi_Dung)
        ON UPDATE CASCADE
        ON DELETE CASCADE
) ENGINE = InnoDB;


-- =========================================================
-- 3. BANG PHONG_HOP
-- Luu thong tin phong hop
-- =========================================================

CREATE TABLE Phong_Hop (
    Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,

    Ma_Phong_Tham_Gia VARCHAR(20) NOT NULL,

    Ten_Phong VARCHAR(100) NOT NULL,

    Ma_PIN_Bam VARCHAR(255),

    Ma_Chu_Phong BIGINT NOT NULL,

    Trang_Thai ENUM(
        'CHO',
        'DANG_HOAT_DONG',
        'DA_DONG'
    ) NOT NULL DEFAULT 'CHO',

    Da_Khoa BOOLEAN NOT NULL DEFAULT FALSE,

    So_Nguoi_Toi_Da SMALLINT NOT NULL DEFAULT 10,

    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    Ngay_Dong TIMESTAMP NULL,

    CONSTRAINT UQ_PhongHop_MaThamGia
        UNIQUE (Ma_Phong_Tham_Gia),

    CONSTRAINT FK_PhongHop_ChuPhong
        FOREIGN KEY (Ma_Chu_Phong)
        REFERENCES Nguoi_Dung(Ma_Nguoi_Dung)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    CONSTRAINT CK_PhongHop_SoNguoi
        CHECK (So_Nguoi_Toi_Da > 0)
) ENGINE = InnoDB;


-- =========================================================
-- 4. BANG THANH_VIEN_PHONG
-- Luu lich su nguoi dung tham gia / roi phong
-- =========================================================

CREATE TABLE Thanh_Vien_Phong (
    Ma_Thanh_Vien_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,

    Ma_Phong BIGINT NOT NULL,

    Ma_Nguoi_Dung BIGINT NOT NULL,

    Vai_Tro ENUM(
        'CHU_PHONG',
        'THANH_VIEN'
    ) NOT NULL DEFAULT 'THANH_VIEN',

    Tham_Gia_Luc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    Roi_Phong_Luc TIMESTAMP NULL,

    Ly_Do_Roi ENUM(
        'TU_ROI',
        'BI_MOI_RA',
        'PHONG_DONG',
        'MAT_KET_NOI'
    ) NULL,

    CONSTRAINT FK_ThanhVienPhong_Phong
        FOREIGN KEY (Ma_Phong)
        REFERENCES Phong_Hop(Ma_Phong)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT FK_ThanhVienPhong_NguoiDung
        FOREIGN KEY (Ma_Nguoi_Dung)
        REFERENCES Nguoi_Dung(Ma_Nguoi_Dung)
        ON UPDATE CASCADE
        ON DELETE RESTRICT
) ENGINE = InnoDB;


-- =========================================================
-- 5. BANG TIN_NHAN
-- Luu lich su chat trong phong
-- =========================================================

CREATE TABLE Tin_Nhan (
    Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY,

    Ma_Phong BIGINT NOT NULL,

    Ma_Nguoi_Gui BIGINT NOT NULL,

    Loai_Tin_Nhan ENUM(
        'VAN_BAN',
        'TEP',
        'HE_THONG'
    ) NOT NULL DEFAULT 'VAN_BAN',

    Noi_Dung TEXT,

    Ngay_Gui TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    Ngay_Chinh_Sua TIMESTAMP NULL,

    Ngay_Xoa TIMESTAMP NULL,

    CONSTRAINT FK_TinNhan_Phong
        FOREIGN KEY (Ma_Phong)
        REFERENCES Phong_Hop(Ma_Phong)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT FK_TinNhan_NguoiGui
        FOREIGN KEY (Ma_Nguoi_Gui)
        REFERENCES Nguoi_Dung(Ma_Nguoi_Dung)
        ON UPDATE CASCADE
        ON DELETE RESTRICT
) ENGINE = InnoDB;


-- =========================================================
-- 6. BANG TEP_DINH_KEM
-- Luu thong tin tep duoc gui trong phong
-- Chi luu metadata, khong luu file truc tiep trong DB
-- =========================================================

CREATE TABLE Tep_Dinh_Kem (
    Ma_Tep BIGINT AUTO_INCREMENT PRIMARY KEY,

    Ma_Tin_Nhan BIGINT NOT NULL,

    Ten_Tep_Goc VARCHAR(255) NOT NULL,

    Duong_Dan_Luu_Tru VARCHAR(500) NOT NULL,

    Loai_MIME VARCHAR(100),

    Kich_Thuoc_Byte BIGINT NOT NULL,

    Ma_Kiem_Tra VARCHAR(128),

    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT FK_TepDinhKem_TinNhan
        FOREIGN KEY (Ma_Tin_Nhan)
        REFERENCES Tin_Nhan(Ma_Tin_Nhan)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT CK_TepDinhKem_KichThuoc
        CHECK (Kich_Thuoc_Byte >= 0)
) ENGINE = InnoDB;


-- =========================================================
-- 7. BANG THONG_KE_KET_NOI
-- Luu cac thong so chat luong mang cua nguoi dung
-- =========================================================

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