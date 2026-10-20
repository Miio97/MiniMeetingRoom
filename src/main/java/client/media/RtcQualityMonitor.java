package client.media;

import dev.onvoid.webrtc.*;
import java.util.*;
import static client.media.NetworkSample.*;

/** Per peer/stat/SSRC counters, sampled once. Never mixes receiving loss into sending quality. */
final class RtcQualityMonitor {
    private record Stream(Object peer,String id,Object ssrc) { }
    private record Counter(long at,long reportAt,Long bytes,Long frames,Long lost,Long received,Long dropped) { }
    private final Map<Stream,Counter> counters=new HashMap<>();
    private final Accumulator[] groups=new Accumulator[6];
    private long now;
    void reset() { counters.clear(); }
    void retainSources(List<MediaStatsSource> sources) {
        Set<Object> live=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var source:sources) live.add(source.identity());
        counters.keySet().removeIf(stream -> !live.contains(stream.peer()));
    }
    void begin(long at) {now=at;for(int i=0;i<groups.length;i++) groups[i]=new Accumulator();}
    void accept(MediaStatsSource source,RTCStatsReport report) {
        if(report==null) return;
        int index=source.kind().ordinal()*2+(source.sending()?0:1);
        Accumulator out=groups[index];
        Set<String> selected=new HashSet<>();
        for(RTCStats stat:report.getStats().values()) if(stat.getType()==RTCStatsType.TRANSPORT) {
            var v=stat.getAttributes();if(v.get("selectedCandidatePairId") instanceof String id) selected.add(id);
            String dtls=String.valueOf(v.get("dtlsState"));if(java.util.Set.of("connected","connecting","failed","closed").contains(dtls)) out.state=dtls;
        }
        for(RTCStats stat:report.getStats().values()) {
            var v=stat.getAttributes();
            if(stat.getType()==RTCStatsType.CANDIDATE_PAIR) {
                if(!selected.isEmpty()?selected.contains(stat.getId()):Boolean.TRUE.equals(v.get("nominated")) || Boolean.TRUE.equals(v.get("selected"))) {
                    out.rtt=max(out.rtt,millis(v.get("currentRoundTripTime")));
                    out.available=min(out.available,number(v.get(source.sending()?"availableOutgoingBitrate":"availableIncomingBitrate")));
                }
                continue;
            }
            if(source.transportOnly()) continue;
            Object kind=v.getOrDefault("kind",v.get("mediaType"));
            if(kind!=null && !(source.kind()==MediaStatsSource.Kind.AUDIO?"audio":"video").equals(kind)) continue;
            boolean inbound=stat.getType()==RTCStatsType.INBOUND_RTP;
            boolean outbound=stat.getType()==RTCStatsType.OUTBOUND_RTP;
            boolean remote=stat.getType()==RTCStatsType.REMOTE_INBOUND_RTP;
            if(source.sending()?!(outbound || remote):!inbound) continue;
            Stream key=new Stream(source.identity(),stat.getId(),v.get("ssrc"));
            Counter current=new Counter(now,stat.getTimestamp(),counter(v.get(source.sending()?"bytesSent":"bytesReceived")),
                    counter(v.get(source.sending()?"framesEncoded":"framesDecoded")),counter(v.get("packetsLost")),counter(v.get("packetsReceived")),counter(v.get("framesDropped")));
            if(current.frames==null) current=new Counter(now,stat.getTimestamp(),current.bytes,counter(v.get(source.sending()?"framesSent":"framesReceived")),current.lost,current.received,current.dropped);
            Counter previous=counters.put(key,current);
            boolean fresh=previous==null || current.reportAt>previous.reportAt;
            if(!fresh) { counters.put(key,previous);continue; }
            out.rtt=max(out.rtt,millis(v.get("roundTripTime")));out.jitter=max(out.jitter,millis(v.get("jitter")));
            if(previous!=null && now>previous.at) {
                double seconds=(now-previous.at)/1e9;
                out.bitrate=sum(out.bitrate,rate(current.bytes,previous.bytes,seconds,8));
                out.fps=max(out.fps,rate(current.frames,previous.frames,seconds,1));
                out.dropped=max(out.dropped,rate(current.dropped,previous.dropped,seconds,1));
                if(current.lost!=null && previous.lost!=null && current.received!=null && previous.received!=null) {
                    long lost=current.lost-previous.lost,received=current.received-previous.received;
                    if(lost>=0 && received>=0 && lost+received>0) out.loss=max(out.loss,100.0*lost/(lost+received));
                }
            }
            if(remote) {
                Double fraction=number(v.get("fractionLost"));
                if(fraction!=null && fraction<=1) out.loss=max(out.loss,fraction*100);
            }
            out.width=dimension(v.get("frameWidth"),out.width);out.height=dimension(v.get("frameHeight"),out.height);
            String reason=String.valueOf(v.get("qualityLimitationReason"));
            if("cpu".equals(reason)) out.limit=Limitation.CPU_LIMITED;
            else if("bandwidth".equals(reason) && out.limit!=Limitation.CPU_LIMITED) out.limit=Limitation.NETWORK_LIMITED;
            else if("none".equals(reason) && out.limit==Limitation.UNKNOWN) out.limit=Limitation.NONE;
        }
    }
    NetworkSample finish(int width,int height,int fps) {
        counters.values().removeIf(counter -> now-counter.at>java.util.concurrent.TimeUnit.SECONDS.toNanos(20));
        if(counters.size()>4096) counters.clear();
        return new NetworkSample(now,groups[0].value(),groups[1].value(),groups[2].value(),groups[3].value(),groups[4].value(),groups[5].value(),width,height,fps);
    }
    private static final class Accumulator {
        Double rtt,jitter,loss,bitrate,fps,available,dropped;Integer width,height;Limitation limit=Limitation.UNKNOWN;String state="unknown";
        Metrics value() {return new Metrics(rtt,jitter,loss,bitrate,fps,width,height,available,limit,dropped,state);}
    }
    private static Double rate(Long n,Long before,double seconds,int multiplier) {
        if(n==null || before==null || n<before || seconds<=0) return null;
        double value=(n-before)*((double)multiplier)/seconds;return Double.isFinite(value)?value:null;
    }
    private static Long counter(Object n) {return n instanceof Number v && Double.isFinite(v.doubleValue()) && v.doubleValue()>=0?v.longValue():null;}
    private static Double number(Object n) {return n instanceof Number v && Double.isFinite(v.doubleValue()) && v.doubleValue()>=0?v.doubleValue():null;}
    private static Double millis(Object n) {Double value=number(n);return value==null?null:value*1000;}
    private static Integer dimension(Object n,Integer previous) {Double value=number(n);if(value!=null && value>0 && value<=7680)return value.intValue();return previous;}
    private static Double max(Double a,Double b) {if(a==null)return b;if(b==null)return a;return Math.max(a,b);}
    private static Double min(Double a,Double b) {if(a==null)return b;if(b==null)return a;return Math.min(a,b);}
    private static Double sum(Double a,Double b) {if(a==null)return b;if(b==null)return a;return a+b;}
}
