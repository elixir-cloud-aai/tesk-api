package uk.ac.ebi.tsc.tesk.config;

import com.google.gson.Gson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.ac.ebi.tsc.tesk.k8s.config.KubernetesJson;

/** @author Ania Niewielska <aniewielska@ebi.ac.uk> */
@Configuration
public class GsonConfig {

    @Bean
    Gson gson() {
        return KubernetesJson.GSON;
    }
}
