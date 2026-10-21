package client.UI;

import client.UI.components.ChatPanel;
import client.filetransfer.FileTransferClient;
import client.meeting.MeetingSession;
import client.signalingapi.SignalingClient;
import common.signaling.*;
import com.google.gson.Gson;
import java.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import server.auth.SessionValidator;
import server.meeting.*;
import server.signalingapi.*;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

class OptimisticChatUiTest {
    @TempDir Path temporary;
    @Test void pendingAppearsBeforeServerCommitAndAckPushReconcileOneRow() throws Exception {
        FxTestRuntime.start();var tls=RoomTestFixture.certificates(temporary);var json=new Gson();
        try(var fixture=new RoomTestFixture(tls)) {
            String schema=Files.readString(Path.of("database/migrations/001-meeting-runtime.sql")).replaceAll("(?m)^--[^\\r\\n]*","").replace("CHARACTER SET ascii COLLATE ascii_bin","").replace("ENGINE=InnoDB","");
            for(String sql:schema.split(";")) if(!sql.isBlank()) fixture.execute(sql);
            var current=new AtomicReference<SignalingServer>();
            try(var rooms=new RoomService(new RoomRepository(fixture::connection),Clock.systemUTC(),"chat-ui",new byte[32],20,event -> {
                var server=current.get();if(server!=null) server.event(event.recipientConnectionId(),event.type(),event.roomId(),event.revision(),json.toJsonTree(event.body()).getAsJsonObject());
            });var handler=new MeetingHandler(rooms)) {
                rooms.start();var gate=new CompletableFuture<Void>();var arrived=new CountDownLatch(1);
                var delayed=new SignalingServer.Handler() {
                    public void bindServer(SignalingServer value) {handler.bindServer(value);}
                    public SignalMessage handle(SignalingServer.Connection c,SignalMessage r) throws Exception {return handler.handle(c,r);}
                    public CompletionStage<SignalMessage> handleAsync(SignalingServer.Connection c,SignalMessage r) throws Exception {
                        if(!r.type().equals("CHAT_SEND")) return handler.handleAsync(c,r);
                        arrived.countDown();return gate.thenCompose(v -> {try {return handler.handleAsync(c,r);} catch(Exception failure) {return CompletableFuture.failedFuture(failure);}});
                    }
                    public void disconnected(SignalingServer.Connection c,String reason) {handler.disconnected(c,reason);}
                };
                try(var server=new SignalingServer(tls.server(),new InetSocketAddress("127.0.0.1",0),new SessionValidator(fixture::connection,Clock.systemUTC()),delayed,rooms.serverEpoch(),SignalingServer.Options.defaults());var files=new FileTransferClient()) {
                    current.set(server);server.start();var account=fixture.account("optimisticUi","Host");
                    var stage=FxTestRuntime.call(Stage::new);
                    var manager=FxTestRuntime.call(() -> new SceneManager(stage,fixture.client(),files,(token,expiry) -> new MeetingSession(token,expiry,
                        listener -> new SignalingClient(new SignalingClient.Settings("localhost",server.port(),null,SignalWire.DEFAULT_MAX_BYTES),tls.client(),listener))));
                    ChatPanel panel=null;
                    try {
                        var joined=new CompletableFuture<Void>();
                        FxTestRuntime.call(() -> {manager.acceptSession(account);manager.requestCreateRoom("Chat UI","1234",3,false,room -> joined.complete(null),joined::completeExceptionally);return null;});
                        joined.get(10,TimeUnit.SECONDS);
                        panel=FxTestRuntime.call(() -> {var view=new ChatPanel(manager);stage.setScene(new Scene(view,600,500));view.applyCss();view.layout();return view;});
                        final ChatPanel view=panel;
                        long elapsed=FxTestRuntime.call(() -> {
                            var input=(TextField)view.lookup("#chat-input");input.setText("Visible before save");long before=System.nanoTime();((Button)view.lookup("#chat-send")).fire();
                            assertEquals("",input.getText());assertEquals(1,((ListView<?>)view.lookup("#chat-messages")).getItems().size());
                            assertTrue(((ListView<?>)view.lookup("#chat-messages")).getItems().toString().contains("Đang gửi"));
                            input.setText("Draft kept while sending");return System.nanoTime()-before;
                        });
                        System.out.println("CHAT_UI_OPTIMISTIC submitMs="+elapsed/1e6+", serverCommitHeld=true");
                        assertTrue(arrived.await(5,TimeUnit.SECONDS));assertEquals(0,fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
                        gate.complete(null);
                        FxTestRuntime.await(() -> !((ListView<?>)view.lookup("#chat-messages")).getItems().toString().contains("Đang gửi"));
                        FxTestRuntime.call(() -> {assertEquals(1,((ListView<?>)view.lookup("#chat-messages")).getItems().size());assertEquals("Draft kept while sending",((TextField)view.lookup("#chat-input")).getText());return null;});
                        assertEquals(1,fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
                    } finally {
                        gate.complete(null);final ChatPanel view=panel;
                        FxTestRuntime.call(() -> {if(view!=null) view.dispose();manager.shutdown();stage.close();return null;});
                    }
                }
            }
        }
    }
}
