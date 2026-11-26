package uk.ac.ebi.tsc.tesk.accessmatrix;

import static uk.ac.ebi.tsc.tesk.accessmatrix.Entitlements.ADMIN;
import static uk.ac.ebi.tsc.tesk.accessmatrix.Entitlements.BASE;
import static uk.ac.ebi.tsc.tesk.accessmatrix.Entitlements.EOC_COMPUTE;
import static uk.ac.ebi.tsc.tesk.accessmatrix.Entitlements.OTHER_SUBGROUP;

import java.util.List;
import java.util.function.Supplier;
import org.jose4j.jwt.JwtClaims;
import uk.ac.ebi.tsc.tesk.accessmatrix.MockIdp.Signature;
import uk.ac.ebi.tsc.tesk.accessmatrix.MockIdp.Token;

/**
 * The callers of the matrix's rows: one or more variants per row (e.g. row 12's four kinds of
 * invalid tokens), each with its token, made when a test runs, and the identity that owns its
 * tasks.
 */
final class Callers {

    /** The owner of the foreign tasks */
    static final String SOMEONE_ELSE = "someone-else";

    /** The team (subgroup of the base group) of users' tasks today */
    static final String TEAM = "EOC-Compute";

    /** A caller: its matrix row, variant ID (the row's number and a letter, if more than one) */
    record Caller(
            int row,
            String id,
            String what,
            String sub,
            Supplier<Token> token,
            MockPdp.Mode pdp,
            int userInfoDelayMs) {

        /**
         * The team of the caller's tasks, as TESK assigns it today: users created theirs as members
         * of EOC-Compute (also those who are no longer members), service clients without a team
         */
        String team() {
            return row == 8 || row == 9 ? null : TEAM;
        }

        /** The owner ID of the caller's tasks (of the subject for exchanged tokens) */
        String owner() {
            return sub == null ? null : Owners.of(MockIdp.ISSUER, sub);
        }

        @Override
        public String toString() {
            return id + " (" + what + ")";
        }
    }

    private static final List<String> MEMBER = List.of(BASE, EOC_COMPUTE);
    private static final List<String> BASE_ONLY = List.of(BASE);

