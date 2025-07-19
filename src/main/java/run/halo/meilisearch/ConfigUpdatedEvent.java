package run.halo.meilisearch;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ConfigUpdatedEvent extends ApplicationEvent {

    private final MeilisearchProperties meilisearchProperties;

    public ConfigUpdatedEvent(Object source, MeilisearchProperties meilisearchProperties) {
        super(source);
        this.meilisearchProperties = meilisearchProperties;
    }

}