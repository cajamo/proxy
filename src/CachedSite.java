import java.io.Serializable;
import java.time.Instant;

public class CachedSite implements Serializable
{
    private Instant cachedTime;
    private byte[] content;
    private String etag;

    public CachedSite(byte[] content, Instant instant)
    {
        this.content = content;
        this.cachedTime = instant;
    }

    public CachedSite(byte[] content, Instant instant, String etag)
    {
        this.content = content;
        this.cachedTime = instant;
        this.etag = etag;
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
