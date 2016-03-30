package org.rakam.report;

import com.facebook.presto.sql.parser.ParsingException;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.google.inject.Inject;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.analysis.MaterializedViewService;
import org.rakam.analysis.MaterializedViewService.MaterializedViewExecution;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.analysis.metadata.QueryMetadataStore;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.MaterializedView;
import org.rakam.util.QueryFormatter;
import org.rakam.util.RakamException;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static org.rakam.report.QueryResult.EXECUTION_TIME;

public class QueryExecutorService {
    private final SqlParser parser = new SqlParser();

    private final QueryExecutor executor;
    private final QueryMetadataStore queryMetadataStore;
    private final MaterializedViewService materializedViewService;
    private final Metastore metastore;
    private final Clock clock;
    private volatile Set<String> projectCache;

    @Inject
    public QueryExecutorService(QueryExecutor executor, QueryMetadataStore queryMetadataStore, Metastore metastore, MaterializedViewService materializedViewService, Clock clock) {
        this.executor = executor;
        this.queryMetadataStore = queryMetadataStore;
        this.materializedViewService = materializedViewService;
        this.metastore = metastore;
        this.clock = clock;
    }

    public QueryExecution executeQuery(String project, String sqlQuery, int limit) {
        if (!projectExists(project)) {
            throw new IllegalArgumentException("Project is not valid");
        }
        HashMap<MaterializedView, MaterializedViewExecution> materializedViews = new HashMap<>();
        String query;

        try {
            query = buildQuery(project, sqlQuery, limit, materializedViews);
        } catch (ParsingException e) {
            return QueryExecution.completedQueryExecution(sqlQuery, QueryResult.errorResult(new QueryError(e.getMessage(), null, null, e.getLineNumber(), e.getColumnNumber())));
        }

        long startTime = System.currentTimeMillis();

        List<MaterializedViewExecution> queryExecutions = materializedViews.values().stream()
                .filter(m -> m.queryExecution != null)
                .collect(Collectors.toList());


        if (queryExecutions.isEmpty()) {
            QueryExecution execution = executor.executeRawQuery(query);
            if (materializedViews.isEmpty()) {
                return execution;
            } else {
                Map<String, Long> collect = materializedViews.entrySet().stream().collect(Collectors.toMap(v -> v.getKey().name, v -> v.getKey().lastUpdate != null ? v.getKey().lastUpdate.toEpochMilli() : -1));
                return new DelegateQueryExecution(execution, result -> {
                    result.setProperty("materializedViews", collect);
                    return result;
                });
            }
        } else {
            CompletableFuture<QueryExecution> mergedQueries = CompletableFuture.allOf(queryExecutions.stream()
                    .filter(e -> e.queryExecution != null)
                    .map(e -> e.queryExecution.getResult())
                    .toArray(CompletableFuture[]::new)).thenApply((r) -> {

                for (MaterializedViewExecution queryExecution : queryExecutions) {
                    QueryResult result = queryExecution.queryExecution.getResult().join();
                    if (result.isFailed()) {
                        return new DelegateQueryExecution(queryExecution.queryExecution,
                                materializedQueryUpdateResult -> {
                                    QueryError error = materializedQueryUpdateResult.getError();
                                    String message = String.format("Error while updating materialized table '%s': %s", queryExecution.computeQuery, error.message);
                                    return QueryResult.errorResult(new QueryError(message, error.sqlState, error.errorCode, error.errorLine, error.charPositionInLine));
                                });
                    }
                }

                return executor.executeRawQuery(query);
            });

            return new QueryExecution() {
                @Override
                public QueryStats currentStats() {
                    QueryStats currentStats = null;
                    for (MaterializedViewExecution queryExecution : queryExecutions) {
                        QueryStats queryStats = queryExecution.queryExecution.currentStats();
                        if (currentStats == null) {
                            currentStats = queryStats;
                        } else {
                            currentStats = merge(currentStats, queryStats);
                        }
                    }

                    if (mergedQueries.isDone()) {
                        currentStats = merge(currentStats, mergedQueries.join().currentStats());
                    }

                    return currentStats;
                }

                private QueryStats merge(QueryStats currentStats, QueryStats stats) {
                    return new QueryStats(currentStats.percentage + stats.percentage,
                            currentStats.state.equals(stats.state) ? currentStats.state : QueryStats.State.RUNNING,
                            Math.max(currentStats.node, stats.node),
                            stats.processedRows + currentStats.processedRows,
                            stats.processedBytes + currentStats.processedBytes,
                            stats.userTime + currentStats.userTime,
                            stats.cpuTime + currentStats.cpuTime,
                            stats.wallTime + currentStats.wallTime
                    );
                }

                @Override
                public boolean isFinished() {
                    if (mergedQueries.isDone()) {
                        QueryExecution join = mergedQueries.join();
                        return join == null || join.isFinished();
                    } else {
                        return false;
                    }
                }

                @Override
                public CompletableFuture<QueryResult> getResult() {
                    CompletableFuture<QueryResult> future = new CompletableFuture<>();
                    mergedQueries.thenAccept(r -> {
                        if (r == null) {
                            future.complete(null);
                        } else {
                            r.getResult().thenAccept(result -> {
                                if (!result.isFailed()) {
                                    Map<String, Long> collect = materializedViews.entrySet().stream().collect(Collectors.toMap(v -> v.getKey().name, v -> v.getKey().lastUpdate.toEpochMilli()));
                                    result.setProperty("materializedViews", collect);
                                    result.setProperty(EXECUTION_TIME, System.currentTimeMillis() - startTime);
                                }

                                future.complete(result);
                            });
                        }
                    });
                    return future;
                }

                @Override
                public String getQuery() {
                    return query;
                }

                @Override
                public void kill() {
                    for (MaterializedViewExecution queryExecution : queryExecutions) {
                        queryExecution.queryExecution.kill();
                    }
                    mergedQueries.thenAccept(q -> q.kill());
                }
            };
        }
    }

