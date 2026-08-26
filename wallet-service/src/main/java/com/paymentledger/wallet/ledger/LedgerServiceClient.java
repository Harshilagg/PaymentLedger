package com.paymentledger.wallet.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Reads go direct over HTTP while writes stay asynchronous through the saga. That asymmetry is
 * deliberate: a read has no cross-service correctness requirement that needs saga semantics, and
 * going direct avoids inventing a query-side event stream purely to serve one endpoint. See
 * README.md.
 *
 * This client performs no authorization. Callers must have already established that the requester
 * is entitled to the transaction before asking for its entries.
 */
@Component
public class LedgerServiceClient {

    private final RestClient restClient;

    public LedgerServiceClient(RestClient.Builder builder,
                                @Value("${app.ledger-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public List<InternalLedgerEntry> fetchEntries(UUID transactionId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/ledger/entries")
                        .queryParam("transactionId", transactionId)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<InternalLedgerEntry>>() {
                });
    }
}
