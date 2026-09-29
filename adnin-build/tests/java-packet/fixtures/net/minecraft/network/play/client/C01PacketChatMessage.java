package net.minecraft.network.play.client;

/** Owned policy fixture. It never opens a connection or forwards a packet. */
public final class C01PacketChatMessage {
    public static int limit = 100;
    public static String mode = "normal";
    public static int constructions;
    public static int reads;
    public static String lastInput;
    private final String message;

    public C01PacketChatMessage(String value) {
        constructions++;
        lastInput = value;
        if ("constructor-exception".equals(mode)) throw new IllegalStateException("owned packet constructor failure");
        if ("constructor-linkage".equals(mode)) throw new NoClassDefFoundError("owned packet linkage failure");
        if ("null".equals(mode)) message = null;
        else if ("changed-prefix".equals(mode)) message = "/msg someone replaced";
        else if ("changed-body".equals(mode)) message = "/pc different body";
        else if ("expanded".equals(mode)) message = value + "x";
        else message = value.substring(0, Math.min(Math.max(0, limit), value.length()));
    }

    public String getMessage() {
        reads++;
        if ("getter-exception".equals(mode)) throw new IllegalArgumentException("owned packet getter failure");
        if ("getter-linkage".equals(mode)) throw new NoSuchMethodError("owned packet getter linkage failure");
        return message;
    }
}
