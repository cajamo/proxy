import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyServer {
    private static Map<String, CachedSite> siteMap = new ConcurrentHashMap<>();
    private static int PORT_NUM = 6969;

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = null;
        boolean listening = true;

        try {
            serverSocket = new ServerSocket(PORT_NUM);
            System.out.println("Started proxy on port " + PORT_NUM);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        while (listening) {
            new ProxyThread(serverSocket.accept()).start();
        }
        serverSocket.close();
    }

    static Map<String, CachedSite> getSiteMap() {
        return siteMap;
    }
}
