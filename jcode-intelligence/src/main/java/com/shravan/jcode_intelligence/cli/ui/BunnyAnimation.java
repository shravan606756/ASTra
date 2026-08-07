package com.shravan.jcode_intelligence.cli.ui;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class BunnyAnimation {
    private final BunnyState state;
    private final List<String[]> frames;
    private final int frameDelayMs;
    private final boolean loop;

    public BunnyAnimation(BunnyState state, List<String[]> frames, int frameDelayMs, boolean loop) {
        this.state = state;
        this.frames = frames;
        this.frameDelayMs = frameDelayMs;
        this.loop = loop;
    }

    public BunnyState getState() {
        return state;
    }

    public List<String[]> getFrames() {
        return frames;
    }

    public int getFrameDelayMs() {
        return frameDelayMs;
    }

    public boolean isLoop() {
        return loop;
    }

    private static final Map<BunnyState, BunnyAnimation> ANIMATIONS = new EnumMap<>(BunnyState.class);

    static {
        ANIMATIONS.put(BunnyState.WELCOME, new BunnyAnimation(
                BunnyState.WELCOME,
                List.of(
                        new String[]{" (\\_/)", " (-.-)", " / >_"}, // sleeping
                        new String[]{" (\\_/)", " (o.o)", " / >_"}, // eyes open
                        new String[]{" (\\_/)", " (<.<)", " / >_"}, // look left
                        new String[]{" (\\_/)", " (>.>)", " / >_"}, // look right
                        new String[]{" (\\_/)", " (^.^)", " / >_"}, // smiles
                        new String[]{" (\\_/)", " (o.o)", " / >_"}  // idle
                ),
                120, false
        ));

        ANIMATIONS.put(BunnyState.IDLE, new BunnyAnimation(
                BunnyState.IDLE,
                List.of(
                        new String[]{" (\\_/)", " (o.o)", " / >_"},
                        new String[]{" (\\_/)", " (-.-)", " / >_"},
                        new String[]{" (\\_/)", " (o.o)", " / >_"},
                        new String[]{" (\\_/)", " (o.o)", " / >_"},
                        new String[]{" (\\_/)", " (o.o)", " / >_"},
                        new String[]{" (\\_/)", " (o.o)", " / >_"}
                ),
                800, true
        ));

        ANIMATIONS.put(BunnyState.THINKING, new BunnyAnimation(
                BunnyState.THINKING,
                List.of(
                        new String[]{" (\\_/)", " (o.o)", " / >_"},
                        new String[]{" (\\_/)", " (-.-)", " / >_"},
                        new String[]{"  (\\_/)", " /(o.o)", "  >_< "}, // head tilt
                        new String[]{" (\\_/)", " (o.o)", " / >_"}
                ),
                300, true
        ));

        ANIMATIONS.put(BunnyState.READING, new BunnyAnimation(
                BunnyState.READING,
                List.of(
                        new String[]{" (\\_/)", " (<.<)", " / >_"},
                        new String[]{" (\\_/)", " (o.o)", " / >_"},
                        new String[]{" (\\_/)", " (>.>)", " / >_"},
                        new String[]{" (\\_/)", " (-.-)", " / >_"}
                ),
                120, true
        ));

        ANIMATIONS.put(BunnyState.SEARCHING, new BunnyAnimation(
                BunnyState.SEARCHING,
                List.of(
                        new String[]{" (\\_/)", " (<.<)", " / >_"},
                        new String[]{" (\\_/)", " (o.o)", " / >_"},
                        new String[]{" (\\_/)", " (>.>)", " / >_"},
                        new String[]{" (\\_/)", " (o.o)", " / >_"}
                ),
                120, true
        ));

        ANIMATIONS.put(BunnyState.DIGGING, new BunnyAnimation(
                BunnyState.DIGGING,
                List.of(
                        new String[]{" (\\_/)", " (•.•)", " / >_"},
                        new String[]{" (\\_/)", " (•.•)", "_/ >\\"},
                        new String[]{" (\\_/)", " (•.•)", "\\_ >/"},
                        new String[]{" (\\_/)", " (•.•)", " / >_"}
                ),
                120, true
        ));

        ANIMATIONS.put(BunnyState.SUCCESS, new BunnyAnimation(
                BunnyState.SUCCESS,
                List.of(
                        new String[]{" (\\_/)", " (^.^)", " / >_"},
                        new String[]{" (\\_/)", " (^o^)", " / >_"},
                        new String[]{" (\\_/)", " (^.^)", " / >_"}
                ),
                150, false
        ));

        ANIMATIONS.put(BunnyState.ERROR, new BunnyAnimation(
                BunnyState.ERROR,
                List.of(
                        new String[]{" (\\_/)", " (>.<)", " / >_"},
                        new String[]{" (\\_/)", " (•-•)?", " / >_"},
                        new String[]{" (\\_/)", " (>.<)", " / >_"}
                ),
                250, false
        ));
        
        ANIMATIONS.put(BunnyState.CONFUSED, new BunnyAnimation(
                BunnyState.CONFUSED,
                List.of(
                        new String[]{" (\\_/)", " (>.<)", " / >_"},
                        new String[]{" (\\_/)", " (•-•)?", " / >_"},
                        new String[]{" (\\_/)", " (>.<)", " / >_"}
                ),
                250, false
        ));

        ANIMATIONS.put(BunnyState.GOODBYE, new BunnyAnimation(
                BunnyState.GOODBYE,
                List.of(
                        new String[]{" (\\_/)", " (o.o)", " / >_"}, // idle
                        new String[]{" (\\_/)", " (-.-)", " / >_"}, // blink
                        new String[]{" (\\_/)", " (^.^)", " / >\\"}, // wave
                        new String[]{" (\\_/)", " (^.^)", " / >_"}  // smile
                ),
                150, false
        ));
    }

    public static BunnyAnimation get(BunnyState state) {
        return ANIMATIONS.getOrDefault(state, ANIMATIONS.get(BunnyState.IDLE));
    }
}
