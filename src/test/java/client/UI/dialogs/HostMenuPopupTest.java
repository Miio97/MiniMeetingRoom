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
    @Test void pendingDisablesEveryActionAndRestoresUnavailableMediaStates() throws Exception {
        FxTestRuntime.call(() -> {
            var pending = new javafx.beans.property.SimpleBooleanProperty(false);
            var menu = new HostMenuPopup(false, true, false, () -> { }, () -> { }, () -> { }, () -> { }, () -> { });
            menu.bindPending(pending); pending.set(true);
            assertTrue(menu.getItems().stream().allMatch(MenuItem::isDisable));
            pending.set(false);
            for (MenuItem item : menu.getItems()) {
                if (item instanceof javafx.scene.control.SeparatorMenuItem) continue;
                assertEquals(List.of("Tắt microphone", "Dừng chia sẻ").contains(item.getText()), item.isDisable());
            }
            pending.set(true); menu.mediaState(true, false, true);
            assertTrue(menu.getItems().stream().allMatch(MenuItem::isDisable));
            pending.set(false);
            for (MenuItem item : menu.getItems()) {
                if (item instanceof javafx.scene.control.SeparatorMenuItem) continue;
                assertEquals("Tắt camera".equals(item.getText()), item.isDisable());
            }
            return null;
        });
    }
}
