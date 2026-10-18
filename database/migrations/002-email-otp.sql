-- Run on the current MiniMeetingRoom MySQL database before enabling email OTP.
-- Existing Nguoi_Dung / Phien_Dang_Nhap tables and existing accounts are untouched.
-- All DATETIME values below are UTC, independent of the MySQL session timezone.
CREATE TABLE IF NOT EXISTS Auth_Email_OTP (
    -- Exact mailbox identity: existing account tables may use accent-insensitive collation.
    Email VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    Purpose VARCHAR(20) NOT NULL,
    Code_Hash VARCHAR(255) NULL,
    Expires_At DATETIME(6) NULL,
    Attempts INT NOT NULL DEFAULT 0,
    Consumed BOOLEAN NOT NULL DEFAULT TRUE,
    Last_Sent_At DATETIME(6) NULL,
    Pending_Username VARCHAR(50) NULL,
    Pending_Display_Name VARCHAR(100) NULL,
    Pending_Password_Hash VARCHAR(255) NULL,
    Registration_Challenge_Hash VARCHAR(64) NULL,
    Account_Id BIGINT NULL,
    PRIMARY KEY (Email, Purpose)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS Auth_Email_OTP_Send (
    Send_Id BIGINT AUTO_INCREMENT PRIMARY KEY,
    Email VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    Purpose VARCHAR(20) NOT NULL,
    Sent_At DATETIME(6) NOT NULL,
    INDEX ix_otp_send_window (Email, Purpose, Sent_At)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
