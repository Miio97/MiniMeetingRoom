package client.media;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VideoViewportTest {
    @Test void everyParticipantRemainsReachableAcrossPagesWhenPinnedOrSharing() {
        for(int pinned:new int[]{-1,0,19,49}) for(boolean screen:new boolean[]{false,true}) {
            var seen=new HashSet<Integer>();int pages=VideoViewport.page(50,pinned,screen,0).pages();
            for(int index=0;index<pages;index++) {
                var page=VideoViewport.page(50,pinned,screen,index);seen.addAll(page.cameras());if(page.primary()>=0) seen.add(page.primary());
                var unique=new HashSet<>(page.cameras());if(page.primary()>=0) unique.add(page.primary());assertTrue(unique.size()<=(screen?5:6));
                if(pinned>=0 && !screen) assertEquals(pinned,page.primary());
            }
            assertEquals(50,seen.size());
        }
        assertEquals(-1,VideoViewport.page(0,-1,false,99).primary());
    }
}
