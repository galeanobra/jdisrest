package es.unex.jdisrest.config;

/**
 * An algorithm configuration that cannot be read or has a wrong value: a file that does not exist
 * or is not UTF-8, a malformed {@code key=value} override, a missing or misspelt key, a value out
 * of range, or operator parameters that the operator itself rejects.
 *
 * <p>The message names the offending key ({@code mutation.probability must be in [0, 1] or k/n,
 * got '1.5'}), so it can be shown to the user as it is: on the command line of a launcher, or as
 * the {@code 422} answer of {@code POST /api/v1/config}. It extends
 * {@link IllegalArgumentException}, so code that treats bad input generically needs no special
 * case.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public class InvalidConfigurationException extends IllegalArgumentException {

    /**
     * Creates the exception.
     *
     * @param message what is wrong, naming the key
     */
    public InvalidConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates the exception for a failure detected by other code, such as a jMetal operator
     * constructor that rejects the values of the file.
     *
     * @param message what is wrong, naming the key
     * @param cause   the original failure
     */
    public InvalidConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
