package uk.ac.ebi.tsc.tesk.limits.data;

import lombok.Builder;
import lombok.Data;

/**
 * @author aniewielska
 * @since 16/08/2018
 */
@Data
@Builder
public class ExecutorResourceUsage {

    private ExecutorStats data;
    private ResourceUsage resourceUsage;

}
