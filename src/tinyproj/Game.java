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
            var view = View.fromReply(new String(buf, 0, pkt.getLength()));
            try {
                System.out.printf(
                    "* Start view: primary = %s, backup = %s%n", 
                    view.primary, view.backup
                );
                game.startView(view);
                System.out.printf("* Start view done: mode = %s%n", game.mode);
            } catch (ConnectException e) {
                game.kickPlayer(view.primary);
                continue;
            }
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
    private HashMap<String, PlayerThread> threadMap;
    private Thread inSyncThread;
    private final UIThread uiThread;
    private HashMap<String, LinkedBlockingQueue<Integer>> queueMap;
    private Socket client;
    private final Object moveBarrier = new Object();
    private final Object syncBarrier = new Object();
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

        inSyncThread = null;

        uiThread = new UIThread(this);
        uiThread.start();
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
            if (client != null) {
                client.close();
                Thread.sleep(100);
                client = null;
            }
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
                // assert mode.equals("BACKUP");
            }
            mode = "PRIMARY";
            this.view = view;
            return;
        }

        if (view.backup != null && view.backup.equals(name) && this.view != null && mode.equals("BACKUP")) {
            assert view.primary.equals(this.view.primary);
            this.view = view;
            return;
        }

        assert this.view == null || mode.equals("CLIENT");
        assert this.view == null || view.backup.equals(name) || !view.primary.equals(this.view.primary);
        Thread.sleep(400);
        if (this.view == null || !view.primary.equals(this.view.primary)) {
            if (client != null) {
                client.close();
                Thread.sleep(100);
            }
            client = new Socket();
            client.bind(addr);
            client.connect(view.primaryAddr);
        }
        if (view.backup.equals(name)) {
            // TODO initialize for backup
            mode = "BACKUP";
        } else {
            mode = "CLIENT";
        }
        this.view = view;
        return;
    }

    private void runServer() throws Exception {
        if (connMap.containsKey(view.backup)) {
            var writer = new StringWriter();
            writer.write("TINYPROJ$RESET\n");
            connMap.get(view.backup).getOutputStream().write(writer.toString().getBytes());
        }
        var heartbeatMap = new HashMap<String, Boolean>();
        if (view.backup != null) {
            heartbeatMap.put(view.backup, false);
        }
        for (var name : connMap.keySet()) {
            heartbeatMap.put(name, false);
        }
        var nextCheckHeartbeat = System.currentTimeMillis() + 800;
        var nextHeartbeat = System.currentTimeMillis();
        while (true) {
            if (serverSelector.selectNow() != 0) {
                serverSelector.selectedKeys().clear();
                var conn = server.accept().socket();
                System.out.println("* Incoming connection: " + conn.getRemoteSocketAddress());
                Thread.sleep(300);
                var scanner = new Scanner(conn.getInputStream());
                assert scanner.next().equals("TINYPROJ$MOVE");
                var name = scanner.next();
                var direction = scanner.nextInt();
                System.out.println("* Accept connection: name = " + name);
                connMap.put(name, conn);

                if (name == view.backup) {
                    var writer = new StringWriter();
                    writer.write("TINYPROJ$RESET\n");
                    conn.getOutputStream().write(writer.toString().getBytes());
                }
                movePlayer(name, direction);

                queryView();
                return;
            }
            if (System.currentTimeMillis() > nextCheckHeartbeat) {
                for (var entry : heartbeatMap.entrySet()) {
                    if (!entry.getValue()) {
                        kickPlayer(entry.getKey());
                        return;
                    }
                    entry.setValue(false);
                }
                nextCheckHeartbeat = System.currentTimeMillis() + 800;
            }
            if (System.currentTimeMillis() > nextHeartbeat) {
                for (var entry: connMap.entrySet()) {
                    var writer = new StringWriter();
                    writer.write("TINYPROJ$HEARTBEAT\n");
                    try {
                        entry.getValue().getOutputStream().write(writer.toString().getBytes());
                    } catch (IOException e) {
                        kickPlayer(entry.getKey());
                        return;
                    }
                }
                nextHeartbeat = System.currentTimeMillis() + 500;
            }

            for (var entry : connMap.entrySet()) {
                if (entry.getValue().getInputStream().available() == 0) {
                    continue;
                }
                var buf = new byte[1500];
                var len = entry.getValue().getInputStream().read(buf);
                assert len > 0;
                var scanner = new Scanner(new String(buf, 0, len));
                assert scanner.hasNext();
                while (scanner.hasNext()) {
                    // var msgType = scanner.next();
                    // System.out.printf("%s: %s%n", entry.getKey(), msgType);
                    // switch (msgType) {
                    switch(scanner.next()) {
                        case "TINYPROJ$MOVE":
                            assert scanner.next().equals(entry.getKey());
                            movePlayer(entry.getKey(), scanner.nextInt());
                            break;
                        case "TINYPROJ$SYNC_OK":
                            synchronized (syncBarrier) {
                                syncBarrier.notify();
                            }
                            break;
                        case "TINYPROJ$HEARTBEAT_OK":
                            heartbeatMap.put(entry.getKey(), true);
                            break;
                        default:
                            assert false;
                    }
                }
            }
        }
    }

    private void kickPlayer(String player) throws Exception {
        System.out.println("* Kick player " + player);
        var writer = new StringWriter();
        writer.write("TINYPROJ$PLAYER_FAIL\n");
        writer.write(player + "\n");
        var buf = writer.toString().getBytes();
        tracker.send(new DatagramPacket(buf, buf.length));

        if (mode != null && mode.equals("PRIMARY")) {
            connMap.remove(player);
            if (player.equals(view.backup) && inSyncThread != null) {
                inSyncThread.interrupt();
                inSyncThread = null;
            }

            // TODO player thread
        }
    }

    private void queryView() throws Exception {
        var writer = new StringWriter();
        writer.write("TINYPROJ$QUERY\n");
        var buf = writer.toString().getBytes();
        tracker.send(new DatagramPacket(buf, buf.length));
    }

    private void movePlayer(String name, int direction) throws Exception {
        // should be inside startView, backup => primary
        if (!threadMap.containsKey(name)) {
            // assert direction == 0;
            queueMap.put(name, new LinkedBlockingQueue<>());
            threadMap.put(name, new PlayerThread(this, name));
            threadMap.get(name).start();
        }
        queueMap.get(name).put(direction);
    }

    private void syncState() throws Exception {
        if (view.backup == null || !connMap.containsKey(view.backup)) {
            return;
        }
        var writer = new StringWriter();
        writer.write("TINYPROJ$SYNC\n");
        // TODO
        connMap.get(view.backup).getOutputStream().write(writer.toString().getBytes());
        synchronized (syncBarrier) {
            syncBarrier.wait();
        }
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
        if (connMap.containsKey(name)) {
            try {
                connMap.get(name).getOutputStream().write(writer.toString().getBytes());
            } catch (IOException e) {
                //
            } 
        }
    }

    private void runClient() throws Exception {
        var scanner = new Scanner(client.getInputStream());
        while (true) {
            String msgType;
            try {
                msgType = scanner.next();
            } catch (NoSuchElementException e) {
                kickPlayer(view.primary);
                uiThread.interrupt();
                return;
            }
            switch (msgType) {
                case "TINYPROJ$STATE":
                    synchronized (moveBarrier) {
                        moveBarrier.notify();
                    }
                    break;
                case "TINYPROJ$RESET":
                    System.out.println("* Reset view");
                    queryView();
                    return;
                case "TINYPROJ$SYNC": {
                    assert mode.equals("BACKUP");
                    // TODO
                    var writer = new StringWriter();
                    writer.write("TINYPROJ$SYNC_OK\n");
                    client.getOutputStream().write(writer.toString().getBytes());
                    break;
                }
                case "TINYPROJ$HEARTBEAT": {
                    var writer = new StringWriter();
                    writer.write("TINYPROJ$HEARTBEAT_OK\n");
                    client.getOutputStream().write(writer.toString().getBytes());
                    break;
                }
                default:
                    assert false;       
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
        try {
            synchronized (moveBarrier) {
                moveBarrier.wait();
            }
        } catch (InterruptedException e) {
            System.out.println("** Move interrupt");
            var view = this.view;
            while (this.view == view) {
                Thread.sleep(100);
            }
            System.out.println("** Retry interrupted move");
            moveThis(direction);
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
            } catch (InterruptedException e) {
                System.out.println("** Idle interrupt");
                run();
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
                        // TODO update

                        assert game.inSyncThread == null;
                        game.inSyncThread = Thread.currentThread();
                        try {
                            game.syncState();
                        } catch (InterruptedException e) {
                            System.out.println("** Give up interrupted sync");
                        }
                        game.inSyncThread = null;
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
