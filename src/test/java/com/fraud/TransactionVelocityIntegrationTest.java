package com.fraud;

import com.fraud.service.VelocityCheckingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.math.BigDecimal;
import com.fraud.dto.TransactionRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.http.HttpStatus;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionVelocityIntegrationTest {

    private static final String API_KEY = "integration-test-api-key-0123456789abcdef";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.security.api-key", () -> API_KEY);
    }

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private VelocityCheckingService velocityCheckingService;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void clearRedis() {
        connectionFactory.getConnection().serverCommands().flushDb();
    }

    @Test
    void allowsFiveTransactionsAndRejectsTheSixth() throws Exception {
        String accountId = "account-" + UUID.randomUUID().toString().replace("-", "");

        for (int transactionNumber = 1; transactionNumber <= 6; transactionNumber++) {
            HttpResponse<String> response = post(accountId, "txn-" + transactionNumber, true, 25.50);

            assertThat(response.statusCode())
                    .isEqualTo((transactionNumber <= 5 ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS).value());
            assertThat(response.body()).contains("\"currentCount\":" + transactionNumber);
            assertThat(response.body()).contains("\"limitExceeded\":" + (transactionNumber > 5));
        }
    }

    @Test
    void countsConcurrentTransactionsAtomically() throws Exception {
        String accountId = "account-" + UUID.randomUUID().toString().replace("-", "");
        var executor = Executors.newFixedThreadPool(12);
        try {
            var responses = IntStream.range(0, 12)
                    .mapToObj(index -> executor.submit(() -> post(
                            accountId, "concurrent-" + index, true, 25.50)))
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS).statusCode();
                        } catch (Exception exception) {
                            throw new IllegalStateException("Concurrent request failed", exception);
                        }
                    })
                    .toList();

            assertThat(responses.stream().filter(status -> status == HttpStatus.OK.value()).count()).isEqualTo(5);
            assertThat(responses.stream()
                    .filter(status -> status == HttpStatus.TOO_MANY_REQUESTS.value()).count()).isEqualTo(7);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requiresApiKeyForTransactionEvaluation() throws Exception {
        HttpResponse<String> response = post(
                "account-" + UUID.randomUUID().toString().replace("-", ""), "txn-unauthorized", false, 25.50);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void duplicateTransactionIdIsCountedOnlyOnce() {
        String accountId = "account-" + UUID.randomUUID().toString().replace("-", "");
        var request = transactionRequest(accountId, "same-txn", "25.50");
        var original = velocityCheckingService.checkTransactionVelocity(request);
        var duplicate = velocityCheckingService.checkTransactionVelocity(request);

        assertThat(original.currentCount()).isEqualTo(1);
        assertThat(original.duplicate()).isFalse();
        assertThat(duplicate.currentCount()).isEqualTo(1);
        assertThat(duplicate.duplicate()).isTrue();
    }

    @Test
    void rejectsReusingTransactionIdWithDifferentDetails() throws Exception {
        String accountId = "account-" + UUID.randomUUID().toString().replace("-", "");
        velocityCheckingService.checkTransactionVelocity(transactionRequest(accountId, "same-txn", "25.50"));

        HttpResponse<String> response = post(accountId, "same-txn", true, 30.00);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void rejectsInvalidRequestFields() throws Exception {
        HttpResponse<String> response = post("invalid account id", "txn-invalid", true, 25.50);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.body()).contains("errors");
    }

    private HttpResponse<String> post(
            String accountId, String transactionId, boolean includeApiKey, double amount) throws Exception {
        String body = """
                {"transactionId":"%s","accountId":"%s","amount":%.2f}
                """.formatted(transactionId, accountId, amount);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/transactions/evaluate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (includeApiKey) {
            request.header("X-API-Key", API_KEY);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static TransactionRequest transactionRequest(String accountId, String transactionId, String amount) {
        return new TransactionRequest(transactionId, accountId, new BigDecimal(amount), null);
    }
}
