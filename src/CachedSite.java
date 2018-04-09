import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class CachedSite
{
    private Instant cachedTime;

    private boolean mustRevalidate;
    private String etag;
    private long maxAge;

    private byte[] content;

    public CachedSite(byte[] content, Instant instant, String etag, boolean mustRevalidate, long maxAge)
    {
        this.content = content;
        this.cachedTime = instant;
        this.etag = etag;
        this.mustRevalidate = mustRevalidate;
        this.maxAge = maxAge;
    }

    public boolean isFresh()
    {
        return !mustRevalidate && cachedTime.plus(maxAge, ChronoUnit.SECONDS).isAfter(Instant.now());
    }

    public String getEtag()
    {
        return etag;
    }

    public void setEtag(String etag)
    {
        this.etag = etag;
    }

    public Instant getCachedTime()
    {
        return cachedTime;
    }

    public byte[] getContent()
    {
        return content;
    }
}
