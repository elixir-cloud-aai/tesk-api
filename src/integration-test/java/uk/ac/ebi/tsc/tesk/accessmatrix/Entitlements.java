package uk.ac.ebi.tsc.tesk.accessmatrix;

/**
 * The entitlements (eduperson_entitlement) of LS Login's groups that the matrix uses: the base
 * group {@code elixir:ECP_CLN}, which grants nothing (D-388), its subgroup {@code EOC-Compute}, the
 * only one that grants creating tasks, and two other subgroups.
 */
final class Entitlements {

    static final String PREFIX = "urn:geant:lifescience-ri.eu:group:";
    static final String SUFFIX = "#aai.lifescience-ri.eu";
    static final String BASE_GROUP = "elixir:ECP_CLN";

    static final String BASE = PREFIX + BASE_GROUP + SUFFIX;
    static final String EOC_COMPUTE = PREFIX + BASE_GROUP + ":EOC-Compute" + SUFFIX;
    static final String ADMIN = PREFIX + BASE_GROUP + ":ADMIN" + SUFFIX;
    static final String OTHER_SUBGROUP = PREFIX + BASE_GROUP + ":NVFlare" + SUFFIX;

    private Entitlements() {}
}
