package com.habitcoach.strands;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the Spring Boot <-> Strands HTTP contract (POST /generate, GET
 * /health) without needing the real Python process running — a
 * MockRestServiceServer stands in for it. The real end-to-end call is
 * verified separately by actually running both processes (see the manual
 * verification steps in the scaffold report).
 */
class StrandsClientTest {

    @Test
    void generateReturnsTextFromStrandsService() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        StrandsProperties properties = new StrandsProperties();
        properties.setBaseUrl("http://localhost:8900");
        StrandsClient client = new StrandsClient(builder, properties);

        server.expect(requestTo("http://localhost:8900/generate"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(
                        "{\"text\":\"Day 1 of 21. Just focus on gym today.\",\"source\":\"strands\"}",
                        MediaType.APPLICATION_JSON));

        StrandsResponse response = client.generate("encouragement", Map.of("name", "gym", "day", 1));

        assertThat(response.text()).isEqualTo("Day 1 of 21. Just focus on gym today.");
        assertThat(response.source()).isEqualTo("strands");
        server.verify();
    }

    @Test
    void generateThrowsStrandsUnavailableOnServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        StrandsProperties properties = new StrandsProperties();
        properties.setBaseUrl("http://localhost:8900");
        StrandsClient client = new StrandsClient(builder, properties);

        server.expect(requestTo("http://localhost:8900/generate"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.generate("encouragement", Map.of()))
                .isInstanceOf(StrandsUnavailableException.class);
    }

    @Test
    void isHealthyReturnsFalseWhenServiceUnreachable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        StrandsProperties properties = new StrandsProperties();
        properties.setBaseUrl("http://localhost:8900");
        StrandsClient client = new StrandsClient(builder, properties);

        server.expect(requestTo("http://localhost:8900/health"))
                .andRespond(withServerError());

        assertThat(client.isHealthy()).isFalse();
    }
}
