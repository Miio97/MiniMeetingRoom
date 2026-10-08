package client.UI.components;

import client.UI.mock.MockData;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class ChatPanel extends BorderPane {
    private final VBox messages = new VBox(18);
    private final ScrollPane scroll = Ui.scroll(messages);
    public ChatPanel(String user) {
        messages.setPadding(new Insets(16)); setCenter(scroll);
        MockData.messages().forEach(this::append);
        TextField input = new TextField(); input.setPromptText("Viết tin nhắn..."); HBox.setHgrow(input, Priority.ALWAYS);
        IconButton send = new IconButton("send", "Gửi tin nhắn"); send.getStyleClass().add("primary");
        Runnable submit = () -> { if (!input.getText().isBlank()) { append(new MockData.Message(user, input.getText().strip(), LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), true)); input.clear(); } };
        send.setOnAction(e -> submit.run()); input.setOnAction(e -> submit.run());
        HBox bottom = Ui.row(8, input, send); bottom.setPadding(new Insets(12)); setBottom(bottom);
    }
    private void append(MockData.Message msg) {
        Label bubble = Ui.label(msg.text(), "bubble"); bubble.getStyleClass().add(msg.mine() ? "bubble-mine" : "bubble-other"); bubble.setMaxWidth(248);
        VBox entry = Ui.column(6, Ui.label((msg.mine() ? "Bạn" : msg.name()) + "  ·  " + msg.time(), "caption"), bubble); entry.setAlignment(msg.mine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messages.getChildren().add(entry);
        Platform.runLater(() -> { scroll.applyCss(); scroll.layout(); scroll.setVvalue(1); });
    }
}
