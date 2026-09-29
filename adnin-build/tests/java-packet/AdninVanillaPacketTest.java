package adnin.packettests;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Offline checks against the actual vanilla 1.8.9 ie/em classes.
 *
 * ie is C01PacketChatMessage and em is PacketBuffer. No client, player,
 * connection, game instance, original DLL, or external service is started.
 * Compile and run with the original 1.8.9 jar, Netty 4.0.23, and Guava 17.
 * The named test package avoids the signed vanilla default-package conflict;
 * reflection invokes the original packet classes without replacing them.
 */
public final class AdninVanillaPacketTest {
    private static final String PARTY = "/pc ";
    private static final String HAN = "\u4e2d";
    private static final String EMOJI = "\ud83d\ude80";
    private static final Constructor<?> PACKET_STRING, PACKET_EMPTY, BUFFER;
    private static final Method GET_MESSAGE, READ, WRITE, WRITE_STRING;
    private static int checks;

    static {
        try {
            Class<?> packet = Class.forName("ie");
            Class<?> buffer = Class.forName("em");
            PACKET_STRING = packet.getConstructor(String.class);
            PACKET_EMPTY = packet.getConstructor();
            BUFFER = buffer.getConstructor(ByteBuf.class);
            GET_MESSAGE = packet.getMethod("a");
            READ = packet.getMethod("a", buffer);
            WRITE = packet.getMethod("b", buffer);
            WRITE_STRING = buffer.getMethod("a", String.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static void main(String[] args) throws Exception {
        for (int length : new int[]{0, 1, 99, 100, 101, 256}) {
            String message = repeat("a", length);
            roundTrip(message, message.substring(0, Math.min(100, length)),
                    "ASCII constructor length " + length);
        }

        roundTrip(PARTY + repeat("p", 96), PARTY + repeat("p", 96),
                "96-unit party body fits the 100-unit command");
        roundTrip(PARTY + repeat("p", 97), PARTY + repeat("p", 96),
                "97-unit party body loses its final character");
        roundTrip(PARTY + repeat("p", 252), PARTY + repeat("p", 96),
                "256-unit constructor probe observes the real 100-unit cap");

        roundTrip(repeat(HAN, 100), repeat(HAN, 100), "100 Chinese characters");
        roundTrip(repeat(HAN, 101), repeat(HAN, 100), "101 Chinese characters");
        roundTrip(repeat(HAN, 256), repeat(HAN, 100), "256 Chinese characters");
        check(repeat(HAN, 100).getBytes(StandardCharsets.UTF_8).length == 300,
                "100 Chinese characters occupy 300 UTF-8 bytes");

        roundTrip(repeat(EMOJI, 50), repeat(EMOJI, 50), "50 complete surrogate pairs");
        roundTrip(repeat(EMOJI, 51), repeat(EMOJI, 50), "51 surrogate pairs truncate to 50");
        roundTrip(repeat("x", 98) + EMOJI, repeat("x", 98) + EMOJI,
                "a complete surrogate pair ends exactly at unit 100");

        // Vanilla's substring(0, 100) can split a pair. Its UTF-8 encoder then
        // replaces the unpaired high surrogate, so ordinary splitting must
        // avoid this boundary before constructing a real packet.
        String unsafe = PARTY + repeat("x", 95) + EMOJI;
        String truncated = PARTY + repeat("x", 95) + EMOJI.charAt(0);
        check(unsafe.length() == 101 && truncated.length() == 100,
                "unsafe party command straddles the constructor boundary");
        check(Character.isHighSurrogate(truncated.charAt(99)),
                "truncation leaves an unmatched high surrogate");
        roundTrip(unsafe, truncated, PARTY + repeat("x", 95) + "?",
                "split surrogate is replaced during actual UTF-8 serialization");

        String first = PARTY + repeat("x", 95);
        String second = PARTY + EMOJI;
        roundTrip(first, first, "safe first party segment");
        roundTrip(second, second, "safe second party segment keeps the pair");
        check((first.substring(PARTY.length()) + second.substring(PARTY.length()))
                        .equals(unsafe.substring(PARTY.length())),
                "safe party segments preserve the complete original body");

        decodeDirect(repeat("a", 100), false, "direct 100-unit ASCII input");
        decodeDirect(repeat("a", 101), true, "direct 101-unit ASCII input");
        decodeDirect(repeat("a", 256), true, "direct 256-unit ASCII input");
        decodeDirect(repeat("a", 401), true, "direct input exceeds 400 encoded bytes");
        decodeDirect(repeat(HAN, 100), false, "direct 100-unit Chinese input");
        decodeDirect(repeat(HAN, 101), true, "303 bytes still exceed 100 decoded units");
        decodeDirect(repeat(EMOJI, 50), false, "direct 100-unit supplementary input");
        decodeDirect(repeat(EMOJI, 51), true, "102 decoded units with 204 encoded bytes");
        decodeDirect(PARTY + repeat("p", 96), false, "direct safe party command");
        decodeDirect(PARTY + repeat("p", 97), true, "direct oversized party command");

        System.out.println("AdninVanillaPacketTest: " + checks + " checks passed"
                + " (real vanilla packets; constructor/read cap 100 UTF-16 units; no network)");
    }

    private static void roundTrip(String input, String expected, String label) throws Exception {
        roundTrip(input, expected, expected, label);
    }

    private static void roundTrip(String input, String expectedStored, String expectedDecoded,
            String label) throws Exception {
        Object outgoing = PACKET_STRING.newInstance(input);
        String stored = (String) invoke(GET_MESSAGE, outgoing);
        check(stored.equals(expectedStored), label + ": constructor content");
        check(stored.length() <= 100, label + ": constructor length");
        ByteBuf bytes = Unpooled.buffer();
        try {
            Object buffer = BUFFER.newInstance(bytes);
            invoke(WRITE, outgoing, buffer);
            check(bytes.writerIndex() > 0, label + ": packet serialized");
            bytes.readerIndex(0);
            Object incoming = PACKET_EMPTY.newInstance();
            invoke(READ, incoming, buffer);
            check(invoke(GET_MESSAGE, incoming).equals(expectedDecoded), label + ": decoded content");
            check(bytes.readableBytes() == 0, label + ": packet consumed exactly");
        } finally {
            bytes.release();
        }
    }

    private static void decodeDirect(String input, boolean reject, String label) throws Exception {
        ByteBuf bytes = Unpooled.buffer();
        try {
            Object buffer = BUFFER.newInstance(bytes);
            // Use the real generic string writer to test the receiver limit
            // independently of ie(String)'s earlier truncation.
            invoke(WRITE_STRING, buffer, input);
            bytes.readerIndex(0);
            Object incoming = PACKET_EMPTY.newInstance();
            boolean rejected = false;
            try {
                invoke(READ, incoming, buffer);
            } catch (DecoderException expected) {
                rejected = true;
            }
            check(rejected == reject, label + ": decoder result");
            if (!reject) {
                check(invoke(GET_MESSAGE, incoming).equals(input), label + ": exact input retained");
                check(bytes.readableBytes() == 0, label + ": all bytes consumed");
            }
        } finally {
            bytes.release();
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
}
