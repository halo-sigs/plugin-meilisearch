package run.halo.meilisearch;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class Settings {

    private String host;

    private String key;

    private String indexes;


}
