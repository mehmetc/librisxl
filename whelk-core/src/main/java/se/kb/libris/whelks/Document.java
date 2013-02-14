package se.kb.libris.whelks;

import java.io.InputStream;
import java.net.URI;
import java.util.Date;
import java.util.Set;

public interface Document {
    public URI getIdentifier();
    public void setIdentifier(URI identifier);
    public String getVersion();
    public Date getTimestampAsDate();
    public long getTimestamp();
    public Document updateTimestamp();

    public byte[] getData();
    public byte[] getData(long offset, long length);
    public String getDataAsString();
    public InputStream getDataAsStream();
    public InputStream getDataAsStream(long offset, long length);
    public void setVersion(String version);
    public void setTimestamp(long timestamp);

    //public void setData(byte data[]);
    //public void setData(InputStream data);
    public String getContentType();
    public void setContentType(String contentType);
    public String getFormat();
    public void setFormat(String format);
    public long getSize();

    public Document withIdentifier(String uri);
    public Document withIdentifier(URI uri);
    public Document withData(String dataString);
    public Document withData(byte[] data);
    public Document withSize(long size);
    public Document withContentType(String contentType);
    public Document withFormat(String format);

    public Set<Link> getLinks();
    public Set<Key> getKeys();
    public Set<Description> getDescriptions();
    public Set<Tag> getTags();
    public String toJson();

    public Document tag(URI type, String value);
    public void untag(URI type, String value);

    public Document withLink(Link link);
    public Document withLink(String identifier);
    public Document withLink(URI identifier);
    public Document withLink(String identifier, String type);
    public Document withLink(URI identifier, String type);
}
