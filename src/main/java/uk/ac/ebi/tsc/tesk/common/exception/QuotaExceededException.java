package uk.ac.ebi.tsc.tesk.common.exception;

/**
 * @author aniewielska
 * @since 17/08/2018
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
