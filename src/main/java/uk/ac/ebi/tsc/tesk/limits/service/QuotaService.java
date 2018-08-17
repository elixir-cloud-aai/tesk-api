package uk.ac.ebi.tsc.tesk.limits.service;

import org.springframework.stereotype.Service;
import uk.ac.ebi.tsc.tesk.common.exception.QuotaExceededException;
import uk.ac.ebi.tsc.tesk.limits.data.GroupResourceUsage;
import uk.ac.ebi.tsc.tesk.limits.data.LimitType;

import java.time.Instant;

/**
 * @author aniewielska
 * @since 17/08/2018
 */
@Service
public class QuotaService {

    private final UsageService usageService;
    private final LimitsService limitsService;

    public QuotaService(UsageService usageService, LimitsService limitsService) {
        this.usageService = usageService;
        this.limitsService = limitsService;
    }

    public void checkUsageWithinLimitForUser(String userId) {
        if (userId == null) return;
        GroupResourceUsage limit = this.limitsService.getLimitForGroup(userId, LimitType.USER);
        GroupResourceUsage usage = this.usageService.getTaskStatisticsForUser(userId).toGroupResourceUsage(Instant.now(), false, false);
         if (usage.exceeds(limit)) {
             throw new QuotaExceededException("Exceeded: " + userId + "usage: " + usage.toString() + "limit: " + limit.toString());
         }
    }

    public void checkUsageWithinLimitForGroup(String groupName) {
        if (groupName == null) return;
        GroupResourceUsage limit = this.limitsService.getLimitForGroup(groupName, LimitType.GROUP);
        GroupResourceUsage usage = this.usageService.getTaskStatisticsForGroup(groupName).toGroupResourceUsage(Instant.now(), false, false);
        if (usage.exceeds(limit)) {
            throw new QuotaExceededException("Exceeded: " + groupName + "usage: " + usage.toString() + "limit: " + limit.toString());
        }
    }
}
