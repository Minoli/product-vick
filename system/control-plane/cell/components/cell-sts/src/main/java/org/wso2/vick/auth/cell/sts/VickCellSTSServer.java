package org.wso2.vick.auth.cell.sts;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.vick.auth.cell.sts.context.store.UserContextStore;
import org.wso2.vick.auth.cell.sts.context.store.UserContextStoreImpl;
import org.wso2.vick.auth.cell.sts.model.config.CellStsConfiguration;
import org.wso2.vick.auth.cell.sts.service.VickCellInboundInterceptorService;
import org.wso2.vick.auth.cell.sts.service.VickCellOutboundInterceptorService;
import org.wso2.vick.auth.cell.sts.service.VickCellSTSException;
import org.wso2.vick.auth.cell.sts.service.VickCellStsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Intercepts outbound calls from micro service proxy.
 */
public class VickCellSTSServer {

    private static final String CELL_NAME_ENV_VARIABLE = "CELL_NAME";
    private static final String STS_CONFIG_PATH_ENV_VARIABLE = "CONF_PATH";
    private static final String CONFIG_FILE_PATH = "/etc/config/sts.json";

    private static final String CONFIG_STS_ENDPOINT = "endpoint";
    private static final String CONFIG_AUTH_USERNAME = "username";
    private static final String CONFIG_AUTH_PASSWORD = "password";

    private static final Logger log = LoggerFactory.getLogger(VickCellSTSServer.class);
    private final int inboundListeningPort;
    private final Server inboundListener;

    private final int outboundListeningPort;
    private final Server outboundListener;

    private VickCellSTSServer(int inboundListeningPort, int outboundListeningPort) throws VickCellSTSException {

        CellStsConfiguration stsConfig = buildCellStsConfiguration();
        log.info("Cell STS configuration:\n" + stsConfig);

        UserContextStore contextStore = new UserContextStoreImpl();

        VickCellStsService cellStsService = new VickCellStsService(stsConfig, contextStore);

        this.inboundListeningPort = inboundListeningPort;
        inboundListener = ServerBuilder.forPort(inboundListeningPort)
                .addService(new VickCellInboundInterceptorService(cellStsService))
                .build();

        this.outboundListeningPort = outboundListeningPort;
        outboundListener = ServerBuilder.forPort(outboundListeningPort)
                .addService(new VickCellOutboundInterceptorService(cellStsService))
                .build();
    }


    private CellStsConfiguration buildCellStsConfiguration() throws VickCellSTSException {

        try {
            String configFilePath = getConfigFilePath();
            String content = new String(Files.readAllBytes(Paths.get(configFilePath)));
            JSONObject config = (JSONObject) new JSONParser().parse(content);

            return new CellStsConfiguration()
                    .setCellName(getMyCellName())
                    .setStsEndpoint((String) config.get(CONFIG_STS_ENDPOINT))
                    .setUsername((String) config.get(CONFIG_AUTH_USERNAME))
                    .setPassword((String) config.get(CONFIG_AUTH_PASSWORD));
        } catch (ParseException | IOException e) {
            throw new VickCellSTSException("Error while setting up STS configurations", e);
        }
    }

    private String getConfigFilePath() {

        String configPath = System.getenv(STS_CONFIG_PATH_ENV_VARIABLE);
        return StringUtils.isNotBlank(configPath) ? configPath : CONFIG_FILE_PATH;
    }

    private String getMyCellName() throws VickCellSTSException {
        // For now we pick the cell name from the environment variable.
        String cellName = System.getenv(CELL_NAME_ENV_VARIABLE);
        if (StringUtils.isBlank(cellName)) {
            throw new VickCellSTSException("Environment variable '" + CELL_NAME_ENV_VARIABLE + "' is empty.");
        }
        return cellName;
    }

    /**
     * Start serving requests.
     */
    private void start() throws IOException {

        inboundListener.start();
        outboundListener.start();
        log.info("Vick Cell STS gRPC Server started, listening for inbound traffic on " + inboundListeningPort);
        log.info("Vick Cell STS gRPC Server started, listening for outbound traffic on " + outboundListeningPort);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may has been reset by its JVM shutdown hook.
            System.err.println("Shutting down Vick Cell STS since JVM is shutting down.");
            VickCellSTSServer.this.stop();
            System.err.println("Vick Cell STS shut down.");
        }));
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    private void stop() {

        if (inboundListener != null) {
            inboundListener.shutdown();
        }

        if (outboundListener != null) {
            outboundListener.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {

        if (inboundListener != null) {
            inboundListener.awaitTermination();
        }

        if (outboundListener != null) {
            outboundListener.awaitTermination();
        }
    }

    public static void main(String[] args) {

        VickCellSTSServer server;
        try {
            server = new VickCellSTSServer(8080, 8081);
            server.start();
            server.blockUntilShutdown();
        } catch (Exception e) {
            log.error("Error while starting up the Cell STS.", e);
            // To make the pod go to CrashLoopBackOff state if we encounter any error while starting up
            System.exit(1);
        }
    }

}
