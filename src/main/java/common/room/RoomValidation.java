package common.room;

import java.util.Locale;

public final class RoomValidation {
    public static final int MIN_CAPACITY = 2;
    public static final int MAX_CAPACITY = 250;
    public static final int DEFAULT_CAPACITY = 10;

    private RoomValidation() { }

    public static String nameError(String name) {
        return name == null || name.isBlank() || name.strip().length() > 100
                ? "Tên phòng phải có 1–100 ký tự." : null;
    }

    public static String pinError(String pin) {
        return pin == null || !pin.matches("[0-9]{4,6}") ? "PIN phòng phải gồm 4–6 chữ số." : null;
    }

    public static String identifierError(String identifier) {
        if (identifier == null || identifier.isBlank()) return "Vui lòng nhập ID hoặc mã phòng.";
        String value = identifier.strip();
        if (value.matches("[0-9]{1,19}")) {
            try { if (Long.parseLong(value) > 0) return null; }
            catch (NumberFormatException ignored) { }
        } else if (value.toUpperCase(Locale.ROOT).matches("MMR-[A-Z0-9]{4,16}")) return null;
        return "Nhập ID phòng là số dương hoặc mã dạng MMR-XXXXXXXX.";
    }

    public static String capacityError(int capacity) {
        return capacity < MIN_CAPACITY || capacity > MAX_CAPACITY
                ? "Số người tối đa phải từ " + MIN_CAPACITY + " đến " + MAX_CAPACITY + "." : null;
    }
}
