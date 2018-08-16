package uk.ac.ebi.tsc.tesk.limits.data;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author aniewielska
 * @since 16/08/2018
 */
@Data
@Builder
public class TaskResourceUsage {

    private String taskName;
    private BigDecimal storageGB;
    private ResourceUsage resourceUsage;
    private List<ExecutorResourceUsage> executors;

}
