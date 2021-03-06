# MATS<sup>3</sup> - Message-based Asynchronous Transactional Staged Stateful Services

A library that facilitates the development of asynchronous, stateless, multi-stage, message-based services. A MATS service may do a request to another MATS service, which replies back, after possibly itself doing a request to a third service - with arbitrary nesting levels possible. Services consisting of multiple stages are connected with a *state object* which is passed along in the subsequent message flow, which simulates the "this" reference when compared to traditional synchronous programming. In addition, each stage is independently transactional, making a system made up of these services exceptionally resilient against any type of failure.

The API consist nearly solely of interfaces, not depending on any specific messaging platform's protocol or API. It can have implementations on several messaging platform, but the current sole implementation is employing Java Message Service API - JMS v1.1.

# Rationale

In a multi-service architecture (e.g. <i>Micro Services</i>) one needs to communicate between the different services. The golden hammer for such communication is REST services employing JSON over HTTP.

However, *Asynchronous Message Oriented Middleware architectures* are superior to synchronous REST-based systems in many ways, for example:

* **High Availability**: Can have listeners on several nodes (servers/containers) for each queue, so that if one goes down, the others are still processing.
* **Scalability**: Can [increase the number of nodes](http://www.reactivemanifesto.org/glossary#Replication) (or listeners per node) for a queue, thereby increasing throughput, without any clients needing reconfiguration.
* **Transactionality**: Each endpoint has either processed a message, done its DB-stuff, and sent a message, or none of it.
* **Resiliency** / **Fault Tolerance**: If a node goes down mid-way in processing, the transactional aspect kicks in and rolls back the processing, and another node picks up. Also, centralized retry.
* **[Location Transparency](http://www.reactivemanifesto.org/glossary#Location-Transparency)**: *Service Location Discovery* is avoided, as messages only targets the logical queue name, without needing information about which nodes are active for that endpoint.
* **Monitoring**: All messages pass by the Message Broker, and can be logged and recorded, and made statistics on, to whatever degree one wants. 
* **Debugging**: The messages are typically strings and JSON, and can be inspected centrally on the Message Broker.
* **Error handling**: Unprocessable messages (both in face of coded-for conditions and programming errors) will be refused and eventually put on a *Dead Letter Queue*, where they can be monitored, ops be alerted, and handled manually - centrally.

However, the big pain point with message-based communications is that to reap all these benefits, one need to fully embrace asynchronous, multi-staged processing, where each stage is totally stateless, but where one still needs to maintain a state throughout the flow. This is typically so hard to grasp, not to mention implement, that many projects choose to go for the much easier model of synchronous processing where one can code linearly, employing blocking calls out to other services that are needed - a model that every programmer intuitively know due to it closely mimicking local method invocations.

The main idea of this library is to let developers code message-based endpoints that themselves may "invoke" other such endpoints, in a manner that closely resembles a synchronous "straight down" linear code style where state will be kept through the flow (envision a plain Java method that invokes other methods, or more relevant, a REST service that invokes other REST services), but in fact ends up being a forward-only *"Pipes and Filters"* multi-stage, fully stateless, asynchronous distributed process.

Effectively, the mental model feels like home, and coding is really straight-forward, while you reap all the benefits of a Message Oriented Architecture.

# Examples

Some examples taken from the unit tests ("mats-lib-test"). Notice the use of the JUnit Rule *Rule_Mats*, which sets up an ActiveMQ in-memory server and creates a JMS-backed MatsFactory based on that.

In these examples, all the endpoints and stages are set up in one test class, and thus obviously runs on the same machine - but in actual usage, you would typically have each
endpoint run in a different process on different nodes. The DTO-classes, which acts as the interface between the different endpoints' requests and replies, would in real usage be copied between the projects (in this testing-scenario, one DTO class is used as all interfaces).

It is important to realize that each of the stages - even each stage in multi-stage endpoints - are handled by separate threads, *sharing no contextual JVM state whatsoever:* The state, request and reply objects' JVM references are not shared between stages. Each stage's processor receives a message, supplies the stage-lambda with a context-instance, the request-object, and the state-object (where both request and state is deserialized from the incoming MatsTrace), and then exits that lambda (after typically having sent a new message out), going back to listening for new messages for that stage. *There is no state shared between these stages outside of the contents of the MatsTrace which is being passed with the message.* In particular, if you have a process running a three-stage MATS endpoint which is running on two nodes A and B, the first stage might execute on A, while the next on B, and the last one on A again.

This means that what *looks like* a blocking, synchronous request/reply "method call" is actually fully asynchronous, where the reply will be handled in the next stage by a different thread, quite possibly on a different node if you have deployed the service in multiple instances.

## Simple send-receive

The following class exercises the simplest functionality: Sets up a Terminator endpoint, and then an initiator sends a message to that endpoint. *(This example does not demonstrate neither the stack (request/reply), nor state keeping - it is just the simplest possible message passing, sending some information from an initiator to a terminator endpoint)*

ASCII-artsy, it looks like this:
<pre>
[Initiator]   {sends to}
[Terminator]
</pre>

```java
public class Test_SimplestSendReceive extends AMatsTest {
    @Before
    public void setupTerminator() {
        matsRule.getMatsFactory().terminator(TERMINATOR, DataTO.class, StateTO.class,
                (context, dto, sto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.getTrace());
                    matsTestLatch.resolve(dto, sto);
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        DataTO dto = new DataTO(42, "TheAnswer");
        matsRule.getMatsFactory().getInitiator(INITIATOR).initiate(
                (msg) -> msg.traceId(randomId())
                        .from(INITIATOR)
                        .to(TERMINATOR)
                        .send(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(dto, result.getData());
    }
}
```

## Simple request to single stage "leaf-service"

Exercises the simplest request functionality: A single-stage service is set up. A Terminator is set up. Then an initiator does a request to the service, setting replyTo(Terminator). *(This example demonstrates one stack level request/reply, and state keeping between initiator and terminator)*

ASCII-artsy, it looks like this:
<pre>
[Initiator]   {request}
  [Service]   {reply}
[Terminator]
</pre>

```java
public class Test_SimplestServiceRequest extends AMatsTest {
    @Before
    public void setupService() {
        matsRule.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> new DataTO(dto.number * 2, dto.string + ":FromService"));
    }

    @Before
    public void setupTerminator() {
        matsRule.getMatsFactory().terminator(TERMINATOR, DataTO.class, StateTO.class,
                (context, dto, sto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.getTrace());
                    matsTestLatch.resolve(dto, sto);
                });

    }

    @Test
    public void doTest() throws InterruptedException {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        matsRule.getMatsFactory().getInitiator(INITIATOR).initiate(
                (msg) -> msg.traceId(randomId())
                        .from(INITIATOR)
                        .to(SERVICE)
                        .replyTo(TERMINATOR)
                        .request(dto, sto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }
}
```

## Multi-stage service and multi-level requests

Sets up a somewhat complex test scenario, testing request/reply message passing and state keeping between stages in several multi-stage endpoints, at different levels in the stack.

The main aspects of MATS are demonstrated here, notice in particular how the code of <code>setupMasterMultiStagedService()</code> *looks like* - if you squint a little - a linear "straight down" method with two "blocking requests" out to other services, where the last stage ends with a return statement, sending off the reply to whoever invoked it.
<p>
Sets up these services:
<ul>
<li>Leaf service: Single stage: Replies directly.
<li>Mid service: Two stages: Requests "Leaf" service, then replies.
<li>Master service: Three stages: First requests "Mid" service, then requests "Leaf" service, then replies.
</ul>
A Terminator is also set up, and then the initiator sends a request to "Master", setting replyTo(Terminator).
<p>
ASCII-artsy, it looks like this:
<pre>
[Initiator]              {request}
    [Master S0 (init)]   {request}
        [Mid S0 (init)]  {request}
            [Leaf]       {reply}
        [Mid S1 (last)]  {reply}
    [Master S1]          {request}
        [Leaf]           {reply}
    [Master S2 (last)]   {reply}
[Terminator]
</pre>
**Again, it is important to realize that the three stages of the Master service (and the two of the Mid service) are actually fully independent messaging endpoints (with their own JMS queue when run on a JMS backend), and if you've deployed the service to multiple nodes, each stage in a particular invocation flow might run on a different node.** What the MATS API does is to set up each stage of a multi-stage service in a way where the "reply" method invocation of a requested service knows which queue to send its result to.


```java
public class Test_ComplexMultiStage extends AMatsTest {
    @Before
    public void setupLeafService() {
        matsRule.getMatsFactory().single(SERVICE + ".Leaf", DataTO.class, DataTO.class,
                (context, dto) -> new DataTO(dto.number * 2, dto.string + ":FromLeafService"));
    }

    @Before
    public void setupMidMultiStagedService() {
        MatsEndpoint<StateTO, DataTO> ep = matsRule.getMatsFactory().staged(SERVICE + ".Mid",
                                                                            StateTO.class, DataTO.class);
        ep.stage(DataTO.class, (context, dto, sto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2);
            // Setting state some variables.
            sto.number1 = 10;
            sto.number2 = Math.PI;
            // Perform request to "Leaf Service".
            context.request(SERVICE + ".Leaf", dto);
        });
        ep.lastStage(DataTO.class, (context, dto, sto) -> {
            // .. "continuing" after the "Leaf Service" has replied.
            // Assert that state variables set in previous stage are still with us.
            Assert.assertEquals(10,      sto.number1);
            Assert.assertEquals(Math.PI, sto.number2);
            // Replying to calling service.
            return new DataTO(dto.number * 3, dto.string + ":FromMidService");
        });
    }

    @Before
    public void setupMasterMultiStagedService() {
        MatsEndpoint<StateTO, DataTO> ep = matsRule.getMatsFactory().staged(SERVICE,
                                                                            StateTO.class, DataTO.class);
        ep.stage(DataTO.class, (context, dto, sto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2);
            // Setting some state variables.
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            // Perform request to "Mid Service".
            context.request(SERVICE + ".Mid", dto);
        });
        ep.stage(DataTO.class, (context, dto, sto) -> {
            // .. "continuing" after the "Mid Service" has replied.
            // Assert that state variables set in previous stage are still with us.
            Assert.assertEquals(Integer.MAX_VALUE, sto.number1);
            Assert.assertEquals(Math.E,            sto.number2);
            // Changing the state variables.
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.E * 2;
            // Perform request to "Leaf Service".
            context.request(SERVICE + ".Leaf", dto);
        });
        ep.lastStage(DataTO.class, (context, dto, sto) -> {
            // .. "continuing" after the "Leaf Service" has replied.
            // Assert that state variables changed in previous stage are still with us.
            Assert.assertEquals(Integer.MIN_VALUE, sto.number1);
            Assert.assertEquals(Math.E * 2,        sto.number2);
            // Replying to calling service.
            return new DataTO(dto.number * 5, dto.string + ":FromMasterService");
        });
    }

    @Before
    public void setupTerminator() {
        matsRule.getMatsFactory().terminator(TERMINATOR, DataTO.class, StateTO.class,
                (context, dto, sto) -> {
                    // .. "continuing" after the request to "Master Service" from Initiator.
                    log.debug("TERMINATOR MatsTrace:\n" + context.getTrace());
                    matsTestLatch.resolve(dto, sto);
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        // Send request to "Master Service", specifying reply to "Terminator".
        StateTO sto = new StateTO(420, 420.024);  // State object for "Terminator".
        DataTO dto = new DataTO(42, "TheAnswer"); // Request object to "Master Service".
        matsRule.getMatsFactory().getInitiator(INITIATOR).initiate(
                (msg) -> msg.traceId(randomId())
                        .from(INITIATOR)
                        .to(SERVICE)
                        .replyTo(TERMINATOR)
                        .request(dto, sto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        // Asserting that the state object which the Terminator got is equal to the one we sent.
        Assert.assertEquals(sto, result.getState());
        // Asserting that the final result has passed through all the services' stages.
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 2 * 5,
                                       dto.string + ":FromLeafService" + ":FromMidService"
                                                  + ":FromLeafService" + ":FromMasterService"),
                            result.getData());
    }
}
```

# Try it out!

If you want to try this out in your project, I will support you!

-Endre, endre@stolsvik.com