    private static final List<Caller> CALLERS =
            List.of(
                    caller(1, "1", "no token", null, () -> null),
                    caller(
                            2,
                            "2",
                            "only the base group",
                            "user-2",
                            () -> user("user-2", BASE_ONLY)),
                    caller(3, "3", "EOC-Compute", "user-3", () -> user("user-3", MEMBER)),
                    caller(
                            4,
                            "4",
                            "EOC-Compute revoked",
                            "user-4",
                            () -> user("user-4", BASE_ONLY)),
                    caller(5, "5", "EOC-Compute", "user-5", () -> user("user-5", MEMBER)),
                    caller(
                            6,
                            "6a",
                            "ADMIN only",
                            "user-6a",
                            () -> user("user-6a", List.of(BASE, ADMIN))),
                    caller(
                            6,
                            "6b",
                            "another subgroup only",
                            "user-6b",
                            () -> user("user-6b", List.of(BASE, OTHER_SUBGROUP))),
                    caller(
                            7,
                            "7",
                            "ADMIN and EOC-Compute",
                            "user-7",
                            () -> user("user-7", List.of(BASE, ADMIN, EOC_COMPUTE))),
                    caller(
                            8,
                            "8",
                            "listed client",
                            MockIdp.LISTED_CLIENT,
                            () -> client(MockIdp.LISTED_CLIENT, MockIdp.LISTED_CLIENT)),
                    // valid for TESK: an audience that TESK accepts (LS Login's client tokens have
                    // the client's own ID, which TESK accepts for listed clients only)
                    caller(
                            9,
                            "9",
                            "unlisted client",
                            MockIdp.UNLISTED_CLIENT,
                            () -> client(MockIdp.UNLISTED_CLIENT, MockIdp.FRONT_END)),
                    // a member, so that only the audience is wrong
                    caller(
                            10,
                            "10",
                            "audience of another service",
                            "user-10",
                            () -> {
                                JwtClaims claims = MockIdp.userClaims("user-10");
                                claims.setAudience("other-service");
                                return MockIdp.accepted(
                                        MockIdp.sign(claims, Signature.VALID, "at+jwt"),
                                        "user-10",
                                        MEMBER);
                            }),
                    caller(
                            11,
                            "11",
                            "unknown issuer",
                            "user-11",
                            () ->
                                    MockIdp.answered(
                                            MockIdp.signAsOtherIssuer(
                                                    MockIdp.userClaims("user-11")),
                                            401)),
                    caller(
                            12,
                            "12a",
                            "bad signature",
                            "user-12",
                            () -> invalid(MockIdp.userClaims("user-12"), Signature.FORGED)),
                    caller(
                            12,
                            "12b",
                            "alg none",
                            "user-12",
                            () -> invalid(MockIdp.userClaims("user-12"), Signature.NONE)),
                    caller(
                            12,
                            "12c",
                            "unknown kid",
                            "user-12",
                            () -> invalid(MockIdp.userClaims("user-12"), Signature.UNKNOWN_KID)),
                    caller(
                            12,
                            "12d",
                            "ID token",
                            "user-12",
                            () -> {
                                JwtClaims claims = MockIdp.userClaims("user-12");
                                claims.setClaim("nonce", "n-0S6_WzA2Mj");
                                claims.unsetClaim("scope");
                                return MockIdp.answered(
                                        MockIdp.sign(claims, Signature.VALID, "JWT"), 401);
                            }),
                    caller(
                            13,
                            "13",
                            "expired",
                            "user-13",
                            () ->
                                    MockIdp.answered(
                                            MockIdp.sign(
                                                    MockIdp.expired(MockIdp.userClaims("user-13")),
                                                    Signature.VALID,
                                                    "at+jwt"),
                                            401)),
                    caller(
                            14,
                            "14",
                            "exchanged, member, trusted actor",
                            "user-14",
                            () -> exchanged("user-14", MockIdp.TRUSTED_ACTOR, MEMBER)),
                    caller(
                            15,
                            "15",
                            "exchanged, no EOC-Compute, trusted actor",
                            "user-15",
                            () -> exchanged("user-15", MockIdp.TRUSTED_ACTOR, BASE_ONLY)),
                    caller(
                            16,
                            "16",
                            "exchanged, member, untrusted actor",
                            "user-16",
                            () -> exchanged("user-16", MockIdp.UNTRUSTED_ACTOR, MEMBER)),
                    caller(
                            17,
                            "17",
                            "user with a listed client's ID",
                            MockIdp.LISTED_CLIENT,
                            () -> user(MockIdp.LISTED_CLIENT, MEMBER)),
                    new Caller(
                            18,
                            "18a",
                            "decision service down",
                            "user-18",
                            () -> user("user-18", MEMBER),
                            MockPdp.Mode.DOWN,
                            0),
                    new Caller(
                            18,
                            "18b",
                            "decision service times out",
                            "user-18",
                            () -> user("user-18", MEMBER),
                            MockPdp.Mode.TIMEOUT,
                            0),
                    new Caller(
                            18,
                            "18c",
                            "decision service answers no JSON",
                            "user-18",
                            () -> user("user-18", MEMBER),
                            MockPdp.Mode.BAD_ANSWER,
                            0),
                    caller(
                            19,
                            "19a",
                            "user info answers 503",
                            "user-19",
                            () -> MockIdp.answered(signedUser("user-19"), 503)),
                    // longer than TESK's timeout of the lookup in the tests (1 s), shorter than
                    // today's read timeout of the user info endpoint (30 s)
                    new Caller(
                            19,
                            "19b",
                            "user info times out",
                            "user-19",
                            () -> user("user-19", MEMBER),
                            MockPdp.Mode.REFERENCE,
                            3000));

    private Callers() {}

    static List<Caller> all() {
        return CALLERS;
    }

    static List<Caller> ofRow(int row) {
        return CALLERS.stream().filter(caller -> caller.row() == row).toList();
    }

    static Caller byId(String id) {
        return CALLERS.stream()
                .filter(caller -> caller.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No caller " + id));
    }

    private static Caller caller(
            int row, String id, String what, String sub, Supplier<Token> token) {
        return new Caller(row, id, what, sub, token, MockPdp.Mode.REFERENCE, 0);
    }

    private static Token user(String sub, List<String> entitlements) {
        return MockIdp.user(sub, entitlements);
    }

    private static String signedUser(String sub) {
        return MockIdp.sign(MockIdp.userClaims(sub), Signature.VALID, "at+jwt");
    }

    /** A token of the client credentials grant; LS Login's user info endpoint answers 403 */
    private static Token client(String clientId, String audience) {
        JwtClaims claims = MockIdp.clientClaims(clientId);
        claims.setAudience(audience);
        return MockIdp.answered(MockIdp.sign(claims, Signature.VALID, "at+jwt"), 403);
    }

    /** A token that the provider didn't sign (validly), which its user info endpoint refuses */
    private static Token invalid(JwtClaims claims, Signature signature) {
        return MockIdp.answered(MockIdp.sign(claims, signature, "at+jwt"), 401);
    }

    /** An exchanged token; the user info endpoint answers with the subject's entitlements */
    private static Token exchanged(String sub, String actor, List<String> entitlements) {
        return MockIdp.accepted(
                MockIdp.sign(MockIdp.exchangedClaims(sub, actor), Signature.VALID, "at+jwt"),
                sub,
                entitlements);
    }
}
