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
    private static final int BUFFER_SIZE = 2 << 24;
    //Since each thread is a unique HTTP connection, we have instance variables.
    private HttpURLConnection urlConnection;
    private String urlName;
    private boolean shouldCache = false;

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
        boolean revalidate = false;
        long maxAge = 0;

        String etag = huc.getHeaderField("ETag");
        String cacheControl = huc.getHeaderField("Cache-Control");

        if (cacheControl != null)
        {
            String[] cacheControlArr = cacheControl.split(", ");

            for (String option : cacheControlArr)
            {
                if (option.contains("max-age"))
                {
                    maxAge = Long.valueOf(option.substring(8));
                }
                switch (option)
                {
                    // This breaks out of cache method, and does not cache anything.
                    case "no-store":
                    case "private":
                        return;
                    case "public":
                        break;
                    case "must-revalidate":
                    case "proxy-revalidate":
                        revalidate = true;
                    default:
                        break;
                }
            }
        }
        CachedSite cachedSite = new CachedSite(websiteContent, Instant.now(), etag, revalidate, maxAge);
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
        // If it gets to this point and is cached, we must revalidate.
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
                inputStream = huc.getInputStream();
                break;
            case HttpURLConnection.HTTP_MOVED_PERM:
                shouldCache = true;
                URL redirURL = new URL(huc.getHeaderField("Location"));
                this.urlConnection = (HttpURLConnection) redirURL.openConnection();
                return extractConnectionData(urlConnection);
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

            // If is cached, and is fresh, just directly write the cached version to the user.
            if (isCached(urlName) && ProxyServer.getSiteMap().get(urlName).isFresh())
            {
                out.write(getStreamOutput(new ByteArrayInputStream(ProxyServer.getSiteMap().get(urlName).getContent())));
                out.close();
                in.close();
                return;
            }

            urlConnection = (HttpURLConnection) new URL(urlName).openConnection();
            byte[] webpageData = getStreamOutput(extractConnectionData(urlConnection));

            cache(urlConnection, webpageData);

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