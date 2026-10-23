package client.UI;

import client.auth.AuthClient;
import client.auth.DeviceSessionStore;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.room.RoomInfo;
import javafx.geometry.Rectangle2D;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

class WindowPlacementTest {
    @BeforeAll static void runtime() throws Exception {FxTestRuntime.start();}

    @Test void boundsFitSmallScaledAndSecondaryDisplaysAndAreExactlyCentered() {
        for(var area:new Rectangle2D[]{new Rectangle2D(0,0,1920,1040),new Rectangle2D(0,0,1280,680),
                new Rectangle2D(-1280,40,1024,728),new Rectangle2D(1536,0,1280,720)}) {
            for(int[] size:new int[][]{{960,600},{1100,700},{1280,800}}) {
                var b=WindowPlacement.centered(area,size[0],size[1]);
                assertTrue(area.contains(b));
                assertEquals(area.getMinX()+area.getWidth()/2,b.getMinX()+b.getWidth()/2,.01);
                assertEquals(area.getMinY()+area.getHeight()/2,b.getMinY()+b.getHeight()/2,.01);
            }
        }
    }

    @Test void shownAuthLobbyWaitingAndMeetingUseStableWindowPlacement() throws Exception {
        Stage[] stage={null};SceneManager[] manager={null};
        try {
            FxTestRuntime.call(() -> {
                stage[0]=new Stage();
                var tls=javax.net.ssl.SSLContext.getDefault();
                manager[0]=new SceneManager(stage[0],new AuthClient("localhost",1,tls,100),
                        new FileTransferClient("localhost",1,tls,100),null,DeviceSessionStore.noop());
                manager[0].auth();stage[0].show();return null;
            });
            assertCentered(stage[0]);
            snapshot(stage[0],"auth");
            FxTestRuntime.call(() -> {
                manager[0].acceptSession(AuthResponse.ok("Test",new AuthUser(1,"window-test","Window Test","test@example.test"),
                        "local-window-test-token",java.time.Instant.now().plusSeconds(3600).toString()));
                manager[0].cacheRecentRooms(java.util.List.of());manager[0].lobby();return null;
            });
            assertCentered(stage[0]);
            snapshot(stage[0],"lobby");
            double[] original=FxTestRuntime.call(() -> new double[]{stage[0].getWidth(),stage[0].getHeight()});
            for(int i=0;i<4;i++) {
                FxTestRuntime.call(() -> {manager[0].lobby();return null;});
                assertCentered(stage[0]);
                FxTestRuntime.call(() -> {assertEquals(original[0],stage[0].getWidth(),2);assertEquals(original[1],stage[0].getHeight(),2);return null;});
            }
            var room=new RoomInfo(1,"Window test","MMR-TEST",1,java.time.Instant.now().toString(),"DANG_HOAT_DONG",false,10,1,java.util.List.of());
            FxTestRuntime.call(() -> {manager[0].preJoin(room);return null;});
            assertMaximized(stage[0]);snapshot(stage[0],"prejoin");
            FxTestRuntime.call(() -> {stage[0].setMaximized(false);return null;});
            FxTestRuntime.await(() -> !stage[0].isMaximized());
            FxTestRuntime.call(() -> {manager[0].meeting();return null;});
            assertMaximized(stage[0]);snapshot(stage[0],"meeting");
            FxTestRuntime.call(() -> {manager[0].lobby();return null;});
            FxTestRuntime.await(() -> !stage[0].isMaximized());
            assertCentered(stage[0]);
            FxTestRuntime.call(() -> {assertEquals(original[0],stage[0].getWidth(),2);assertEquals(original[1],stage[0].getHeight(),2);return null;});
        } finally {
            FxTestRuntime.call(() -> {if(manager[0]!=null) manager[0].shutdown();if(stage[0]!=null) stage[0].close();return null;});
        }
    }

    private static void assertCentered(Stage stage) throws Exception {
        FxTestRuntime.await(() -> {
            var area=WindowPlacement.visualArea(stage);
            return Math.abs(stage.getX()+stage.getWidth()/2-(area.getMinX()+area.getWidth()/2))<3
                    && Math.abs(stage.getY()+stage.getHeight()/2-(area.getMinY()+area.getHeight()/2))<3
                    && stage.getWidth()<=area.getWidth()+1 && stage.getHeight()<=area.getHeight()+1;
        });
    }

    private static void assertMaximized(Stage stage) throws Exception {
        try {FxTestRuntime.await(() -> {
            var area=WindowPlacement.visualArea(stage);
            return stage.isMaximized() && stage.getWidth()>=area.getWidth()-24 && stage.getHeight()>=area.getHeight()-24;
        });} catch(AssertionError failure) {
            snapshot(stage,"failed-maximize");
            throw new AssertionError(FxTestRuntime.call(() -> "maximized="+stage.isMaximized()+", width="+stage.getWidth()
                    +", height="+stage.getHeight()+", area="+WindowPlacement.visualArea(stage)),failure);
        }
    }

    private static void snapshot(Stage stage,String name) throws Exception {
        FxTestRuntime.call(() -> {
            var root=stage.getScene().getRoot();root.setOpacity(1);root.applyCss();root.layout();
            var image=root.snapshot(null,null);
            int w=(int)image.getWidth(),h=(int)image.getHeight();
            var png=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var pixels=((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData();
            image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,w);
            var folder=java.nio.file.Path.of("target/qa/window-placement");java.nio.file.Files.createDirectories(folder);
            javax.imageio.ImageIO.write(png,"png",folder.resolve(name+".png").toFile());
            java.nio.file.Files.writeString(folder.resolve(name+".txt"),"x="+stage.getX()+", y="+stage.getY()
                    +", width="+stage.getWidth()+", height="+stage.getHeight()+", maximized="+stage.isMaximized()
                    +", visualArea="+WindowPlacement.visualArea(stage));
            return null;
        });
    }
}
