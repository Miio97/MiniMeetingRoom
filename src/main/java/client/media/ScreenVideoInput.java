package client.media;

import dev.onvoid.webrtc.media.video.*;
import dev.onvoid.webrtc.media.video.desktop.*;
import java.util.*;

/** SDK 0.19.0 desktop capture; source identifiers and titles stay local to the picker. */
public final class ScreenVideoInput implements VideoInput {
    public record Choice(String title, long id, boolean window) {
        @Override public String toString() { return title; }
    }
    public record Settings(int width, int height, int fps, int bitrate) {
        public static Settings configured() {
            return new Settings(number("width",1280,320,1280),number("height",720,240,720),
                    number("fps",10,1,15),number("bitrate",1800000,200000,5000000));
        }
        private static int number(String key,int fallback,int min,int max) {
            int value=Integer.getInteger("mmr.screen."+key,fallback);return Math.max(min,Math.min(max,value));
        }
    }
    private final VideoDesktopSource source=new VideoDesktopSource();
    private final Choice choice;
    private boolean closed;
    public ScreenVideoInput(Choice choice) {
        this.choice=Objects.requireNonNull(choice);
        try {
            Settings settings=Settings.configured();source.setSourceId(choice.id(),choice.window());
            source.setMaxFrameSize(settings.width(),settings.height());source.setFrameRate(settings.fps());
        } catch(RuntimeException failure) {source.dispose();throw new IllegalStateException("SCREEN_CAPTURE_FAILED");}
    }
    public static List<Choice> choices() {
        List<Choice> result=new ArrayList<>();
        for(boolean window:new boolean[]{false,true}) {
            DesktopCapturer capturer=window ? new WindowCapturer() : new ScreenCapturer();
            try { for(DesktopSource source:capturer.getDesktopSources()) result.add(new Choice(source.title,source.id,window)); }
            finally {capturer.dispose();}
        }
        return List.copyOf(result);
    }
    public boolean available() {return choices().stream().anyMatch(source -> source.id()==choice.id() && source.window()==choice.window());}
    public VideoTrackSource source() {return source;}
    public synchronized void start() {if(closed) throw new IllegalStateException("SCREEN_CAPTURE_FAILED");source.start();}
    public synchronized void close() {if(!closed) {closed=true;try {source.stop();} finally {source.dispose();}}}
}
