package client.media;

import static client.media.NetworkSample.*;
final class MediaStatsSamples {
    static Metrics metrics(double rtt,double jitter,double loss) {return new Metrics(rtt,jitter,loss,100000.0,30.0,640,360,4000000.0,Limitation.NONE);}
    static final Metrics GOOD=metrics(40,5,0), BAD=metrics(700,70,8);
    static NetworkSample sample(long at,Metrics up,Metrics down,Metrics voice) {return new NetworkSample(at,up,down,voice,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN,1280,720,30);}
    static NetworkSample evidence(long at,AdaptiveVideoPolicy.Evidence video,AdaptiveVideoPolicy.Evidence voice) {
        return sample(at,new Metrics(video.rttMs(),video.jitterMs(),video.lossPct(),null,null,null,null,4000000.0,Limitation.NONE),Metrics.UNKNOWN,
                new Metrics(voice.rttMs(),voice.jitterMs(),voice.lossPct(),null,null,null,null,null,Limitation.NONE));
    }
}
