/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import static org.springframework.http.HttpHeaders.HOST;
import org.springframework.beans.factory.annotation.Value;

@RestController
final class Controller {

    @Value("${VALID_IPS:}")
    private String ValidIPs;

    static final String FORWARDED_URL = "X-CF-Forwarded-Url";

    static final String PROXY_METADATA = "X-CF-Proxy-Metadata";

    static final String PROXY_SIGNATURE = "X-CF-Proxy-Signature";

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final WebClient webClient;

    Controller(WebClient webClient) {
        this.webClient = webClient;
    }

    @RequestMapping(headers = {FORWARDED_URL, PROXY_METADATA, PROXY_SIGNATURE})
    Mono<ResponseEntity<Flux<DataBuffer>>> service(ServerHttpRequest request) {

        this.logger.info("Incoming Request:  {}", formatRequest(request.getMethod(), request.getURI().toString(), request.getHeaders(), ""));

        String forwardedUrl = getForwardedUrl(request.getHeaders());
        HttpHeaders forwardedHttpHeaders = getForwardedHeaders(request.getHeaders());

        List<String> forwardedIPs = getForwardedIPs(request.getHeaders());
        List<String> list = Arrays.asList(ValidIPs.replaceAll("\\s+","").split(","));

        if (list.stream().anyMatch(s -> forwardedIPs.contains(s))) {

          this.logger.info("Outgoing Request:  {}", formatRequest(request.getMethod(), forwardedUrl, forwardedHttpHeaders, forwardedIPs.toString()));
          return this.webClient
              .method(request.getMethod())
              .uri(forwardedUrl)
              .headers(headers -> headers.putAll(forwardedHttpHeaders))
              .body((outputMessage, context) -> outputMessage.writeWith(request.getBody()))
              .exchange()
              .map(response -> {
                  this.logger.info("Outgoing Response: {}", formatResponse(response.statusCode(), response.headers().asHttpHeaders()));

                  return ResponseEntity
                      .status(response.statusCode())
                      .headers(response.headers().asHttpHeaders())
                      .body(response.bodyToFlux(DataBuffer.class));
              });
        } else {
              this.logger.info("Client not authorized! {}", forwardedIPs.toString());
              DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
              DataBuffer bodybuffer = dataBufferFactory.wrap("Access is not allowed!".getBytes(StandardCharsets.UTF_8));

              return Mono.just(
                  ResponseEntity
                  .status(HttpStatus.FORBIDDEN)
                  .body(Flux.just(bodybuffer))
                  );
          }
    }

    private static String formatRequest(HttpMethod method, String uri, HttpHeaders headers, String forwardedIPs) {
        return String.format("%s %s, %s, IPs: %s", method, uri, headers, forwardedIPs);
    }

    private static String getForwardedUrl(HttpHeaders httpHeaders) {
        String forwardedUrl = httpHeaders.getFirst(FORWARDED_URL);

        if (forwardedUrl == null) {
            throw new IllegalStateException(String.format("No %s header present", FORWARDED_URL));
        }

        return forwardedUrl;
    }

    private static List<String> getForwardedIPs(HttpHeaders httpHeaders) {
        String forwardedIPs = httpHeaders.getFirst(FORWARDED_FOR);

        if (forwardedIPs == null) {
            throw new IllegalStateException(String.format("No %s header present", FORWARDED_FOR));
        }

        return Arrays.asList(forwardedIPs.replaceAll("\\s+","").split(","));
    }

    private String formatResponse(HttpStatus statusCode, HttpHeaders headers) {
        return String.format("%s, %s", statusCode, headers);
    }

    private HttpHeaders getForwardedHeaders(HttpHeaders headers) {
        return headers.entrySet().stream()
            .filter(entry -> !entry.getKey().equalsIgnoreCase(FORWARDED_URL) && !entry.getKey().equalsIgnoreCase(HOST))
            .collect(HttpHeaders::new, (httpHeaders, entry) -> httpHeaders.addAll(entry.getKey(), entry.getValue()), HttpHeaders::putAll);
    }

}
