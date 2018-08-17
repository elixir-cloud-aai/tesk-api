package uk.ac.ebi.tsc.tesk.common.data;

/**
 * @author aniewielska
 * @since 17/08/2018
 */
public class ValidationConstants {

    /**
     * Pattern to validate paths
     */
    public static final String ABSOLUTE_PATH_REGEXP = "^\\/.*";

    /**
     * Message for absolute path validation (to avoid message.properties)
     */
    public static final String ABSOLUTE_PATH_MESSAGE = "must be an absolute path";
}
