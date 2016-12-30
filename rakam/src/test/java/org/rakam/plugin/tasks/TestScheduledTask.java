package org.rakam.plugin.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import io.netty.channel.epoll.Epoll;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.rakam.TestingConfigManager;
import org.rakam.analysis.InMemoryApiKeyService;
import org.rakam.analysis.InMemoryEventStore;
import org.rakam.analysis.InMemoryMetastore;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.metadata.SchemaChecker;
import org.rakam.collection.FieldDependencyBuilder;
import org.rakam.collection.FieldType;
import org.rakam.collection.JSCodeLoggerService;
import org.rakam.collection.JsonEventDeserializer;
import org.rakam.collection.util.JSCodeCompiler;
import org.rakam.config.JDBCConfig;
import org.rakam.plugin.RAsyncHttpClient;
import org.rakam.ui.ScheduledTaskUIHttpService;
import org.rakam.util.JsonHelper;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.google.common.io.ByteStreams.toByteArray;
import static java.lang.String.format;
import static org.rakam.plugin.tasks.ScheduledTaskHttpService.run;

public class TestScheduledTask
{

    @Test(enabled = false)
    public void testName()
            throws Exception
    {
        FieldDependencyBuilder.FieldDependency fieldDependency = new FieldDependencyBuilder().build();
        JSCodeCompiler.TestLogger logger = new JSCodeCompiler.TestLogger();
        TestingConfigManager testingConfigManager = new TestingConfigManager();
        JSCodeCompiler.IJSConfigManager ijsConfigManager = new JSCodeCompiler.JSConfigManager(testingConfigManager, "test", null);

        InMemoryApiKeyService apiKeyService = new InMemoryApiKeyService();
        InMemoryMetastore metastore = new InMemoryMetastore(apiKeyService);
        SchemaChecker schemaChecker = new SchemaChecker(metastore, new FieldDependencyBuilder().build());
        JsonEventDeserializer testingEventDeserializer = new JsonEventDeserializer(metastore,
                apiKeyService,
                testingConfigManager,
                schemaChecker,
                fieldDependency);
        metastore.createProject("test");

        AsyncHttpClientConfig cf = new DefaultAsyncHttpClientConfig.Builder()
                .setRequestTimeout(100000)
                .setUserAgent("test")
                .setUseNativeTransport(Epoll.isAvailable())
                .build();

        String metadataDatabase = Files.createTempDir().getAbsolutePath();

        JDBCPoolDataSource sa = JDBCPoolDataSource.getOrCreateDataSource(new JDBCConfig().setUrl("jdbc:h2:" + metadataDatabase)
                .setUsername("sa").setPassword(""));

        Map<String, ScheduledTaskUIHttpService.Parameter> parameters = JsonHelper.read(toByteArray(this.getClass().getResource("/scheduled-task/adwords/config.json").openStream()),
                new TypeReference<Map<String, String>>() {}).entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> new ScheduledTaskUIHttpService.Parameter(FieldType.STRING, e.getValue(), null, null)));

        InMemoryEventStore eventStore = new InMemoryEventStore();

        JSCodeCompiler jsCodeCompiler = new JSCodeCompiler(testingConfigManager,
                new RAsyncHttpClient(new DefaultAsyncHttpClient(cf)),
                new JSCodeLoggerService(sa),
                true);

        CompletableFuture<ScheduledTaskHttpService.Environment> future =
                run(jsCodeCompiler, Runnable::run, "test", "load('src/test/resources/scheduled-task/adwords/script.js')",
                        parameters, logger, ijsConfigManager, testingEventDeserializer, eventStore, ImmutableList.of()).thenApply(eventList -> {
                    if (eventStore.getEvents().isEmpty()) {
                        logger.info("No event is returned");
                    }
                    else {
                        logger.info(format("Successfully got %d events: %s: %s", eventStore.getEvents().size(),
                                eventStore.getEvents().get(0).collection(),
                                eventStore.getEvents().get(0).properties()));
                    }

                    return new ScheduledTaskHttpService.Environment(logger.getEntries(), testingConfigManager.getTable().row("test"));
                });

        System.out.println(future.join());
    }
}