package es.unex.jdisrest.distributed.rest;

/**
 * Reads and changes the configuration of the running algorithm for {@link ConfigController}
 * ({@code GET} and {@code POST /api/v1/config}). The program that builds the algorithm registers
 * one with {@link MasterFacade#setConfigurationHandler}, because only it knows how a
 * configuration maps onto the algorithm; {@link es.unex.jdisrest.config.AlgorithmReconfiguration}
 * is the one for configuration files.
 *
 * <p>Both methods are called off the event loop, on REST worker threads, possibly at the same time
 * as the algorithm runs and as each other, so implementations must be thread-safe. {@link #apply}
 * may take its time, but should not block on the progress of the run.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public interface ConfigurationHandler {

    /**
     * The configuration in use, as the text of a properties file that can be edited and sent back
     * to {@link #apply}.
     *
     * @return the text
     */
    String current();

    /**
     * Validates a new configuration and applies it to the running algorithm, recording the change
     * in the traces. The exception says how {@link ConfigController} answers.
     *
     * @param properties the text of the new properties file; empty, never {@code null}, for an
     *                   empty request body
     * @return a one-line description of the configuration now in use
     * @throws IllegalArgumentException with the reason, if the configuration is wrong or changes
     *                                  something that cannot change while the run goes on
     *                                  ({@code 422}); nothing is applied then
     * @throws IllegalStateException    with the reason, if the run cannot take a change any more,
     *                                  for instance because it has been stopped ({@code 409});
     *                                  nothing is applied then
     */
    String apply(String properties);
}
