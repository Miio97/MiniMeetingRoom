package common.signaling;

import java.io.IOException;

public final class SignalException extends IOException {
    private final String code;
    public SignalException(String code) {
        super(code != null && code.matches("[A-Z_]{2,48}") ? code : "SIGNALING_ERROR");
        this.code = getMessage();
    }
    public String code() { return code; }
}
