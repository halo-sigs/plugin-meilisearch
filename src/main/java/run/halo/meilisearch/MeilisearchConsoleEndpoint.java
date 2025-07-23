package run.halo.meilisearch;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.IndexStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerErrorException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeilisearchConsoleEndpoint implements CustomEndpoint {

    private final ReactiveSettingFetcher reactiveSettingFetcher;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "MeilisearchConsoleV1alpha1";
        return SpringdocRouteBuilder.route()
            .GET("/stats", this::getStats, builder -> {
                builder.operationId("GetMeilisearchStats")
                    .description("Get Meilisearch index statistics")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(IndexStats.class));
            })
            .build();
    }

    private Mono<ServerResponse> getStats(ServerRequest request) {
        return reactiveSettingFetcher.fetch("basic", MeilisearchProperties.class)
            .flatMap(properties -> {
                var host = properties.getHost();
                var indexName = properties.getIndexName();

                if (host == null || host.isEmpty() || indexName == null || indexName.isEmpty()) {
                    return Mono.error(new ServerWebInputException("Meilisearch host or index name is not configured"));
                }

                return Mono.fromCallable(() -> getMeilisearchStats(properties))
                    .flatMap(stats -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(stats))
                    .onErrorResume(MeilisearchException.class, e -> Mono.error(new ServerErrorException("Failed to get Meilisearch stats", e)))
                    .onErrorResume(Exception.class, e -> Mono.error(new ServerErrorException("Unexpected error: " + e.getMessage(), e)));
            })
            .onErrorResume(e -> Mono.error(new ServerWebInputException("Failed to fetch Meilisearch configuration")));
    }

    private IndexStats getMeilisearchStats(MeilisearchProperties properties) throws MeilisearchException {
        var client = new Client(new Config(properties.getHost(), properties.getMasterKey()));
        var index = client.index(properties.getIndexName());
        return index.getStats();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.meilisearch.halo.run", "v1alpha1");
    }
}
