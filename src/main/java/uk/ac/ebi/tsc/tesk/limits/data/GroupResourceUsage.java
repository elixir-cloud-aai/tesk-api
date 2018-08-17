package uk.ac.ebi.tsc.tesk.limits.data;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * @author aniewielska
 * @since 13/08/2018
 */
@Data
@Builder
public class GroupResourceUsage {

    private String name;
    private LimitType type;
    private ResourceUsage resourceUsage;
    private List<TaskResourceUsage> tasks;

    public boolean exceeds(GroupResourceUsage limit) {
        return resourceUsage.exceeds(limit.resourceUsage);
    }

}
