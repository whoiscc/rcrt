import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.*;
import java.io.*;

public class Game {
    private static final Logger log = Logger.getGlobal();
    public static void main(String[] args) throws Exception {
        log.setLevel(Level.ALL);
        log.setUseParentHandlers(false);
        System.setProperty(
            "java.util.logging.SimpleFormatter.format",
            "[%1$tT.%1$tL] [%2$s] %5$s%6$s%n"
        );
        var handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new SimpleFormatter());
        log.addHandler(handler);

        var name = args[2];
        var addr = generateAddress();
        var tracker = new Tracker(
            name, addr, new InetSocketAddress(args[0], Integer.parseInt(args[1]))
        );
        log.fine("game start: name = " + name + ", addr = " + addr);
        var game = new Game(name, addr, tracker);
        game.tracker.query();
        try {
            while (true) {
                var view = game.tracker.receiveView();
                log.entering(Game.class.getName(), "startView", view);
                try {
                    game.startView(view);
                } catch (ConnectException e) {
                    // risky?
                    log.info("start view fail: primary = " + view.primary());
                    game.tracker.playerFail(view.id(), view.primary(), name);
                    game.client = null;
                    continue;
                }
                if (view.primary().equals(name)) {
                    log.entering(Game.class.getName(), "runServer");
                    game.runServer();
                } else {
                    log.entering(Game.class.getName(), "runClient");
                    game.runClient();
                }
            }
        } catch (Throwable e) {
            log.throwing(Game.class.getName(), "main", e);
        } finally {
            // A successful run always terminates externally
            System.exit(1);
        }
    }

    private static InetSocketAddress generateAddress() throws Exception {
        var channel = SocketChannel.open();
        channel.socket().bind(new InetSocketAddress("", 0));
        var addr = (InetSocketAddress) channel.getLocalAddress();
        channel.close();
        return addr;
    }

    private final String name;
    private final InetSocketAddress addr;
    private final Tracker tracker;
    private Tracker.View view;
    private Game(String name, InetSocketAddress addr, Tracker tracker) {
        this.name = name;
        this.addr = addr;
        this.tracker = tracker;
        view = null;
    }

    private ServerSocketChannel server;
    private Selector serverSel;
    private SocketChannel backup;  // downlink for server
    private SocketChannel client;  // uplink for client (including backup)
    private App app;
    private HashMap<String, WorkerThread> workerTable;
    private void startView(Tracker.View view) throws Exception {
        if (this.view != null) {
            assert view.id() > this.view.id();
            assert !view.primary().equals(this.view.primary()) ||
                !view.backup().equals(this.view.backup());
        }

        if (view.primary().equals(name) && server == null) {
            server = ServerSocketChannel.open();
            server.bind(addr);
            server.configureBlocking(false);
            serverSel = Selector.open();
            server.register(serverSel, SelectionKey.OP_ACCEPT);
        }
        
        if (!view.primary().equals(name)) {
            // wait until primary up to date and set up
            if (this.view != null && this.view.primary().equals(view.primary())) {
                Thread.sleep(500);  // previous client guard exit
            } else {            
                // 1000ms detection + 200ms reaction
                Thread.sleep(1200);
            }
        }
        if (!view.primary().equals(name) && client == null) {
            client = SocketChannel.open();
            client.bind(addr);

            // client.connect(view.serverAddr());
            // simple hack, only work for localhost setup
            client.configureBlocking(false);
            log.entering(SocketChannel.class.getName(), "connect");
            client.connect(view.serverAddr());
            log.exiting(SocketChannel.class.getName(), "connect");
            Thread.sleep(1);
            if (!client.finishConnect()) {
                throw new ConnectException();
            }
            client.configureBlocking(true);
            log.fine("connected");

            Transport.send(
                client, Transport.HelloMessage.TYPE, 
                new Transport.HelloMessage(name)
            );
        }

        if (view.backup() != null && view.backup().equals(name)) {
            assert app == null;
            log.entering(Game.class.getName(), "initBackup");
            Transport.send(
                client, Transport.InitBackupRequestMessage.TYPE,
                new Transport.InitBackupRequestMessage(view.id())
            );
            // TODO server crash from now on...
            var buf = Transport.socketReceiveRaw(client);
            assert buf.getInt() == Transport.InitBackupReplyMessage.TYPE;
            var msgLen = buf.getInt();
            var msg = (Transport.InitBackupReplyMessage) Transport.parse(buf);
            app = msg.app();
            log.exiting(
                Game.class.getName(), 
                "initBackup", 
                "player set = " + app.playerSet()
            );
        }
        if (view.primary().equals(name)) {
            assert backup == null || this.view.id() == 1;
            if (this.view != null && this.view.primary().equals(name)) {
                assert !view.backup().equals(this.view.backup());
            } else {
                assert this.view == null || this.view.backup().equals(name);
                if (this.view == null) {
                    assert view.id() == 1;
                    app = new App();
                    app.createPlayer(name);
                }
                workerTable = new HashMap<>();
                for (var name : app.playerSet()) {
                    workerTable.put(name, new WorkerThread(name, null, this));
                    workerTable.get(name).start();
                }
            }
        }
        this.view = view;
    }

    private final Object appMutex = new Object();
    private final Object syncBarrier = new Object();
    private Thread inSync;
    private boolean syncDone = true;

    private void runServer() throws Exception {
        var heartbeatSet = new HashSet<String>();
        // 1200ms waiting in startView + 200ms reaction + 100ms redundant
        var nextCheckHearbeat = System.currentTimeMillis() + 1500;
        // pendingSwitchBackup == backup crash && hearbeatSet is {}
        var pendingSwitchBackup = false;
        while (true) {
            var now = System.currentTimeMillis();
            if (now > nextCheckHearbeat && !pendingSwitchBackup) {
                log.fine("check: heartbeat set = " + heartbeatSet);
                if (!heartbeatSet.contains(view.backup())) {
                    backup = null;  // disable backup before mutate app
                }

                // var iter = workerTable.entrySet().iterator();
                // while (iter.hasNext()) {
                //     var entry = iter.next();
                //     if (!heartbeatSet.contains(entry.getKey()) && !entry.getKey().equals(name)) {
                //         entry.getValue().interrupt();
                //         iter.remove();
                //     }
                // }

                if (!heartbeatSet.contains(view.backup())) {
                    if (!heartbeatSet.isEmpty()) {
                        var nextBackup = heartbeatSet.iterator().next();
                        log.info("backup fail: " + view.backup() + ", next backup = " + nextBackup);
                        tracker.playerFail(view.id(), view.backup(), nextBackup);
                        return;
                    } else {
                        log.info("next backup not show up yet so pending");
                        pendingSwitchBackup = true;
                    }
                }
                heartbeatSet.clear();
                nextCheckHearbeat = System.currentTimeMillis() + 600;
            }

            if (pendingSwitchBackup) {
                serverSel.select();
            } else {
                serverSel.select(nextCheckHearbeat - now);
            }
            for (var k : serverSel.selectedKeys()) {
                if (k.isAcceptable()) {
                    assert k.channel() == server;
                    var channel = server.accept();
                    log.info("incoming connection: addr = " + channel.getRemoteAddress());
                    channel.configureBlocking(false);
                    channel.register(serverSel, SelectionKey.OP_READ);
                    continue;
                }

                var channel = (SocketChannel) k.channel();
                var buf = Transport.socketReceiveRaw(channel);
                if (buf == null) {
                    k.cancel();
                    continue;  // heartbeat to cover failure
                }
                int msgType = buf.getInt(), msgLen = buf.getInt();
                switch (msgType) {
                    case Transport.InitBackupRequestMessage.TYPE: {
                        var msg = (Transport.InitBackupRequestMessage) Transport.parse(buf);
                        log.fine(msg.toString());
                        assert backup == null;
                        // should sync on appMutex?
                        Transport.send(
                            channel, Transport.InitBackupReplyMessage.TYPE,
                            new Transport.InitBackupReplyMessage(app)
                        );
                        backup = channel;

                        if (msg.viewId() > view.id()) {
                            assert view.id() == 1;
                            assert msg.viewId() == 2;
                            tracker.query();
                            return;
                        }
                        break;
                    }
                    case Transport.HelloMessage.TYPE: {
                        var msg = (Transport.HelloMessage) Transport.parse(buf);
                        log.fine(msg.toString());
                        if (!workerTable.containsKey(msg.name())) {
                            workerTable.put(msg.name(), new WorkerThread(msg.name(), channel, this));
                            workerTable.get(msg.name()).start();
                        } else {
                            workerTable.get(msg.name()).channel = channel;
                        }
                        break;
                    }
                    case Transport.SyncOkMessage.TYPE: {
                        var msg = (Transport.SyncOkMessage) Transport.parse(buf);
                        log.fine(msg.toString());
                        assert inSync != null;
                        assert !syncDone;
                        syncDone = true;
                        synchronized (syncBarrier) {
                            syncBarrier.notify();
                        }
                        break;
                    }
                    case Transport.HeartbeatMessage.TYPE: {
                        var msg = (Transport.HeartbeatMessage) Transport.parse(buf);
                        // log.fine(msg.toString());
                        heartbeatSet.add(msg.name());
                        Transport.send(
                            channel, Transport.HeartbeatOkMessage.TYPE,
                            new Transport.HeartbeatOkMessage(view.id())
                        );
                        if (pendingSwitchBackup) {
                            var nextBackup = msg.name();
                            log.fine("pending end: next backup = " + nextBackup);
                            tracker.playerFail(view.id(), view.backup(), nextBackup);
                            return;
                        }
                        break;
                    }
                    default:
                        throw new RuntimeException();
                }
            }
            serverSel.selectedKeys().clear();
        }
    }

    private static class WorkerThread extends Thread {
        private final String name;
        private SocketChannel channel;
        private final LinkedBlockingQueue<Integer> taskQueue;
        private final Game game;

        public WorkerThread(String name, SocketChannel channel, Game game) {
            this.name = name;
            this.channel = channel;
            this.game = game;
            taskQueue = new LinkedBlockingQueue<>();
        }

        private static final Logger log = Logger.getGlobal();
        public void run() {
            interrupted();
            log.info("worker start: " + name);
            try {
                synchronized (game.appMutex) {
                    if (!game.app.playerSet().contains(name)) {
                        game.app.createPlayer(name);
                        log.entering(Game.class.getName(), "syncApp", "name = " + name);
                        game.syncApp();
                    }
                }
                log.info("player ready: " + name);
                while (true) {
                    int direction;
                    try {
                        direction = taskQueue.take();
                    } catch (InterruptedException e) {
                        interrupted();
                        break;
                    }
                    synchronized (game.appMutex) {
                        // TODO app logic
                        // TODO sync app with backup
                    }
                    // TODO reply client
                    assert channel != null || game.name.equals(name);
                }
                synchronized (game.appMutex) {
                    game.app.removePlayer(name);
                    log.entering(Game.class.getName(), "syncApp", "removed name = " + name);
                    game.syncApp();
                }
            } catch (Throwable e) {
                log.throwing(WorkerThread.class.getName(), getName(), e);
                System.exit(1);
            }
        }
    }

    private void syncApp() throws Exception {
        if (backup == null) {
            log.info("backup not present so skip sync");
            return;
        }
        // assert sync-ed with appMutex, so no concurrent within this method
        assert inSync == null;
        assert syncDone;
        syncDone = false;
        log.entering(Game.class.getName(), "inSync", "thread = " + Thread.currentThread());
        inSync = Thread.currentThread();
        try {
            synchronized (syncBarrier) {
                Transport.send(
                    backup, Transport.SyncMessage.TYPE,
                    new Transport.SyncMessage(app)    
                );
                // TODO backup crash during this
                log.entering("syncBarrier", "wait");
                while (!syncDone) {
                    syncBarrier.wait();
                }
                log.exiting("syncBarrier", "wait");
            }            
        } finally {
            inSync = null;
            log.exiting(Game.class.getName(), "inSync", "thread = " + Thread.currentThread());
        }
    }

    boolean primaryHeartbeat, stopGuard = false;
    private void runClient() throws Exception {
        primaryHeartbeat = true;
        var master = Thread.currentThread();
        assert !stopGuard;  // previous guard already exit
        (new Thread(() -> {
            try {
                while (true) {
                    Transport.send(
                        client, Transport.HeartbeatMessage.TYPE,
                        new Transport.HeartbeatMessage(name)
                    );
                    Thread.sleep(500);
                    if (stopGuard) {
                        stopGuard = false;
                        return;
                    }
                    log.fine("check: primary hearbeat = " + primaryHeartbeat);
                    if (!primaryHeartbeat) {
                        if (view.backup().equals(name)) {
                            log.info("primary fail but no idea on next backup so keep query");
                            while (true) {
                                Thread.sleep(50);
                                log.info("query");
                                tracker.query();
                                if (tracker.receiveView().id() > this.view.id()) {
                                    tracker.query();  // silly
                                    break;
                                }
                            }
                        } else {
                            log.info("primary fail: next backup = " + name + " (aka self)");
                            tracker.playerFail(view.id(), view.primary(), name);
                        }
                        master.interrupt();
                        return;
                    }
                    primaryHeartbeat = false;
                }
            } catch (Throwable e) {
                log.throwing(Game.class.getName(), "client guard", e);
                System.exit(1);
            }
        })).start();
        while (!master.interrupted()) {
            ByteBuffer buf;
            try {
                buf = Transport.socketReceiveRaw(client);
                if (buf == null) {
                    // 1200ms (new client) waiting + 200ms reaction + 100ms redundant
                    Thread.sleep(1500);
                    throw new RuntimeException();  // unreachable
                }
            } catch (InterruptedException | ClosedByInterruptException e) {
                client.close();
                client = null;
                master.interrupted();
                return;
            }
            int msgType = buf.getInt(), msgLen = buf.getInt();
            switch (msgType) {
                case Transport.SyncMessage.TYPE: {
                    var msg = (Transport.SyncMessage) Transport.parse(buf);
                    assert view.backup().equals(name);
                    assert app != null;
                    app = msg.app();
                    log.fine("sync done: player set = " + app.playerSet());
                    Transport.send(
                        client, Transport.SyncOkMessage.TYPE,
                        new Transport.SyncOkMessage()
                    );
                    break;
                }
                case Transport.HeartbeatOkMessage.TYPE:
                    var msg = (Transport.HeartbeatOkMessage) Transport.parse(buf);
                    primaryHeartbeat = true;
                    if (msg.viewId() > view.id()) {
                        tracker.query();
                        stopGuard = true;
                        return;
                    }
                    break;
                default:
                    throw new RuntimeException();
            }
        }
    }
}

class App implements Serializable {
    private final HashMap<String, Boolean> playerTable;
    public App() {
        playerTable = new HashMap<>();
    }

    public Set<String> playerSet() {
        return playerTable.keySet();
    }

    public void createPlayer(String name) {
        playerTable.put(name, true);  // TODO
    }

    public void removePlayer(String name) {
        playerTable.remove(name);
    }
}
