package com.tngtech.jenkins.notification;

import com.tngtech.jenkins.notification.endpoints.MissileEndpoint;
import com.tngtech.jenkins.notification.endpoints.TrafficLightEndpoint;
import com.tngtech.jenkins.notification.endpoints.TtsEndpoint;
import com.tngtech.jenkins.notification.model.Config;
import com.tngtech.jenkins.notification.status.BuildJobsStatusHolder;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.SimpleRegistry;
import org.apache.camel.main.Main;
import org.apache.camel.model.MulticastDefinition;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.apache.camel.processor.idempotent.FileIdempotentRepository.fileIdempotentRepository;

public class CamelApplication extends Main {
    public static final String ENTRY_TO_BUILD_INFO_BEAN = "entryToBuildInfo";

    public static final String MISSILE_ENDPOINT = "missileEndpoint";
    public static final String TTS_ENDPOINT = "ttsEndpoint";
    public static final String TRAFFIC_LIGHT_ENDPOINT = "trafficLightEndpoint";
    public static final String BUILD_JOB_STATUS_HOLDER = "buildJobStatusHolder";
    private Config config;

    CamelApplication(Config config) {
        this.config = config;
    }

    @Override
    public void run() throws Exception {
        CamelContext camelContext = createCamelContext();
        camelContexts.add(camelContext);
        super.run();
    }

    protected CamelContext createCamelContext() throws Exception {

        SimpleRegistry registry = new SimpleRegistry();
        registry.put(ENTRY_TO_BUILD_INFO_BEAN, new EntryToBuildInfo(new BuildInfoViaRestProvider()));
        registry.put(MISSILE_ENDPOINT, new MissileEndpoint(config.getMissile()));
        registry.put(TTS_ENDPOINT, new TtsEndpoint(config.getTts()));
        registry.put(TRAFFIC_LIGHT_ENDPOINT, new TrafficLightEndpoint(config.getTrafficLight()));
        registry.put(BUILD_JOB_STATUS_HOLDER, new BuildJobsStatusHolder());

        CamelContext context = new DefaultCamelContext(registry);

        context.addRoutes(createRoutes());

        return context;
    }

    protected RouteBuilder createRoutes() throws Exception {
        return new RouteBuilder() {
            public void configure() throws Exception {
                Date date = new Date();
                if (config.isHandleInitialEntries()) {
                    date.setTime(0);
                }
                String dateString = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(date);

                fromF("atom:%s?splitEntries=true&lastUpdate=%s&throttleEntries=false&consumer.delay=%d",
                        config.getRssFeedUrl(), dateString, config.getPollInterval())
                        .id("atom")
                        .idempotentConsumer(simple("${body.id}"), fileIdempotentRepository(new File("initialRepo")))
                        .toF("bean:%s", ENTRY_TO_BUILD_INFO_BEAN)
                        .toF("bean:%s", BUILD_JOB_STATUS_HOLDER)
                        .to("log:com.tngtech.jenkins.notification?showAll=true&multiline=true")
                        .to("seda:feeds");

                List<String> endpoints = new ArrayList<>();
                for (String endpoint: config.getFeedbackDevices()) {
                    endpoints.add(String.format("bean:%sEndpoint", endpoint));
                }
                MulticastDefinition multicast = from("seda:feeds").multicast();
                if (config.isFeedbackInParallel()) {
                    multicast.parallelProcessing();
                }
                multicast.to(endpoints.toArray(new String[endpoints.size()]));
            }
        };
    }
}
