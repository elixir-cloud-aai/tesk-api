package uk.ac.ebi.tsc.tesk.config.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Authorisation without groups (issue #78): the setting {@code tesk.api.authorisation.claim.*}, the
 * claim check of {@link ElixirPrincipalExtractor} and the label selector of {@link User}
 */
public class ClaimAuthorisationTest {

    private static final String CLAIM = "eduperson_assurance";
    private static final String MEDIUM = "https://refeds.org/assurance/IAP/medium";
    private static final String HIGH = "https://refeds.org/assurance/IAP/high";

    private static AuthorisationProperties properties(
            boolean enabled, String name, String... values) {
        AuthorisationProperties properties = new AuthorisationProperties();
        properties.setBaseGroupPrefix("BASE_GROUP:");
        properties.setAdminSubgroup("ADMIN");
        properties.setAdminGroupFull("BASE_GROUP:ADMIN");
        properties.getClaim().setEnabled(enabled);
        properties.getClaim().setName(name);
        properties.getClaim().setValues(Arrays.asList(values));
        return properties;
    }

    private static User extract(
            AuthorisationProperties properties, Object claimValue, String... groups) {
        ElixirPrincipalExtractor extractor =
                new ElixirPrincipalExtractor(
                        new GroupNamesSecurityExtractor("groupNames", "whatever"), properties);
        Map<String, Object> map = new HashMap<>();
        map.put("sub", "user@example.org");
        if (claimValue != null) {
            map.put(CLAIM, claimValue);
        }
        if (groups.length > 0) {
            map.put("groupNames", Arrays.asList(groups));
        }
        return (User) extractor.extractPrincipal(map);
    }

    @Test
    public void enabled_claimValueInList() {
        User user = extract(properties(true, CLAIM, MEDIUM, HIGH), Arrays.asList("low", HIGH));
        assertTrue(user.isClaimAuthorised());
    }

    @Test
    public void enabled_claimValueAsString() {
        User user = extract(properties(true, CLAIM, MEDIUM, HIGH), MEDIUM);
        assertTrue(user.isClaimAuthorised());
    }

    @Test
    public void enabled_claimAbsent() {
        User user = extract(properties(true, CLAIM, MEDIUM), null);
        assertFalse(user.isClaimAuthorised());
    }

    @Test
    public void enabled_claimWrongValue() {
        assertFalse(
                extract(properties(true, CLAIM, MEDIUM), Arrays.asList("low", MEDIUM + "/x"))
                        .isClaimAuthorised());
        assertFalse(extract(properties(true, CLAIM, MEDIUM), "").isClaimAuthorised());
        assertFalse(
                extract(properties(true, CLAIM, MEDIUM), Collections.emptyList())
                        .isClaimAuthorised());
        // values are compared exactly, also in case
        assertFalse(
                extract(properties(true, CLAIM, MEDIUM), MEDIUM.toUpperCase()).isClaimAuthorised());
    }

    @Test
    public void disabled_claimValueIgnored() {
        User user = extract(properties(false, CLAIM, MEDIUM), MEDIUM);
        assertFalse(user.isClaimAuthorised());
    }

    /** Without the setting, the name and values need not be set */
    @Test
    public void disabled_noNameOrValues() {
        User user = extract(properties(false, null), MEDIUM);
        assertFalse(user.isClaimAuthorised());
    }

    @Test(expected = IllegalStateException.class)
    public void enabled_noName() {
        extract(properties(true, " ", MEDIUM), MEDIUM);
    }

    @Test(expected = IllegalStateException.class)
    public void enabled_noValues() {
        extract(properties(true, CLAIM), MEDIUM);
    }

    @Test(expected = IllegalStateException.class)
    public void enabled_onlyBlankValues() {
        extract(properties(true, CLAIM, ""), MEDIUM);
    }

    @Test(expected = IllegalStateException.class)
    public void enabled_nullValues() {
        AuthorisationProperties properties = properties(true, CLAIM);
        properties.getClaim().setValues(null);
        extract(properties, MEDIUM);
    }

    /** Configured values are trimmed, the claim's values aren't */
    @Test
    public void enabled_trimmedValues() {
        assertTrue(
                extract(properties(true, CLAIM, " " + MEDIUM + " "), MEDIUM).isClaimAuthorised());
        assertFalse(extract(properties(true, CLAIM, MEDIUM), " " + MEDIUM).isClaimAuthorised());
    }

    /** Values of other types than strings never match */
    @Test
    public void enabled_nonStringValues() {
        assertFalse(extract(properties(true, CLAIM, "1"), 1).isClaimAuthorised());
        assertFalse(
                extract(properties(true, CLAIM, "true"), Arrays.asList(true, 1))
                        .isClaimAuthorised());
    }

    /** Users differ by the claim, also in their hash code and text */
    @Test
    public void equalsHashCodeToString() {
        User user = User.builder("123").claimAuthorised(true).build();
        User same = User.builder("123").claimAuthorised(true).build();
        User other = User.builder("123").claimAuthorised(false).build();
        assertEquals(user, same);
        assertEquals(user.hashCode(), same.hashCode());
        assertNotEquals(user, other);
        assertNotEquals(user.hashCode(), other.hashCode());
        assertTrue(user.toString().contains("claimAuthorised=true"));
    }

    /** A user authorised by the claim only sees the own tasks without a group */
    @Test
    public void labelSelector_claimUser() {
        User user = extract(properties(true, CLAIM, MEDIUM), MEDIUM);
        assertEquals(
                "creator-user-hash=d159ef624ed86697b4f1f3ff086aacdd,!creator-group-name",
                user.getLabelSelector());
    }

    /** Admins see all tasks: no selector, also with the claim */
    @Test
    public void labelSelector_adminWithClaim() {
        User user = extract(properties(true, CLAIM, MEDIUM), MEDIUM, "BASE_GROUP:ADMIN");
        assertTrue(user.isTeskAdmin());
        assertTrue(user.isClaimAuthorised());
        assertNull(user.getLabelSelector());
    }

    /** Group members keep the selector of their groups, also with the claim */
    @Test
    public void labelSelector_memberWithClaim() {
        User user = extract(properties(true, CLAIM, MEDIUM), MEDIUM, "BASE_GROUP:TEST");
        assertEquals(
                "creator-group-name in (TEST),creator-user-hash=d159ef624ed86697b4f1f3ff086aacdd",
                user.getLabelSelector());
    }

    /** The setting tesk.api.authorisation.legacy-user-labels reaches the user (issue #119) */
    @Test
    public void legacyUserLabels_fromProperties() {
        AuthorisationProperties properties = properties(false, null);
        assertFalse(extract(properties, null).isLegacyUserLabels());
        properties.setLegacyUserLabels(true);
        assertTrue(extract(properties, null).isLegacyUserLabels());
    }
}
