package run.halo.meilisearchengine;

import lombok.Data;

@Data
public class MeilisearchProperties {

    private String host;
    
    private String masterKey;
    
    private String indexName;
} 