    public QueryExecution executeQuery(String project, String sqlQuery) {
        return executeQuery(project, sqlQuery, 10000);
    }

    public QueryExecution executeStatement(String project, String sqlQuery) {
        return executeQuery(project, sqlQuery);
    }

    private synchronized void updateProjectCache() {
        projectCache = metastore.getProjects();
    }

    private boolean projectExists(String project) {
        if (projectCache == null) {
            updateProjectCache();
        }

        if (!projectCache.contains(project)) {
            updateProjectCache();
            if (!projectCache.contains(project)) {
                return false;
            }
        }

        return true;
    }

    public String buildQuery(String project, String query, Integer maxLimit, Map<MaterializedView, MaterializedViewExecution> materializedViews) {
        StringBuilder builder = new StringBuilder();
        Query statement;
        synchronized (parser) {
            statement = (Query) parser.createStatement(query);
        }

        // TODO: use fake StringBuilder for performance
        new QueryFormatter(new StringBuilder(), tableNameMapper(project, materializedViews, true)).process(statement, 1);

        new QueryFormatter(builder, tableNameMapper(project, materializedViews, false)).process(statement, 1);

        if (maxLimit != null) {
            Integer limit = null;
            if (statement.getLimit().isPresent()) {
                limit = Integer.parseInt(statement.getLimit().get());
            }
            if (statement.getQueryBody() instanceof QuerySpecification && ((QuerySpecification) statement.getQueryBody()).getLimit().isPresent()) {
                limit = Integer.parseInt(((QuerySpecification) statement.getQueryBody()).getLimit().get());
            }
            if (limit != null) {
                if (limit > maxLimit) {
                    throw new IllegalArgumentException(format("The maximum value of LIMIT statement is %s", statement.getLimit().get()));
                }
            } else {
                builder.append(" LIMIT ").append(maxLimit);
            }
        }

        return builder.toString();
    }

    private Function<QualifiedName, String> tableNameMapper(String project, Map<MaterializedView, MaterializedViewExecution> materializedViews, boolean fetchReference) {
        return (node) -> {
            if (node.getPrefix().isPresent() && node.getPrefix().get().toString().equals("materialized")) {
                MaterializedView materializedView;
                try {
                    materializedView = queryMetadataStore.getMaterializedView(project, node.getSuffix());
                } catch (Exception e) {
                    throw new RakamException(String.format("Referenced materialized table %s is not exist", node.getSuffix()), BAD_REQUEST);
                }
                if (fetchReference) {
                    materializedViews.computeIfAbsent(materializedView, (key) -> materializedViewService.lockAndUpdateView(materializedView));
                    return "";
                } else {
                    return materializedViews.get(materializedView).computeQuery;
                }
            }
            return executor.formatTableReference(project, node);
        };
    }

    public CompletableFuture<List<SchemaField>> metadata(String project, String query) {
        StringBuilder builder = new StringBuilder();
        Query queryStatement;
        try {
            queryStatement = (Query) parser.createStatement(checkNotNull(query, "query is required"));
        } catch (Exception e) {
            throw new RakamException("Unable to parse query: " + e.getMessage(), BAD_REQUEST);
        }

        new QueryFormatter(builder, qualifiedName -> executor.formatTableReference(project, qualifiedName))
                .process(queryStatement, 1);

        QueryExecution execution = executor
                .executeRawQuery(builder.toString() + " limit 0");
        CompletableFuture<List<SchemaField>> f = new CompletableFuture<>();
        execution.getResult().thenAccept(result -> {
            if (result.isFailed()) {
                f.completeExceptionally(new RakamException(result.getError().message, HttpResponseStatus.INTERNAL_SERVER_ERROR));
            } else {
                f.complete(result.getMetadata());
            }
        });
        return f;
    }
}
