import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

public class Tracker {
    public static record View(
        int id, 
        String primary, 
        String backup, 
        InetSocketAddress serverAddr
    ) implements Serializable {
        public String toString() {
            return String.format("!! #%d: %s@%s; %s !!", id, primary, serverAddr, backup);
        }
    }

    private static final Logger log = Logger.getGlobal();
    public static void main(String[] args) throws Exception {
        log.setLevel(Level.ALL);

        var channel = DatagramChannel.open();
        channel.bind(new InetSocketAddress("", Integer.parseInt(args[0])));
        int n = Integer.parseInt(args[1]), k = Integer.parseInt(args[2]);
        log.fine("tracker start: addr = " + channel.getLocalAddress());

        var addrTable = new HashMap<String, InetSocketAddress>();
        var view = new View(0, null, null, null);
        while (true) {
            var buf = ByteBuffer.allocate(1024);
            var remoteAddr = channel.receive(buf);
            buf.flip();
            int msgType = buf.getInt(), msgLen = buf.getInt();
            assert msgLen == buf.remaining();
            switch (msgType) {
                case Transport.QueryMessage.TYPE: {
                    var msg = (Transport.QueryMessage) Transport.parse(buf);
                    log.info(msg.toString());
                    addrTable.put(msg.name(), msg.addr());
                    if (view.id() == 0) {
                        view = new View(1, msg.name(), null, msg.addr());
                        log.info("view change:\n" + view);
                    } else if (view.id() == 1) {
                        view = new View(2, view.primary(), msg.name(), view.serverAddr());
                        log.info("view change:\n" + view);
                    }
                    break;
                }
                case Transport.PlayerFailMessage.TYPE: {
                    var msg = (Transport.PlayerFailMessage) Transport.parse(buf);
                    log.info(msg.toString());
                    if (msg.viewId() == view.id()) {
                        if (msg.failed().equals(view.primary())) {
                            assert !msg.nextBackup().equals(view.backup());
                            view = new View(
                                view.id() + 1, view.backup(), msg.nextBackup(), 
                                addrTable.get(view.backup())
                            );
                            log.info("view change:\n" + view);
                        } else if (msg.failed().equals(view.backup())) {
                            view = new View(
                                view.id() + 1, view.primary(), msg.nextBackup(), view.serverAddr()
                            );
                            log.info("view change:\n" + view);
                        } else {
                            throw new RuntimeException();
                        }
                    }
                    break;
                }
                default:
                    throw new RuntimeException();
            }
            Transport.send(
                channel, remoteAddr, Transport.ViewMessage.TYPE, 
                new Transport.ViewMessage(view, n, k)
            );
        }
    }

    private DatagramChannel channel;
    private String name;
    private InetSocketAddress addr;
    public int n, k;
    public Tracker(String name, InetSocketAddress addr, InetSocketAddress trackerAddr) throws Exception {
        channel = DatagramChannel.open();
        channel.connect(trackerAddr);
        this.name = name;
        this.addr = addr;
    }

    public void query() throws Exception {
        Transport.send(
            channel, Transport.QueryMessage.TYPE, 
            new Transport.QueryMessage(name, addr)
        );
    }

    public void playerFail(int viewId, String failed, String nextBackup) throws Exception {
        Transport.send(
            channel, Transport.PlayerFailMessage.TYPE,
            new Transport.PlayerFailMessage(viewId, failed, nextBackup)
        );
    }

    public View receiveView() throws Exception {
        var buf = ByteBuffer.allocate(1024);
        channel.read(buf);
        buf.flip();
        int msgType = buf.getInt(), msgLen = buf.getInt();
        assert msgType == Transport.ViewMessage.TYPE;
        assert msgLen == buf.remaining();
        var msg = (Transport.ViewMessage) Transport.parse(buf);
        log.fine(msg.toString());
        n = msg.n();
        k = msg.k();
        return msg.view();
    }
}
