package server.auth;

/** Server-only delivery boundary. Never log the code or place it in API responses. */
@FunctionalInterface
public interface OtpMailSender {
    void send(String email, String purpose, String code) throws MailDeliveryException;

    final class MailDeliveryException extends Exception {
        public MailDeliveryException() {
            super("Email delivery is unavailable.");
        }
    }
}
