import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ProxyThread extends Thread
{
    private Socket socket;
    private static final int BUFFER_SIZE = Short.MAX_VALUE;
    //Since each thread is a unique HTTP connection, we have instance variables.
    private HttpURLConnection urlConnection;
    private String urlName;

    public ProxyThread(Socket socket)
    {
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
    private String toHttpTimeFormat(Instant instant)
    {
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
    private boolean isCached(String url)
    {
        return ProxyServer.getSiteMap().containsKey(url);
    }

    private byte[] getStreamOutput(InputStream in) throws IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[BUFFER_SIZE];

        while ((nRead = in.read(data, 0, data.length)) != -1)
        {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private void cache(HttpURLConnection huc, byte[] websiteContent)
    {
        String etag = huc.getHeaderField("ETag");
        CachedSite cachedSite = new CachedSite(websiteContent, Instant.now(), etag);
        ProxyServer.getSiteMap().put(urlName, cachedSite);
    }

    /**
     * Connects to a given URL.
     *
     * @param huc HTTP Connection that user wants to extract data from
     * @return Returns InputStream of the webpage content.
     */
    private InputStream extractConnectionData(HttpURLConnection huc) throws IOException
    {
        URL url = huc.getURL();

        if (isCached(urlName))
        {
            CachedSite cachedSite = ProxyServer.getSiteMap().get(urlName);
            Instant lastVisit = cachedSite.getCachedTime();

            huc.setRequestProperty("If-Modified-Since", toHttpTimeFormat(lastVisit));

            if (cachedSite.getEtag() != null)
            {
                huc.setRequestProperty("If-None-Match", cachedSite.getEtag());
            }
        }

        InputStream inputStream = null;
        switch (huc.getResponseCode())
        {
            case HttpURLConnection.HTTP_OK:
                //Remove it to re-cache if HTTP has been modified.
                ProxyServer.getSiteMap().remove(urlName);
                inputStream = huc.getInputStream();
                break;
            //Sometimes Java cannot follow the 301/302 errors, so we manually handle those cases.
            case HttpURLConnection.HTTP_MOVED_PERM:
            case HttpURLConnection.HTTP_MOVED_TEMP:
                URL redirURL = new URL(huc.getHeaderField("Location"));
                this.urlConnection = (HttpURLConnection) redirURL.openConnection();
                return extractConnectionData(urlConnection);
            //Some webpages do not support this protocol, e.g. google/yahoo/bing
            case HttpURLConnection.HTTP_NOT_MODIFIED:
                inputStream = new ByteArrayInputStream(ProxyServer.getSiteMap().get(urlName).getContent());
                break;
            //"The client SHOULD NOT repeat the request without modifications" according to RFC 2616
            //Therefore we do not cache HTTP_BAD_REQUEST responses.
            case HttpURLConnection.HTTP_BAD_REQUEST:
                //The server does not support the functionality to fulfill the request
            case HttpURLConnection.HTTP_NOT_IMPLEMENTED:
                inputStream = huc.getErrorStream();
                break;
        }
        return inputStream;
    }

    public void run()
    {
        try
        {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            String inputLine;

            if ((inputLine = in.readLine()) != null)
            {
                // First line of request is GET http://bing.com HTTP/1.1
                urlName = inputLine.split(" ")[1];
            }

            urlConnection = (HttpURLConnection) new URL(urlName).openConnection();
            byte[] webpageData = getStreamOutput(extractConnectionData(urlConnection));


            if (!isCached(urlName))
            {
                cache(urlConnection, webpageData);
            }

            //Write to output (e.g. back to client)
            out.write(webpageData);
            out.close();
            in.close();
            if (socket != null)
            {
                socket.close();
            }

        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}