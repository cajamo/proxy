import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ProxyThread extends Thread {
    private Socket socket;
    private static final int BUFFER_SIZE = Short.MAX_VALUE;

    public ProxyThread(Socket socket) {
        super("ProxyThread");
        this.socket = socket;
    }

    /**
     * HTTP Time format for If-Modified-Since format is like
     * Wed, 21 Oct 2015 07:28:00 GMT
     *
     * @param instant The Instant you want to convert to HTTP time
     * @return returns String in If-Modified-Since format
     */
    private String toHttpTimeFormat(Instant instant) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date(instant.toEpochMilli()));
    }

    /**
     * Checks if a given URL (in String format) has been cached already.
     *
     * @param url The URL to check, in String format.
     * @return Whether or not has been cached yet.
     */
    private boolean isCached(String url) {
        return ProxyServer.getSiteMap().containsKey(url);
    }

    private byte[] getStreamOutput(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[BUFFER_SIZE];

        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    /**
     * Connects to a given URL.
     *
     * @param urlStr String URL to connect to, e.g. www.google.com
     * @return Returns InputStream of the webpage content.
     */
    private InputStream connectToUrl(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        URLConnection conn = url.openConnection();
        HttpURLConnection huc = (HttpURLConnection) conn;

        if (isCached(urlStr)) {
            CachedSite cachedSite = ProxyServer.getSiteMap().get(urlStr);
            Instant lastVisit = cachedSite.getCachedTime();
            huc.setRequestProperty("If-Modified-Since", toHttpTimeFormat(lastVisit));
        }

        huc.connect();

        switch (huc.getResponseCode()) {
            case HttpURLConnection.HTTP_OK:
                //Remove it to re-cache if HTTP has been modified.
                ProxyServer.getSiteMap().remove(urlStr);
                return huc.getInputStream();
            //Sometimes Java cannot follow the 301/302 errors, so we manually handle those cases.
            case HttpURLConnection.HTTP_MOVED_PERM:
            case HttpURLConnection.HTTP_MOVED_TEMP:
                return connectToUrl(huc.getHeaderField("Location"));
            case HttpURLConnection.HTTP_NOT_MODIFIED:
                return new ByteArrayInputStream(ProxyServer.getSiteMap().get(urlStr).getContent());
            //Unaware of how he wants us to handle these.
            case HttpURLConnection.HTTP_BAD_REQUEST:
            case HttpURLConnection.HTTP_NOT_IMPLEMENTED:
                return null;
        }
        return null;
    }

    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            String inputLine;
            String url = null;

            if ((inputLine = in.readLine()) != null) {
                // First line of request is GET http://bing.com HTTP/1.1
                url = inputLine.split(" ")[1];
            }

            InputStream conn = connectToUrl(url);
            if (conn == null) {
                System.out.println("Error has occurred: Exiting request");
                out.close();
                return;
            }

            byte[] webpageData = getStreamOutput(conn);

            if (!isCached(url)) {
                CachedSite cachedSite = new CachedSite(webpageData, Instant.now());
                ProxyServer.getSiteMap().put(url, cachedSite);
            }

            //Write to output (e.g. back to client)
            out.write(webpageData);
            out.close();
            in.close();
            if (socket != null) {
                socket.close();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}