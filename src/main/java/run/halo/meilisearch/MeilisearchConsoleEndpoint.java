package run.halo.meilisearch;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

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
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeilisearchConsoleEndpoint implements CustomEndpoint {

    private final MeilisearchSearchEngine searchEngine;

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
        return Mono.fromCallable(searchEngine::getStats)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(stats -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(stats))
            .onErrorResume(e -> {
                if (e instanceof IllegalStateException) {
                    return Mono.error(new ServerWebInputException(e.getMessage()));
                }
                if (e instanceof MeilisearchException) {
                    return Mono.error(new ServerErrorException("Failed to get Meilisearch stats",
                        e));
                }
                return Mono.error(new ServerErrorException("Unexpected error: " + e.getMessage(),
                    e));
            });
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.meilisearch.halo.run", "v1alpha1");
    }
}
