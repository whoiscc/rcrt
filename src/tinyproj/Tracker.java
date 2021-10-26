import java.net.*;
import java.util.*;
import java.io.*;

public class Tracker {
    public static void main(String[] args) throws Exception {
        var server = new DatagramSocket(Integer.parseInt(args[0]));      
        int n = Integer.parseInt(args[1]), k = Integer.parseInt(args[2]);
        var playerMap = new HashMap<String, InetSocketAddress>();
        String primary = null, backup = null;

        while (true) {
            var buf = new byte[1500];
            var pkt = new DatagramPacket(buf, buf.length);
            server.receive(pkt);

            var scanner = new Scanner(new String(buf, 0, pkt.getLength()));
            switch (scanner.next()) {
                case "TINYPROJ$HELLO": {
                    String name = scanner.next(), hostname = scanner.next();
                    var port = scanner.nextInt();
                    playerMap.put(name, new InetSocketAddress(hostname, port));
                    if (primary == null) {
                        assert backup == null;
                        primary = name;
                    }
                    break;
                }
                case "TINYPROJ$QUERY":
                    break;
                case "TINYPROJ$PLAYER_FAIL": {
                    assert primary != null;
                    assert playerMap.size() >= 3;
                    var name = scanner.next();
                    playerMap.remove(name);
                    if (name.equals(primary)) {
                        primary = backup;
                        backup = null;
                    } else if (name.equals(backup)) {
                        backup = null;
                    }
                    break;
                }
                default:
                    assert false;
            }
            assert primary != null;
            if (backup == null && playerMap.size() >= 2) {
                for (var name : playerMap.keySet()) {
                    if (!name.equals(primary)) {
                        backup = name;
                        break;
                    }
                }
            }

            var writer = new StringWriter();
            writer.write("TINYPROJ$VIEW\n");
            writer.write(String.format("%s%n", n));
            writer.write(String.format("%s%n", k));
            writer.write(primary + "\n");            
            writer.write(playerMap.get(primary).getHostName() + "\n");
            writer.write(playerMap.get(primary).getPort() + "\n");
            if (backup != null) {
                writer.write(backup + "\n");
            }
            buf = writer.toString().getBytes();
            server.send(new DatagramPacket(buf, buf.length, pkt.getSocketAddress()));
        }
    }
}
