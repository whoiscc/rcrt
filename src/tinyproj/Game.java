import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.channels.*;

public class Game {
    public static void main(String[] args) throws Exception {
        var socket = new Socket();
        socket.bind(null);
        var addr = (InetSocketAddress) socket.getLocalSocketAddress();
        socket.close();
        var tracker = new DatagramSocket();
        tracker.connect(new InetSocketAddress(args[0], Integer.parseInt(args[1])));
        var name = args[2];
        System.out.printf("* Player start, address = %s, name = %s%n", addr, name);

        var writer = new StringWriter();
        writer.write("TINYPROJ$HELLO\n");
        writer.write(name + "\n");
        writer.write(addr.getHostName() + "\n");
        writer.write(addr.getPort() + "\n");
        var buf = writer.toString().getBytes();
        tracker.send(new DatagramPacket(buf, buf.length));
        buf = new byte[1500];
        var pkt = new DatagramPacket(buf, buf.length);

        var game = new Game(addr, name, tracker);
        while (true) {
            tracker.receive(pkt);
            game.startView(View.fromReply(new String(buf, 0, pkt.getLength())));
            System.out.printf(
                "* Start view: primary = %s, backup = %s, mode = %s%n", 
                game.view.primary, game.view.backup, game.mode
            );
            if (game.mode.equals("PRIMARY")) {
                game.runServer();
            } else {
                game.runClient();
            }
        }
    }

    private final SocketAddress addr;
    private final String name;
    private final DatagramSocket tracker;
    private View view;
    private String mode;  // redundant to view + name
    private Selector serverSelector;
    private ServerSocketChannel server;
    private HashMap<String, Socket> connMap;
    private HashMap<String, Thread> threadMap;
    private HashMap<String, LinkedBlockingQueue<Integer>> queueMap;
    private Socket client;
    private final Object moveBarrier = new Object();
    private final Object appMutex = new Object();

    private Game(SocketAddress addr, String name, DatagramSocket tracker) throws Exception {
        this.addr = addr;
        this.name = name;
        this.tracker = tracker;
        view = null;
        // TODO set n and k for app

        serverSelector = null;
        server = null;
        connMap = null;
        client = null;

        (new UIThread(this)).start();
    }

    private record View(int n, int k, String primary, SocketAddress primaryAddr, String backup) {
        public static View fromReply(String reply) {
            Scanner scanner = new Scanner(reply);
            assert scanner.next().equals("TINYPROJ$VIEW");
            return new View(
                scanner.nextInt(),
                scanner.nextInt(),
                scanner.next(),
                new InetSocketAddress(scanner.next(), scanner.nextInt()),
                scanner.hasNext() ? scanner.next() : null
            );
        }
    }

    private void startView(View view) throws Exception {
        if (this.view != null && mode.equals("PRIMARY")) {
            assert view.primary.equals(name);
            this.view = view;
            return;
        }
        if (view.primary.equals(name)) {
            assert server == null;
            serverSelector = Selector.open();
            server = ServerSocketChannel.open();
            server.bind(addr);
            server.configureBlocking(false);
            server.register(serverSelector, SelectionKey.OP_ACCEPT, null);
            connMap = new HashMap<>();

            threadMap = new HashMap<>();
            queueMap = new HashMap<>();

            if (this.view == null) {
                // TODO initialize app
            } else {
                assert mode.equals("BACKUP");
            }
            mode = "PRIMARY";
            this.view = view;
            return;
        }

        assert this.view == null || mode.equals("CLIENT");
        assert this.view == null || !view.primary.equals(this.view.primary);
        if (view.backup.equals(name)) {
            // TODO initialize for backup
            mode = "BACKUP";
        } else {
            mode = "CLIENT";
        }
        if (client != null) {
            client.close();
        }
        client = new Socket();
        client.connect(view.primaryAddr);
        this.view = view;
        return;
    }

    private void runServer() throws Exception {
        while (true) {
            if (serverSelector.selectNow() != 0) {
                serverSelector.selectedKeys().clear();
                var conn = server.accept().socket();
                var scanner = new Scanner(conn.getInputStream());
                assert scanner.next().equals("TINYPROJ$MOVE");
                var name = scanner.next();
                assert scanner.nextInt() == 0;
                System.out.println("Accept connection: name = " + name);
                connMap.put(name, conn);

                movePlayer(name, 0);
            }
            for (var entry : connMap.entrySet()) {
                if (entry.getValue().getInputStream().available() == 0) {
                    continue;
                }
                var scanner = new Scanner(entry.getValue().getInputStream());
                assert scanner.next().equals("TINYPROJ$MOVE");  // TODO SYNC_OK
                assert scanner.next().equals(entry.getKey());
                movePlayer(entry.getKey(), scanner.nextInt());
            }
        }
    }

    private void movePlayer(String name, int direction) throws Exception {
        if (!threadMap.containsKey(name)) {
            assert direction == 0;
            queueMap.put(name, new LinkedBlockingQueue());
            threadMap.put(name, new PlayerThread(this, name));
            threadMap.get(name).start();
        }
        queueMap.get(name).put(direction);
    }

    private void sendState(String name) throws Exception {
        if (name.equals(this.name)) {
            synchronized (moveBarrier) {
                moveBarrier.notify();
            }
            return;
        }
        var writer = new StringWriter();
        writer.write("TINYPROJ$STATE\n");
        // TODO
        connMap.get(name).getOutputStream().write(writer.toString().getBytes());
    }

    private void runClient() throws Exception {
        var scanner = new Scanner(client.getInputStream());
        while (true) {
            assert scanner.next().equals("TINYPROJ$STATE");  // TODO SYNC
            synchronized (moveBarrier) {
                moveBarrier.notify();
            }
        }
    }

    private void moveThis(int direction) throws Exception {
        if (mode.equals("PRIMARY")) {
            movePlayer(name, direction);
            synchronized (moveBarrier) {
                moveBarrier.wait();
            }
            return;
        }
        var writer = new StringWriter();
        writer.write("TINYPROJ$MOVE\n");
        writer.write(name + "\n");
        writer.write(direction + "\n");
        client.getOutputStream().write(writer.toString().getBytes());
        synchronized (moveBarrier) {
            moveBarrier.wait();
        }
    }

    private static class UIThread extends Thread {
        private final Game game;
        UIThread(Game game) {
            this.game = game;
        }

        public void run() {
            try {
                while (game.view == null) {
                    Thread.sleep(100);
                }
                System.out.println("** UI thread start");
                game.moveThis(0);

                var scanner = new Scanner(System.in);
                while (true) {
                    var direction = scanner.nextInt();
                    game.moveThis(direction);
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    private static class PlayerThread extends Thread {
        private final Game game;
        private String name;
        PlayerThread(Game game, String name) {
            this.game = game;
            this.name = name;
        }

        public void run() {
            try {
                // TODO initialize player
                while (true) {
                    var direction = game.queueMap.get(name).take();
                    synchronized (game.appMutex) {
                        System.out.printf(
                            "** Mutex move: player = %s, direction = %d%n", 
                            name, direction
                        );
                        // TODO update & sync
                    }
                    game.sendState(name);
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
}
