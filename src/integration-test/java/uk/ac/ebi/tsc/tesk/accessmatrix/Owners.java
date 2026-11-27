package uk.ac.ebi.tsc.tesk.accessmatrix;

/**
 * The owner ID that TESK stamps on the tasks of a caller, for the tasks that the mock cluster
 * holds: {@code <iss>|<sub>} since slice T3b. What TESK stamps on create is checked against the
 * target format by {@code AccessMatrixIT#create_stampsIssuerAndSubject}.
 */
final class Owners {

    private Owners() {}

    /** The owner ID of the subject {@code sub} of the issuer {@code iss} */
    static String of(String iss, String sub) {
        return target(iss, sub);
    }

    /** The owner ID in the format of the redesign */
    static String target(String iss, String sub) {
        return iss + "|" + sub;
    }
}
