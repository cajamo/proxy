import java.io.Serializable;
import java.time.Instant;

public class CachedSite implements Serializable {
    private Instant cachedTime;
    private byte[] content;

    public CachedSite(byte[] content, Instant instant) {
        this.content = content;
        this.cachedTime = instant;
    }

    public Instant getCachedTime() {
        return cachedTime;
    }

    public byte[] getContent() {
        return content;
    }
}
