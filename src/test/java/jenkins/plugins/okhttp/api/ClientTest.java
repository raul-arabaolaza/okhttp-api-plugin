package jenkins.plugins.okhttp.api;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import hudson.ProxyConfiguration;
import io.jenkins.jenkins.plugins.okhttp.api.JenkinsOkHttpClient;
import io.jenkins.jenkins.plugins.okhttp.api.OkHttpFuture;
import io.jenkins.jenkins.plugins.okhttp.api.internals.JenkinsProxySelector;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Rule
    public WireMockRule server = new WireMockRule(options().dynamicPort());

    @Rule
    public WireMockRule proxy = new WireMockRule(options()
            .dynamicPort()
            .dynamicHttpsPort());

    @Test
    public void testHappyPath() throws ExecutionException, InterruptedException {
        server.stubFor(
                get("/").willReturn(ok("Hello"))
        );

        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final Request request = new Request.Builder().get().url(server.baseUrl()).build();

        final OkHttpFuture<Response> future = new OkHttpFuture<>(client.newCall(request), (call, response) -> response);
        final Response response = future.get();

        assertTrue("Request to " + request.url() + " isn't successful", response.isSuccessful());
    }

    @Test
    public void testInexistingUrl() throws ExecutionException, InterruptedException {
        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final Request request = new Request.Builder().get().url("https://jenkins.io-does-not-exist").build();

        Optional<Response> futureResponse = new OkHttpFuture<>(client.newCall(request), (call, response) -> Optional.of(response))
                .exceptionally(ex -> Optional.empty())
                .get();
        assertFalse("A response was sent while none was expected due to calling a non existing URL", futureResponse.isPresent());
    }

    @Test
    public void testSimulatingListeners() throws ExecutionException, InterruptedException {
        server.stubFor(
                get("/").willReturn(ok("Hello"))
        );

        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final Request request = new Request.Builder().get().url(server.baseUrl()).build();

        CompletableFuture<Optional<Response>> futureResponse = new OkHttpFuture<>(client.newCall(request), (call, response) -> Optional.of(response))
                .exceptionally(ex -> Optional.empty());

        final CompletableFuture<Optional<String>> listener1 = futureResponse.thenApply(response -> Optional.of("Listener 1 OK"));
        final CompletableFuture<Optional<String>> listener2 = futureResponse.thenApply(response -> Optional.of("Listener 2 OK"));

        assertEquals("Listener 1 OK", listener1.get().get());
        assertEquals("Listener 2 OK", listener2.get().get());
    }

    @Test
    public void testTimeout() throws ExecutionException, InterruptedException {
        server.stubFor(
                get("/").willReturn(WireMock.aResponse().withFixedDelay(5000).withBody("Hello"))
        );

        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .build();
        final Request request = new Request.Builder().get().url(server.baseUrl()).build();

        final CompletableFuture<Optional<Throwable>> future = new OkHttpFuture<>(client.newCall(request), (call, response) -> Optional.<Throwable>empty())
                .exceptionally(throwable -> Optional.of(throwable));

        final Optional<Throwable> response = future.get();

        assertTrue(response.isPresent());
        assertEquals(SocketTimeoutException.class, response.get().getClass());
    }

    /**
     * Indicates {@link #proxy} is secured and requires authentication.
     *
     * @param authorizedUserPass "userName:password" style authorized users.
     */
    private void secureProxy(final String ... authorizedUserPass) {
        proxy.stubFor(WireMock.get(WireMock.anyUrl())
                .willReturn(aResponse()
                        .withStatus(407) // 407: Proxy Authentication Required
                        .withHeader("Proxy-Authenticate", "Basic")));

        if (authorizedUserPass != null) {
            for (String userPass : authorizedUserPass) {
                final String basicAuthenticationHeaderValue = "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
                proxy.stubFor(WireMock.get(WireMock.anyUrl())
                        .withHeader("Proxy-Authorization", WireMock.equalTo(basicAuthenticationHeaderValue))
                        .willReturn(WireMock.ok("Hello from proxy")));
            }
        }
    }

    @Test
    public void testProxy() throws ExecutionException, InterruptedException, IOException {
        jenkinsRule.jenkins.setProxy(new ProxyConfiguration("127.0.0.1", proxy.port(), "proxy-user", "proxy-pass"));
        secureProxy("proxy-user:proxy-pass");
        server.stubFor(WireMock.get("/hello").willReturn(aResponse().proxiedFrom(proxy.baseUrl())));

        final OkHttpClient client = JenkinsOkHttpClient.newClientBuilder(new OkHttpClient())
                .build();
        final Request request = new Request.Builder().get().url(server.url("/hello")).build();

        final CompletableFuture<Optional<Response>> future = new OkHttpFuture<>(client.newCall(request), (call, response) -> Optional.of(response));

        final Optional<Response> response = future.get();

        assertTrue(response.isPresent());
        assertEquals(200, response.get().code());
        try (final ResponseBody body = response.get().body()) {
            assertEquals("Hello from proxy", body.string());
        }
    }

    @Test
    public void testProxySelector() throws URISyntaxException {
        final String noProxyHosts = new StringJoiner("|")
                .add("1.2.3.4")
                .add("*.jenkins.io")
                .add("my*.super-jenkins.io")
                .toString();

        final ProxyConfiguration configuration = new ProxyConfiguration("127.0.0.1", proxy.port(), null, null, noProxyHosts);
        final JenkinsProxySelector proxySelector = new JenkinsProxySelector(configuration);

        final URI[] urisThatShouldNotUseProxy = {
                new URI("http://1.2.3.4"),
                new URI("http://hello-from.jenkins.io"),
                new URI("http://another.hello.from.jenkins.io"),
                new URI("http://my-wonderful.super-jenkins.io")
        };

        for (final URI uri : urisThatShouldNotUseProxy) {
            final List<Proxy> proxies = proxySelector.select(uri);
            assertEquals("Not only one proxy returned for URI " + uri,1, proxies.size());
            assertEquals("A proxy different from NO_PROXY is sent for " + uri, Proxy.NO_PROXY, proxies.get(0));
        }
    }
}
