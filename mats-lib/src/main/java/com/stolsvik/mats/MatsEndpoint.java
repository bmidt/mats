package com.stolsvik.mats;

import java.util.List;
import java.util.function.Consumer;

import com.stolsvik.mats.MatsConfig.StartStoppable;
import com.stolsvik.mats.MatsInitiator.InitiateLambda;
import com.stolsvik.mats.MatsInitiator.MatsInitiate;
import com.stolsvik.mats.MatsStage.StageConfig;
import com.stolsvik.mats.exceptions.MatsRefuseMessageException;

/**
 * Represents a MATS Endpoint.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsEndpoint<S, R> extends StartStoppable {

    /**
     * @return the config for this endpoint. If endpoint is not yet started, you may invoke mutators on it.
     */
    EndpointConfig<S, R> getEndpointConfig();

    /**
     * Adds a new stage to a multi-stage endpoint.
     *
     * @param <I>
     *            the type of the incoming DTO. The very first stage's incoming DTO is the endpoint's incoming DTO.
     * @param processor
     *            the lambda that will be invoked when messages arrive in the corresponding queue.
     */
    <I> MatsStage<I, S, R> stage(Class<I> incomingClass, ProcessLambda<I, S, R> processor);

    /**
     * Variation of {@link #stage(Class, ProcessLambda)} that can be configured "on the fly".
     */
    <I> MatsStage<I, S, R> stage(Class<I> incomingClass, Consumer<? super StageConfig<I, S, R>> stageConfigLambda,
            ProcessLambda<I, S, R> processor);

    /**
     * Adds the last stage to a multi-stage endpoint, which also starts the endpoint. Note that the last-stage concept
     * is just a convenience that lets the developer reply from the endpoint with a <code>return replyDTO</code>
     * statement - you may just as well add a standard stage, and invoke the {@link ProcessContext#reply(Object)} method
     * (and remember to start it, as that is then obviously not done automatically).
     *
     * @param <I>
     *            the type of the incoming DTO. The very first stage's incoming DTO is the endpoint's incoming DTO.
     * @param processor
     *            the lambda that will be invoked when messages arrive in the corresponding queue.
     */
    <I> MatsStage<I, S, R> lastStage(Class<I> incomingClass, ProcessReturnLambda<I, S, R> processor);

    /**
     * Variation of {@link #lastStage(Class, ProcessReturnLambda)} that can be configured "on the fly".
     */
    <I> MatsStage<I, S, R> lastStage(Class<I> incomingClass, Consumer<? super StageConfig<I, S, R>> stageConfigLambda,
            ProcessReturnLambda<I, S, R> processor);

    /**
     * Starts the endpoint, invoking {@link MatsStage#start()} on any not-yet started stages (which should be all of
     * them at application startup).
     * <p>
     * If the {@link MatsFactory} is stopped ("closed") when this method is invoked, the {@link MatsStage}s will not
     * start until the factory is started.
     */
    @Override
    void start();

    /**
     * Stops the endpoint, invoking {@link MatsStage#stop()} on all {@link MatsStage}s.
     */
    @Override
    void stop();

    /**
     * Provides for both configuring the endpoint (before it is started), and introspecting the configuration.
     */
    interface EndpointConfig<S, R> extends MatsConfig {
        /**
         * @return the class expected for incoming messages to this endpoint (decided by the first {@link MatsStage}).
         */
        Class<?> getIncomingMessageClass();

        /**
         * @return the class used for the endpoint's state.
         */
        Class<S> getStateClass();

        /**
         * @return the class that will be sent as reply for this endpoint.
         */
        Class<R> getReplyClass();

        /**
         * @return a List of {@link MatsStage}s, representing all the stages of the endpoint. The order is the same as
         *         the order in which the stages will be invoked. For single-staged endpoints and terminators, this list
         *         is of size 1.
         */
        List<MatsStage<?, S, R>> getStages();
    }

    /**
     * A way for the process stage to communicate with the library, providing methods to invoke a request, send a reply
     * (for multi-stage endpoints, this provides a way to do a "early return"), initiate a new message etc. Note that
     * the MATS-implementations might provide for specializations of this class - if you choose to cast down to that,
     * you tie into the implementation (e.g. JMS specific implementations might want to expose the underlying incoming
     * and outgoing {@code MapMessage}s.)
     */
    interface ProcessContext<R> {
        /**
         * @return the endpointId that is processed, i.e. the id of <i>this</i> endpoint. Should probably never be
         *         necessary, but accessible for introspection.
         */
        String getEndpointId();

        /**
         * @return the stageId that is processed, i.e. the id of <i>this</i> stage. It will be equal to
         *         {@link #getEndpointId()} for the first stage in multi-stage-endpoint, and for the sole stage of a
         *         single-stage and terminator endpoint. Should probably never be necessary, but accessible for
         *         introspection.
         */
        String getStageId();

        /**
         * @param key
         *            the key for which to retrieve a binary payload from the incoming message.
         * @return the requested byte array.
         * @see #getBytes(String)
         * @see #addString(String, String)
         * @see #getString(String)
         */
        byte[] getBytes(String key);

        /**
         * @param key
         *            the key for which to retrieve a String payload from the incoming message.
         * @return the requested String.
         * @see #getString(String)
         * @see #addBytes(String, byte[])
         * @see #getBytes(String)
         */
        String getString(String key);

        /**
         * Attaches a binary payload to the next outgoing message, being it a request or a reply. Note that for
         * initiations, you have the same method on the {@link MatsInitiate} instance.
         *
         * @param key
         *            the key on which to store the binary payload.
         * @param payload
         *            the payload to store.
         * @see #getBytes(String)
         * @see #addString(String, String)
         * @see #getString(String)
         */
        void addBytes(String key, byte[] payload);

        /**
         * Attaches a String payload to the next outgoing message, being it a request or a reply. Note that for
         * initiations, you have the same method on the {@link MatsInitiate} instance.
         *
         * @param key
         *            the key on which to store the String payload.
         * @param payload
         *            the payload to store.
         * @see #getString(String)
         * @see #addBytes(String, byte[])
         * @see #getBytes(String)
         */
        void addString(String key, String payload);

        /**
         * Adds a property that will "stick" with the {@link MatsTrace} from this call on out. Note that for
         * initiations, you have the same method on the {@link MatsInitiate} instance. The functionality effectively
         * acts like a {@link ThreadLocal} when compared to normal java method invocations: If the Initiator adds it,
         * all subsequent stages will see it, on any stack level, including the terminator. If a stage in a service
         * nested some levels down in the stack adds it, it will be present in all subsequent stages including all the
         * way up to the Terminator.
         * <p>
         * Possible use cases: You can for example "sneak along" some property meant for Service X through an invocation
         * of intermediate Service A (which subsequently calls Service X), where the signature (DTO) of the intermediate
         * Service A does not provide such functionality. Another usage would be to add some "global context variable",
         * e.g. "current user", that is available for any down-stream Service that requires it. Both of these scenarios
         * can obviously lead to pretty hard-to-understand code if used extensively: When employed, you should code
         * rather defensively, where if this property is not present when a stage needs it, it should throw
         * {@link MatsRefuseMessageException} and clearly explain that the property needs to be present.
         *
         * @param propertyName
         *            the name of the property
         * @param propertyValue
         *            the value of the property, which will be serialized using the active MATS serializer.
         * @see #getTraceProperty(String, Class)
         */
        void setTraceProperty(String propertyName, Object propertyValue);

        /**
         * Retrieves the {@link MatsTrace} property with the specified name, deserializing the value to the specified
         * class, using the active MATS serializer. Read more on {@link #setTraceProperty(String, Object)}.
         *
         * @param propertyName
         *            the name of the {@link MatsTrace} property to retrieve.
         * @param clazz
         *            the class to which the value should be deserialized.
         * @return the value of the {@link MatsTrace} property, deserialized as the specified class.
         * @see #setTraceProperty(String, Object)
         */
        <T> T getTraceProperty(String propertyName, Class<T> clazz);

        /**
         * @return the current {@link MatsTrace} (the one that invoked this {@link MatsStage}.
         */
        MatsTrace getTrace();

        /**
         * Sends a request message, meaning that the specified endpoint will be invoked, with the reply-to endpointId
         * set to the next stage in the multi-stage endpoint. This will throw if the current process stage is a
         * terminator, single-stage endpoint or the last endpoint of a multi-stage endpoint, as there then is no next
         * stage to reply to.
         *
         * @param endpointId
         *            which endpoint to invoke
         * @param requestDto
         *            the message that should be sent to the specified endpoint.
         */
        void request(String endpointId, Object requestDto);

        /**
         * Sends a reply to the requesting service. This will be ignored if there is no endpointId on the stack, which
         * obviously is the case if this is a terminator, but also if it is the last stage of an endpoint that was
         * invoked directly.
         * <p>
         * It is possible to do "early return" in a multi-stage endpoint by invoking this method in a stage that is not
         * the last. (You should then obviously not also invoke {@link #request(String, Object)} or
         * {@link #next(Object)} unless you have explicit handling of the messy result, either in the downward stages or
         * on the endpoint that might get two replies for one request).
         *
         * @param replyDto
         *            the reply DTO to return to the invoker.
         */
        void reply(R replyDto);

        /**
         * Invokes the next stage of a multi-stage endpoint directly, instead of going through a request-reply to some
         * service. The rationale for this method is that in certain situation you might not need to invoke some service
         * after all: Basically, you can do something like <code>if (condition) { request service } else { next }</code>
         * .
         *
         * @param incomingDto
         *            the object for the next stage's incoming DTO, which must match what the next stage expects. When
         *            using this method to skip a request, it probably often makes sense to set it to <code>null</code>,
         *            which the next stage then must handle correctly.
         */
        void next(Object incomingDto);

        /**
         * Initiates a new message out to an endpoint. This is effectively the same as invoking
         * {@link MatsInitiator#initiate(InitiateLambda lambda) the same method} on a {@link MatsInitiator} gotten via
         * {@link MatsFactory#getInitiator(String)}, only that this way works within the transactional context of the
         * {@link MatsStage} which this method is invoked within. Also, the traceId and from-endpointId is predefined,
         * but it is still recommended to set the traceId, as that will append the new string on the existing traceId,
         * making log tracking (e.g. when debugging) better.
         *
         * @param lambda
         *            provides the {@link MatsInitiate} instance on which to create the message to be sent.
         */
        void initiate(InitiateLambda lambda);
    }

    /**
     * The lambda that shall be provided by the developer for the process stage(s) for the endpoint - provides the
     * context, state and incoming message DTO.
     */
    @FunctionalInterface
    interface ProcessLambda<I, S, R> {
        void process(ProcessContext<R> processContext, I incomingDto, S state) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} that makes it possible to do a
     * "return replyDto" at the end of the stage, which is just a convenient way to invoke
     * {@link MatsEndpoint.ProcessContext#reply(Object)}. Used for the last process stage of a multistage endpoint.
     */
    @FunctionalInterface
    interface ProcessReturnLambda<I, S, R> {
        R process(ProcessContext<R> processContext, I incomingDto, S state) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} which does not have a state, and have the same
     * return-semantics as {@link MatsEndpoint.ProcessReturnLambda ProcessLambda} - used for single-stage endpoints as
     * these does not have multiple stages to transfer state between.
     * <p>
     * However, since it is possible to send state along with the request, one may still use the
     * {@link MatsEndpoint.ProcessReturnLambda ProcessReturnLambda} for single-stage endpoints, but in this case you
     * need to code it up yourself by making a multi-stage and then just adding a single lastStage.
     */
    @FunctionalInterface
    interface ProcessSingleLambda<I, R> {
        R process(ProcessContext<R> processContext, I incomingDto) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} which does not have reply specified - used for
     * terminator endpoints. It has state, as the initiator typically have state that it wants the terminator to get.
     */
    @FunctionalInterface
    interface ProcessTerminatorLambda<I, S> {
        void process(ProcessContext<Void> processContext, I incomingDto, S state) throws MatsRefuseMessageException;
    }
}
