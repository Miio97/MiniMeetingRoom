-- Database đã có bảng Lich_Hen với hai cột lựa chọn cũ.
-- Dùng client/server bản mới cùng migration này. Không thay đổi thời điểm của lịch hiện có.
-- Database tạo mới bằng schema.sql hoặc 002 hiện tại không cần chạy migration này.
ALTER TABLE Lich_Hen
    DROP COLUMN Ca_Ngay,
    DROP COLUMN Mui_Gio;
