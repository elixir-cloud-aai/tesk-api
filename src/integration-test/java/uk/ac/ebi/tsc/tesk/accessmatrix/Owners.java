package uk.ac.ebi.tsc.tesk.accessmatrix;

/**
 * The owner ID that TESK stamps on the tasks of a caller, for the tasks that the mock cluster
 * holds. Today that is the user info's {@code sub} (or the service client's ID); the redesign
 * stamps {@code <iss>|<sub>} (design section 3, step 11), which slice T3b switches here, in one
 * place. What TESK stamps on create is checked against the target format by {@code
 * AccessMatrixIT#create_stampsIssuerAndSubject}.
 */
final class Owners {

    private Owners() {}

    /** The owner ID of the subject {@code sub} of the issuer {@code iss} */
    static String of(String iss, String sub) {
        return sub;
    }

    /** The owner ID in the format of the redesign */
    static String target(String iss, String sub) {
        return iss + "|" + sub;
    }
}
