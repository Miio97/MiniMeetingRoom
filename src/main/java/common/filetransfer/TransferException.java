package common.filetransfer;

import java.io.IOException;

/** Safe, user-visible protocol failure. Never include credentials or storage paths. */
public final class TransferException extends IOException {
    private final String code;

    public TransferException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
