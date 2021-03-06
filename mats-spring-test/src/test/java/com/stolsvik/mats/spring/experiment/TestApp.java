package com.stolsvik.mats.spring.experiment;

import java.util.List;

import javax.inject.Inject;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.spring.test.SpringTestDataTO;
import com.stolsvik.mats.spring.test.SpringTestStateTO;
import com.stolsvik.mats.test.MatsTestLatch.Result;

@Component
public class TestApp {
    @Inject
    private TestMatsEndpoint _testBean;

    @Inject
    private List<BeanPostProcessor> _beanPostProcessors;

    @Inject
    private MatsFactory _matsFactory;

    public void run() {
        System.out.println("Hello " + _testBean.getHello());
        System.out.println("Hello " + _beanPostProcessors);

        SpringTestDataTO dto = new SpringTestDataTO(Math.PI, "Data");
        SpringTestStateTO sto = new SpringTestStateTO(256, "State");
        SpringTestStateTO requstSto = new SpringTestStateTO(3, "RequestState");
        _matsFactory.getInitiator("Endre").initiate(
                msg -> msg.traceId("TraceId")
                        .from("FromId")
                        .to(TestMatsEndpoint.ENDPOINT_ID + ".SingleWithState")
                        .replyTo(TestMatsEndpoint.ENDPOINT_ID + ".Terminator")
                        .request(dto, sto, requstSto));

        Result<SpringTestDataTO, SpringTestStateTO> result = _testBean._latch.waitForResult();
        System.out.println("Reply: " + result.getData());
        System.out.println("State: " + result.getState());
    }
}
