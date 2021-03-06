package whelk.apix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLSocketFactory;

/**
 * The purpose of this class is to provide a low level http connection that uses a TCP socket with sockopt SO_KEEPALIVE.
 * This is NOT to be confused with the HTTP keep-alive header, which is a completely different beast.
 *
 * SO_KEEPALIVE compels the OS to send periodic out-of-band (with respect to the TCP stream) keep-alive probes with the
 * ACK flag set. If an ACK is not received from the other end of the connection in a timely manner, the connection is
 * considered dead and an exception is raised.
 *
 * The specific use case for this is requests to APIX which have been known to loose connections without a clean
 * connection shut down (essentially leaving a client hanging for ever). Normal timeouts are not a good way to deal with
 * this, as requests can sometimes _legitimately_ take as much 16 hours during a Voyager-regen.
 */
public class LongTermHttpRequest
{
    private int m_responseCode;
    private String m_responseData;
    private HashMap<String, String> m_responseHeaders;

    /**
     * contentType, data, basicAuthName and basicAuthPass may all be passed as null where they are not relevant.
     */
    public LongTermHttpRequest(String url, String verb, String contentType, String data,
                               String basicAuthName, String basicAuthPass)
            throws IOException
    {
        URL properUrl = new URL(url);
        int port = properUrl.getPort();
        if (port == -1)
            port = properUrl.getDefaultPort();
        if (port == -1)
            port = 80;

        try ( Socket socket = createSocket(properUrl.getProtocol(), properUrl.getHost(), port) )
        {
            socket.setKeepAlive(true); // Essentially the point of all this

            writeRequest( socket.getOutputStream(), properUrl.getHost(), properUrl.getPath(), verb, contentType,
                    data, basicAuthName, basicAuthPass );
            readResponse( socket.getInputStream() );
        }
    }

    public int getResponseCode()
    {
        return m_responseCode;
    }

    public String getResponseData()
    {
        return m_responseData;
    }

    public Map<String, String> getResponseHeaders()
    {
        return Collections.unmodifiableMap(m_responseHeaders);
    }

    private Socket createSocket(String protocol, String host, int port)
            throws IOException
    {
        if (protocol.equals("https"))
        {
            SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            return ssf.createSocket(host, port);
        }
        else
            return new Socket(host, port);
    }

    private void writeRequest(OutputStream outputStream, String host, String path, String verb, String contentType,
                              String data, String basicAuthName, String basicAuthPass)
            throws IOException
    {
        if (path.equals(""))
            path = "/";

        StringBuilder header = new StringBuilder();
        header.append( verb + " " + path + " HTTP/1.1\r\n" );
        header.append( "Host: " + host + "\r\n" );
        header.append( "Accept-Charset: utf-8\r\n" );
        header.append( "Connection: close\r\n" );

        if (basicAuthName != null && basicAuthPass != null)
        {
            Base64.Encoder encoder = Base64.getMimeEncoder(0, new byte[]{});
            String basicString = basicAuthName + ":" + basicAuthPass;
            String basicBase64 = new String(encoder.encode( basicString.getBytes("UTF-8")), Charset.forName("UTF-8"));
            header.append( "Authorization: Basic " + basicBase64 + "\r\n");
        }

        byte[] dataBytes = null;
        if (data != null)
        {
            dataBytes = data.getBytes(Charset.forName("UTF-8"));
            header.append( "Content-Type: " + contentType + "\r\n" );
            header.append( "Content-Length: " + dataBytes.length + "\r\n" );
        }
        header.append( "\r\n" );

        outputStream.write(header.toString().getBytes(Charset.forName("UTF-8")));

        if (dataBytes != null)
            outputStream.write(dataBytes);

        outputStream.flush();
    }

    private void readResponse(InputStream inputStream)
            throws IOException
    {
        ByteArrayOutputStream completeResponse = new ByteArrayOutputStream();
        byte[] buf = new byte[1024*8];

        int bytesRead = 0;
        do
        {
            completeResponse.write(buf, 0, bytesRead);
            bytesRead = inputStream.read(buf);
        } while (bytesRead != -1);

        // Response completely retrieved.
        String responseText = completeResponse.toString("UTF-8");
        int introEnd = responseText.indexOf("\r\n");
        if (introEnd == -1)
            throw new IOException("Malformed HTTP response, no intro line ending: " + responseText);
        String introLine = responseText.substring(0, introEnd);

        // The intro line should look something like: HTTP/1.1 200 OK
        if (!introLine.startsWith("HTTP/1."))
            throw new IOException("Malformed HTTP response, no 'HTTP/1.X': " + responseText);
        // Next tree chars should be the response code.
        m_responseCode = Integer.parseInt( introLine.substring(9, 12) );

        int headerEnd = responseText.indexOf("\r\n\r\n");
        if (headerEnd == -1)
            throw new IOException("Malformed HTTP response, no correct header ending: " + responseText);

        String headerString = responseText.substring(introEnd, headerEnd).trim();
        m_responseData = responseText.substring(headerEnd+3);

        String[] headerLines = headerString.split("\r\n");
        m_responseHeaders = new HashMap<String, String>();
        for (String headerLine : headerLines)
        {
            int delimiterIndex = headerLine.indexOf(':');
            if (delimiterIndex == -1)
                throw new IOException("Malformed HTTP response, bad header line: " + headerLine);
            m_responseHeaders.put(
                    headerLine.substring(0, delimiterIndex).trim(),
                    headerLine.substring(delimiterIndex+1, headerLine.length()).trim());
        }
    }
}
