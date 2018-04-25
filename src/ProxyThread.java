import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ProxyThread extends Thread
{
    private static final int BUFFER_SIZE = 2 << 24;
    private static final int DEFAULT_CACHE_TIME = 86400;

    private SimpleDateFormat httpTime = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);

    private Socket socket;
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
        httpTime.setTimeZone(TimeZone.getTimeZone("GMT"));
        return httpTime.format(new Date(instant.toEpochMilli()));
    }

    private Instant fromHttpTimeFormat(String date)
    {
        try
        {
            return httpTime.parse(date).toInstant();
        } catch (ParseException e)
        {
            e.printStackTrace();
        }
        return null;
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

    /**
     * According to RFC2616
     * A response received with a status code of 200, 203, 206, 300, 301 or 410 MAY
     * be stored by a cache and used in reply to a subsequent request,
     * subject to the expiration mechanism, unless a cache-control directive
     * prohibits caching.
     *
     * @param urlResponseCode The Response code
     * @return Whether the URL is cacheable
     */
    private boolean returnCodeOk(int urlResponseCode)
    {
        switch (urlResponseCode)
        {
            case HttpURLConnection.HTTP_OK:
            case HttpURLConnection.HTTP_NOT_AUTHORITATIVE:
            case HttpURLConnection.HTTP_MULT_CHOICE:
            case HttpURLConnection.HTTP_MOVED_PERM:
            case HttpURLConnection.HTTP_GONE:
            case HttpURLConnection.HTTP_NOT_MODIFIED:
                return true;
            default:
                return false;
        }
    }

    private void conditionalCache(HttpURLConnection huc, byte[] websiteContent)
    {
        boolean revalidate = false;
        long maxAge = -1;

        String cacheControl = huc.getHeaderField("Cache-Control");

        if (cacheControl != null)
        {
            String[] cacheControlArr = cacheControl.split(", ");

            for (String option : cacheControlArr)
            {
                if (option.contains("max-age"))
                {
                    // the max-age substring is 7 characters long
                    maxAge = Long.valueOf(option.substring(8));
                }

                switch (option)
                {
                    // This breaks out of conditionalCache method, and does not conditionalCache anything.
                    case "no-store":
                    case "no-cache":
                    case "private":
                        return;
                    case "must-revalidate":
                    case "proxy-revalidate":
                        revalidate = true;
                    case "public":
                    default:
                        continue;
                }
            }
        }

        if (maxAge == -1)
        {
            maxAge = findExpireTime(huc.getHeaderField("Date"), huc.getHeaderField("Expires"),
                    huc.getHeaderField("Last-Modified"));
        }

        String etag = huc.getHeaderField("ETag");

        CachedSite cachedSite = new CachedSite(websiteContent, Instant.now(), etag, revalidate, maxAge);
        ProxyServer.getSiteMap().put(urlName, cachedSite);
    }

    /**
     * Finds length of time before conditionalCache of website goes stale
     *
     * @param date         The HTTP Header "Date"
     * @param expires      The HTTP Header "Expires"
     * @param lastModified The HTTP Header "Last-Modified"
     * @return Number of seconds until expiration.
     */
    private int findExpireTime(String date, String expires, String lastModified)
    {
        Instant dateInstant, expiresInstant, lastModInstant = null;

        dateInstant = date == null ? Instant.now() : fromHttpTimeFormat(date);
        expiresInstant = expires == null ? null : fromHttpTimeFormat(expires);
        lastModInstant = lastModified == null ? null : fromHttpTimeFormat(lastModified);

        if (expiresInstant != null && dateInstant != null)
        {
            return (int) Duration.between(dateInstant, expiresInstant).getSeconds();
        }
        else if (expiresInstant == null && dateInstant != null && lastModInstant != null)
        {
            return (int) Duration.between(lastModInstant, dateInstant).getSeconds() / 10;
        }
        else
        {
            return DEFAULT_CACHE_TIME;
        }
    }

    /**
     * Connects to a given URL.
     *
     * @param huc HTTP Connection that user wants to extract data from
     * @return Returns InputStream of the webpage content.
     */
    private InputStream getWebpageContent(HttpURLConnection huc) throws IOException
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

        InputStream inputStream;
        switch (huc.getResponseCode())
        {
            case HttpURLConnection.HTTP_OK:
                inputStream = huc.getInputStream();
                break;
            case HttpURLConnection.HTTP_MOVED_PERM:
                //Manually redirect to new webpage
                URL redirURL = new URL(huc.getHeaderField("Location"));
                this.urlConnection = (HttpURLConnection) redirURL.openConnection();
                return getWebpageContent(urlConnection);
            case HttpURLConnection.HTTP_NOT_MODIFIED:
                inputStream = new ByteArrayInputStream(ProxyServer.getSiteMap().get(urlName).getContent());
                break;
            case HttpURLConnection.HTTP_BAD_REQUEST:
            case HttpURLConnection.HTTP_NOT_IMPLEMENTED:
            default:
                try
                {
                    inputStream = huc.getInputStream();
                } catch (IOException e)
                {
                    inputStream = huc.getErrorStream();
                }
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

            // Else it opens a connection to the url
            urlConnection = (HttpURLConnection) new URL(urlName).openConnection();
            byte[] webpageData = getStreamOutput(getWebpageContent(urlConnection));

            // Check response is even cacheable
            if (returnCodeOk(urlConnection.getResponseCode()))
            {
                conditionalCache(urlConnection, webpageData);
            }
            else if (urlConnection.getResponseCode() > 399)
            {
                String output = "HTTP/1.1 " + urlConnection.getResponseCode() + urlConnection.getResponseMessage();
                out.write(output.getBytes());
                out.close();
                in.close();
                return;
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