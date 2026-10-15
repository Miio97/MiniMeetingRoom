package client.UI.dialogs;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

class HostMenuPopupTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    @Test void screenStopCoexistsWithMediaAndRoomManagement() throws Exception {
        FxTestRuntime.call(() -> {
            List<String> actions = new ArrayList<>();
            var menu = new HostMenuPopup(true, true, true,
                    () -> actions.add("mic"), () -> actions.add("camera"), () -> actions.add("screen"),
                    () -> actions.add("kick"), () -> actions.add("transfer"));
            for (String label : List.of("Tắt microphone", "Tắt camera", "Dừng chia sẻ", "Chuyển chủ phòng", "Xóa thành viên")) {
                MenuItem item = menu.getItems().stream().filter(candidate -> label.equals(candidate.getText())).findFirst().orElseThrow();
                assertFalse(item.isDisable()); item.fire();
            }
            assertEquals(List.of("mic", "camera", "screen", "transfer", "kick"), actions);
            var idle = new HostMenuPopup(true, true, false, () -> { }, () -> { }, () -> { }, () -> { }, () -> { });
            assertTrue(idle.getItems().stream().filter(item -> "Dừng chia sẻ".equals(item.getText())).findFirst().orElseThrow().isDisable());
            return null;
        });
    }
}
