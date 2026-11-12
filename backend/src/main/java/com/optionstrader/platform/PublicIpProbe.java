package com.optionstrader.platform;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Fixed HTTPS destination, bounded timeout, never logs credentials or network identifiers. */
@Service
public class PublicIpProbe {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public String current(){
        try{
            String ip=client.send(HttpRequest.newBuilder(URI.create("https://api4.ipify.org")).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofString()).body().trim();
            if(!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")||java.util.Arrays.stream(ip.split("\\.")).anyMatch(n->Integer.parseInt(n)>255))throw new IllegalStateException();
            return ip;
        }catch(Exception error){throw new IllegalStateException("Public IPv4 could not be verified. Real entries are blocked.");}
    }
}
