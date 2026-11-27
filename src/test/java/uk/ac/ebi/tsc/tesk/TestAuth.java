package uk.ac.ebi.tsc.tesk;

/**
 * Settings of an accepted issuer for tests that start TESK API with authentication (the default
 * profile), which refuses to start without one. The issuer is never asked for its keys unless a
 * test sends a token.
 */
public final class TestAuth {

    /** An issuer that no test sends tokens of */
    public static final String ISSUER = "https://issuer.example/";

    /** The issuer, as a property of {@code @SpringBootTest} or {@code @TestPropertySource} */
    public static final String ISSUER_URL = "tesk.api.auth.issuers[0].url=" + ISSUER;

    /** Its audiences: the client ID of a front end */
    public static final String AUDIENCES = "tesk.api.auth.issuers[0].audiences=tesk-portal";

    private TestAuth() {}
}
