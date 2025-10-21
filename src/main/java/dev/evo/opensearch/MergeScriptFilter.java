package dev.evo.opensearch;

import dev.evo.opensearch.script.MergeScript;
import org.opensearch.action.ActionRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.action.search.SearchAction;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.ActionFilter;
import org.opensearch.action.support.ActionFilterChain;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.script.ScriptService;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.internal.InternalSearchResponse;
import org.opensearch.search.profile.SearchProfileShardResults;
import org.opensearch.tasks.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class MergeScriptFilter implements ActionFilter {
    private static final int DEFAULT_SIZE = 10;

    private static final int DEFAULT_ORDER = 30;
    public static final Setting<Integer> FILTER_ORDER = Setting.intSetting(
        "merge.script.filter.order", DEFAULT_ORDER, Setting.Property.NodeScope
    );

    private final ScriptService scriptService;
    private final int order;

    public MergeScriptFilter(Settings settings, ScriptService scriptService) {
        this.scriptService = scriptService;
        this.order = FILTER_ORDER.get(settings);
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public <Request extends ActionRequest, Response extends ActionResponse> void apply(
        Task task,
        String action,
        Request request,
        ActionListener<Response> listener,
        ActionFilterChain<Request, Response> chain
    ) {
        if (!SearchAction.INSTANCE.name().equals(action)) {
            chain.proceed(task, action, request, listener);
            return;
        }

        final var searchRequest = (SearchRequest) request;
        var source = searchRequest.source();
        if (source == null) {
            source = SearchSourceBuilder.searchSource();
        }
        final var origSize = source.size();
        if (origSize == 0) {
            chain.proceed(task, action, request, listener);
            return;
        }
        final var size = origSize < 0 ? DEFAULT_SIZE : origSize;
        final var from = Math.max(source.from(), 0);

        final var searchExtensions = source.ext();
        final var searchExt = searchExtensions.stream()
            .filter(ext -> ext.getWriteableName().equals(MergeScriptExtBuilder.NAME))
            .findFirst();
        if (searchExt.isEmpty()) {
            chain.proceed(task, action, request, listener);
            return;
        }

        final var ext = (MergeScriptExtBuilder) searchExt.get();
        final var script = ext.mergeScript();
        MergeScript.Factory mergeScriptFactory = scriptService.compile(
            script.script,
            MergeScript.CONTEXT
        );
        MergeScript mergeScript = mergeScriptFactory.newInstance(
            script.script.getParams()
        );

        source.from(0);
        source.size(Math.max(ext.windowSize(), from + size));
        if (script.fields != null) {
            for (var scriptField : script.fields) {
                source.docValueField(scriptField);
            }
        }

        @SuppressWarnings("unchecked")
        final ActionListener<Response> rescoreListener = ActionListener.map(listener, (response) -> {
            final var resp = (SearchResponse) response;
            final var searchHits = resp.getHits();
            final var hits = searchHits.getHits();
            if (hits.length == 0) {
                return response;
            }

            final var respHits = mergeScript.execute(hits);

            return (Response) responseWithHits(
                resp,
                ext.pagination() ? paginate(respHits, from, size) : respHits
            );

        });

        chain.proceed(task, action, request, rescoreListener);
    }

    private SearchHit[] paginate(SearchHit[] hits, int from, int size) {
        return from < hits.length ?
            Arrays.copyOfRange(hits, from, Math.min(from + size, hits.length)) :
            new SearchHit[0];
    }

    private SearchResponse responseWithHits(
        SearchResponse response, SearchHit[] hits
    ) {
        final var searchHits = response.getHits();
        final var pageResponse = new InternalSearchResponse(
            new SearchHits(hits, searchHits.getTotalHits(), searchHits.getMaxScore()),
            (InternalAggregations) response.getAggregations(),
            response.getSuggest(),
            new SearchProfileShardResults(response.getProfileResults()),
            response.isTimedOut(),
            response.isTerminatedEarly(),
            response.getNumReducePhases()
        );
        return new SearchResponse(
            pageResponse,
            response.getScrollId(),
            response.getTotalShards(),
            response.getSuccessfulShards(),
            response.getSkippedShards(),
            response.getTook().millis(),
            response.getShardFailures(),
            response.getClusters()
        );
    }
}
