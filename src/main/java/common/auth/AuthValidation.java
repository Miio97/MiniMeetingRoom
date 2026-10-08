package common.auth;

/** Shared form rules; the server enforces them even when a client bypasses its UI. */
public final class AuthValidation {
    public static final String PASSWORD_REQUIREMENTS =
            "Mật khẩu cần 8–128 ký tự, gồm chữ thường, chữ hoa, chữ số và ký tự đặc biệt.";

    private AuthValidation() { }

    public static String registrationUsernameError(String username) {
        String error = loginUsernameError(username);
        if (error != null) return error;
        if (username.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            return "Tên đăng nhập không được chứa khoảng trắng.";
        }
        return null;
    }

    public static String loginUsernameError(String username) {
        return username == null || username.isBlank() || username.length() > 50
                ? "Tên đăng nhập phải có 1–50 ký tự." : null;
    }

    public static String registrationPasswordError(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            return PASSWORD_REQUIREMENTS;
        }
        boolean lower = false, upper = false, digit = false, special = false;
        for (int character : password.codePoints().toArray()) {
            lower |= Character.isLowerCase(character);
            upper |= Character.isUpperCase(character);
            digit |= Character.isDigit(character);
            special |= isSpecial(character);
        }
        return lower && upper && digit && special ? null : PASSWORD_REQUIREMENTS;
    }

    /** Existing accounts can still log in with their original password. */
    public static String loginPasswordError(String password) {
        return password == null || password.isEmpty() || password.length() > 128
                ? "Vui lòng nhập mật khẩu (tối đa 128 ký tự)." : null;
    }

    private static boolean isSpecial(int character) {
        return switch (Character.getType(character)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }
}
