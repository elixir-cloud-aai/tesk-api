package uk.ac.ebi.tsc.tesk.config.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration of group based authorisation, and of the optional authorisation by a claim without
 * groups ({@link Claim})
 *
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@Configuration
@ConfigurationProperties(prefix = "tesk.api.authorisation")
public class AuthorisationProperties {
    /** The name of the claim in userInfo that contains user's group membership */
    private String groupsClaim;

    /** The prefix before actual group name */
    private String groupPrefix;
    /** The suffix after actual group name */
    private String groupSuffix = "";

    /**
     * Full name of base group required to perform any operation (prefix + parentGroup + envSubgroup
     * + suffix)
     */
    private String baseGroupFull;
    /** (Prefix) + Base group name + delimiter (to use as a prefix to even more nested groups) */
    private String baseGroupPrefix;
    /** Subgroup representing admins of its parent group */
    private String adminSubgroup;

    /** Full name of a group representing admins of a single installation of TESK */
    private String adminGroupFull;

    /** Authorisation without groups, by a claim of the user info (off by default) */
    private final Claim claim = new Claim();

    /**
     * Whether tasks created before the label creator-user-hash, which only have the label
     * creator-user-id (the creator's ID with @ replaced by _), are matched to their creators by
     * that label (off by default; issue #119). For a transition after an upgrade: the old encoding
     * gives different IDs the same value.
     */
    private boolean legacyUserLabels = false;

    /**
     * Authorisation without groups (issue #78): if enabled, users whose user info has one of the
     * values in the claim may create tasks without a group, and see, get and cancel their own tasks
     * without a group. Group members and admins keep their rights.
     */
    public static class Claim {
        /** Whether the claim authorises users without groups */
        private boolean enabled = false;
        /** Name of the claim in the user info, e.g. eduperson_assurance */
        private String name;
        /** Values of the claim, one of which a user needs (compared exactly) */
        private List<String> values = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values;
        }
    }

    public Claim getClaim() {
        return claim;
    }

    public boolean isLegacyUserLabels() {
        return legacyUserLabels;
    }

    public void setLegacyUserLabels(boolean legacyUserLabels) {
        this.legacyUserLabels = legacyUserLabels;
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public void setGroupsClaim(String groupsClaim) {
        this.groupsClaim = groupsClaim;
    }

    public String getGroupPrefix() {
        return groupPrefix;
    }

    public void setGroupPrefix(String groupPrefix) {
        this.groupPrefix = groupPrefix;
    }

    public String getGroupSuffix() {
        return groupSuffix;
    }

    public void setGroupSuffix(String groupSuffix) {
        this.groupSuffix = groupSuffix;
    }

    public String getBaseGroupFull() {
        return baseGroupFull;
    }

    public void setBaseGroupFull(String baseGroupFull) {
        this.baseGroupFull = baseGroupFull;
    }

    public String getAdminSubgroup() {
        return adminSubgroup;
    }

    public void setAdminSubgroup(String adminSubgroup) {
        this.adminSubgroup = adminSubgroup;
    }

    public String getBaseGroupPrefix() {
        return baseGroupPrefix;
    }

    public void setBaseGroupPrefix(String baseGroupPrefix) {
        this.baseGroupPrefix = baseGroupPrefix;
    }

    public String getAdminGroupFull() {
        return adminGroupFull;
    }

    public void setAdminGroupFull(String adminGroupFull) {
        this.adminGroupFull = adminGroupFull;
    }
}
