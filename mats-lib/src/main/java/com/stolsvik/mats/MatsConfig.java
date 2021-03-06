package com.stolsvik.mats;

/**
 * All of {@link MatsFactory}, {@link MatsEndpoint} and {@link MatsStage} have some configurable elements, provided by a
 * config instance, this is the top of that hierarchy.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsConfig {
    /**
     * To change the default concurrency of the Factory, or of the endpoint (which defaults to the concurrency of the
     * {@link MatsFactory}), or of the process stage (which defaults to the concurrency of the {@link MatsEndpoint}).
     * <p>
     * The default for the {@link MatsFactory} is the number of processors on the server it is running on, as determined
     * by {@link Runtime#availableProcessors()}.
     * <p>
     * Will only have effect before the {@link MatsStage} is started. Can be reset by stopping, setting, and restarting.
     * <p>
     * Setting to 0 will invoke default logic.
     *
     * @param numberOfThreads
     *            the number of consumers on the queue(s) for the processing stage(s). If set to 0, default-logic be in
     *            effect.
     * @return the config object, for method chaining.
     */
    MatsConfig setConcurrency(int numberOfThreads);

    /**
     * @return the number of consumers set up for this factory, or endpoint, or process stage. Will provide the default
     *         unless overridden by {@link #setConcurrency(int)} before start.
     */
    int getConcurrency();

    /**
     * @return whether the number provided by {@link #getConcurrency()} is using default-logic (as if the concurrency is
     *         set to 0) (<code>true</code>), or it if is set specifically (</code>false</code>).
     */
    boolean isConcurrencyDefault();

    /**
     * @return whether the MATS element has been started and not stopped. For the {@link MatsFactory}, it returns true
     *         if any of the endpoints return true. For {@link MatsEndpoint}s, it returns true if any stage is running.
     */
    boolean isRunning();

    /**
     * All three of {@link MatsFactory}, {@link MatsEndpoint} and {@link MatsStage} implements this interface.
     */
    interface StartStoppable {
        /**
         * This method is idempotent, calling it when the endpoint is already running has no effect.
         * <p>
         * Further documentation on extensions.
         */
        void start();

        /**
         * This method is idempotent, calling it when the endpoint is already running has no effect.
         * <p>
         * Further documentation on extensions.
         */
        void stop();
    }
}
