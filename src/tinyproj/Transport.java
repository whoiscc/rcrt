import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.logging.*;

public class Transport {
    public static record QueryMessage(
        String name, 
        InetSocketAddress addr
    ) implements Serializable {
        public static final int TYPE = 1;
    }

    public static record PlayerFailMessage(
        int viewId,
        String failed,
        String nextBackup
    ) implements Serializable {
        public static final int TYPE = 2;
    }

    public static record ViewMessage(
        Tracker.View view,
        int n,
        int k
    ) implements Serializable {
        public static final int TYPE = 3;
    }

    public static record HelloMessage(
        String name
    ) implements Serializable {
        public static final int TYPE = 4;
    }

    public static record InitBackupRequestMessage(
        int viewId
    ) implements Serializable {
        public static final int TYPE = 5;
    }

    public static record InitBackupReplyMessage(
        App app
    ) implements Serializable {
        public static final int TYPE = 6;
    }

    public static record SyncMessage(
        App app
    ) implements Serializable {
        public static final int TYPE = 7;
    }

    public static record SyncOkMessage() implements Serializable {
        public static final int TYPE = 8;
    }

    public static record HeartbeatMessage(
        String name
    ) implements Serializable {
        public static final int TYPE = 9;
    }

    public static record HeartbeatOkMessage(
        int viewId
    ) implements Serializable {
        public static final int TYPE = 10;
    }

    public static Object parse(ByteBuffer buffer) throws Exception {
        var data = new byte[buffer.remaining()];
        buffer.get(data);
        var in = new ObjectInputStream(new ByteArrayInputStream(data));
        return in.readObject();
    }

    public static ByteBuffer socketReceiveRaw(ReadableByteChannel channel) throws Exception {
        var buf = ByteBuffer.allocate(1024).limit(8);
        try {
            while (buf.remaining() != 0) {
                if (channel.read(buf) == -1) {
                    throw new IOException();
                }
            }
            buf.flip();
            int msgType = buf.getInt(), msgLen = buf.getInt();
            buf.limit(8 + msgLen);
            while (buf.remaining() != 0) {
                if (channel.read(buf) == -1) {
                    throw new IOException();
                }
            }
        } catch (IOException e) {
            // Logger.getGlobal().throwing(Transport.class.getName(), "socketReceiveRaw", e);
            Logger.getGlobal().info("ignore exception and expect failure detected soon");
            return null;
        }
        return buf.flip();
    }

    private static ByteBuffer dump(int msgType, Object msg) throws Exception {
        var buf = ByteBuffer.allocate(1024);
        buf.putInt(msgType).putInt(-1);
        var byteOut = new ByteArrayOutputStream();
        var out = new ObjectOutputStream(byteOut);
        out.writeObject(msg);
        out.close();
        var msgLen = byteOut.size();
        assert buf.remaining() >= msgLen;
        buf.putInt(4, msgLen);
        buf.put(byteOut.toByteArray());        
        return buf.flip();
    }

    public static void send(WritableByteChannel channel, int msgType, Object msg) throws Exception {
        try {
            channel.write(dump(msgType, msg));
        } catch (IOException e) {
            // Logger.getGlobal().throwing(Transport.class.getName(), "send", e);
            Logger.getGlobal().info("ignore exception and expect failure detected soon");
        }
    }

    public static void send(
        DatagramChannel server, SocketAddress remote, int msgType, Object msg
    ) throws Exception {
        server.send(dump(msgType, msg), remote);
    }

}
