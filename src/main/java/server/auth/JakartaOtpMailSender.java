package server.auth;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Gmail STARTTLS delivery. Configuration and credentials stay exclusively on the server. */
public final class JakartaOtpMailSender implements OtpMailSender {
    @Override
    public void send(String email, String purpose, String code) throws MailDeliveryException {
        try {
            Properties configuration = new Properties();
            Path file = Path.of(setting("MMR_MAIL_CONFIG", "config/mail.private.properties"));
            if (Files.isRegularFile(file)) {
                try (InputStream input = Files.newInputStream(file)) {
                    configuration.load(input);
                }
            }
            String host = value(configuration, "mail.smtp.host", "MMR_SMTP_HOST", "smtp.gmail.com");
            String port = value(configuration, "mail.smtp.port", "MMR_SMTP_PORT", "587");
            String user = value(configuration, "mail.smtp.user", "MMR_SMTP_USER", "");
            String password = value(configuration, "mail.smtp.password", "MMR_SMTP_PASSWORD", "");
            String from = value(configuration, "mail.from", "MMR_SMTP_FROM", user);
            if (user.isBlank() || password.isBlank() || from.isBlank())
                throw new MailDeliveryException();
            int numericPort = Integer.parseInt(port);
            if (numericPort < 1 || numericPort > 65535) throw new MailDeliveryException();
            Properties smtp = new Properties();
            smtp.setProperty("mail.smtp.host", host);
            smtp.setProperty("mail.smtp.port", port);
            smtp.setProperty("mail.smtp.auth", "true");
            smtp.setProperty("mail.smtp.starttls.enable", "true");
            smtp.setProperty("mail.smtp.starttls.required", "true");
            smtp.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            smtp.setProperty("mail.smtp.connectiontimeout", "10000");
            smtp.setProperty("mail.smtp.timeout", "15000");
            smtp.setProperty("mail.smtp.writetimeout", "15000");
            Session session = Session.getInstance(smtp);
            session.setDebug(false);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from, true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(email, true));
            String subject =
                    switch (purpose) {
                        case "REGISTER" -> "Mini Meeting Room - Xác thực đăng ký";
                        case "VERIFY" -> "Mini Meeting Room - Xác thực email tài khoản";
                        default -> "Mini Meeting Room - Đặt lại mật khẩu";
                    };
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setText(
                    "Mã xác thực của bạn: "
                            + code
                            + "\n\n"
                            + "Mã có hiệu lực trong 5 phút và chỉ dùng một lần. Không chia sẻ mã"
                            + " cho bất kỳ ai.\n"
                            + "Nếu bạn không yêu cầu, hãy bỏ qua email này.",
                    StandardCharsets.UTF_8.name());
            try (Transport transport = session.getTransport("smtp")) {
                transport.connect(host, numericPort, user, password);
                transport.sendMessage(message, message.getAllRecipients());
            }
        } catch (Exception failure) {
            // Provider exceptions can contain SMTP account names or message content.
            throw new MailDeliveryException();
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String value(Properties properties, String key, String env, String fallback) {
        return setting(env, properties.getProperty(key, fallback)).strip();
    }
}
