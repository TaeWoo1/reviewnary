package com.sellerops.vendor.review;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>An uploaded export never touches the disk</b> — contract §3.1. Through the real Tomcat: a test-only filter asks
 * the container to parse a multipart body of the largest allowed size, then lists the container's temp folder while
 * the request is still open — where Tomcat spools a part it does not keep in memory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UploadStaysInMemoryTest {

    private static final String PROBE = "/test-only/multipart-probe";

    @LocalServerPort
    private int port;

    @Test
    void theLargestAllowedFileIsParsedWithoutATempFile() throws Exception {
        String boundary = "probe-boundary";
        byte[] file = new byte[10 * 1024 * 1024 - 1024];
        Arrays.fill(file, (byte) 'x');
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"r.xlsx\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + file.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(file, 0, body, head.length, file.length);
        System.arraycopy(tail, 0, body, head.length + file.length, tail.length);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PROBE))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).as("part size · files spooled in the temp folder")
                .isEqualTo(file.length + " · 0");
    }

    @TestConfiguration
    static class Probe {
        @Autowired
        private ServletContext context;

        @Bean
        FilterRegistrationBean<Filter> multipartProbe() {
            Filter filter = (request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                if (!PROBE.equals(http.getRequestURI())) {
                    chain.doFilter(request, response);
                    return;
                }
                long size = http.getPart("file").getSize();
                File temp = (File) context.getAttribute(ServletContext.TEMPDIR);
                long spooled = Arrays.stream(Objects.requireNonNullElse(temp.listFiles(), new File[0]))
                        .filter(f -> f.getName().startsWith("upload_")).count();
                response.setContentType("text/plain");
                response.getWriter().write(size + " · " + spooled);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
