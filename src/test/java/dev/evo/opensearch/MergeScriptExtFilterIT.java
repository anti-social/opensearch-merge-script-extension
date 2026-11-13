package dev.evo.opensearch;

import dev.evo.opensearch.plugin.MergeScriptPlugin;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.common.lucene.search.function.CombineFunction;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.functionscore.ScoreFunctionBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptType;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.OpenSearchIntegTestCase;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertOrderedSearchHits;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertRequestBuilderThrows;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE)
public class MergeScriptExtFilterIT extends OpenSearchIntegTestCase {
    private final static int NUMBER_OF_SHARDS = 2;

    public void testEmpty() throws Exception {
        createIndex(NUMBER_OF_SHARDS);

        var resp = rescoreSearchRequest();
        assertHitCount(resp, 0);
    }

    public void testMergeScript() throws Exception {
        createIndex(NUMBER_OF_SHARDS);
        populateIndex();

        var resp = rescoreSearchRequest();
        assertHitCount(resp, 6);
        assertOrderedSearchHits(resp, "103", "101", "301", "201", "304", "204");
    }

    public void testMergeScriptWithPagination() throws Exception {
        createIndex(NUMBER_OF_SHARDS);
        populateIndex();

        var resp = rescoreSearchRequest(10_000, true, 2, 3);
        assertHitCount(resp, 6);
        assertOrderedSearchHits(resp, "301", "201", "304");
    }

    public void testMergeScriptWithPaginationOverflow() throws Exception {
        createIndex(NUMBER_OF_SHARDS);
        populateIndex();

        var resp = rescoreSearchRequest(10_000, true, 7, 3);
        assertHitCount(resp, 6);
        assertOrderedSearchHits(resp);
    }

    public void testMergeScriptWithoutPagination() throws Exception {
        createIndex(NUMBER_OF_SHARDS);
        populateIndex();

        var resp = rescoreSearchRequest(10_000, false, 2, 3);
        assertHitCount(resp, 6);
        assertOrderedSearchHits(resp, "103", "101", "301", "201", "304", "204");
    }

    public void testMatchedQueries() throws Exception {
        createIndex(NUMBER_OF_SHARDS);
        populateIndex();

        var resp = matchedQueriesSearchRequest(
            false,
            """
                boolean isTurbo = false;
                for (String name : hit.getMatchedQueries()) {
                    if (name == "turbo") {
                        isTurbo = true;
                    }
                }
                return isTurbo;
            """
        );
        assertHitCount(resp, 6);
        assertOrderedSearchHits(resp, "201", "304", "204", "101", "103", "301");
    }

    public void testMatchedQueryScore() throws Exception {
        createIndex(NUMBER_OF_SHARDS);
        populateIndex();

        var resp = matchedQueriesSearchRequest(
            true,
            "return hit.getMatchedQueryScore(\"turbo\") != null;"
        );
        assertHitCount(resp, 6);
        assertOrderedSearchHits(resp, "201", "304", "204", "101", "103", "301");
    }

    public void testMatchedQueriesAndScores() throws Exception {
        createIndex(NUMBER_OF_SHARDS);
        populateIndex();

        var resp = matchedQueriesSearchRequest(
            true,
            """
                Map matched = hit.getMatchedQueriesAndScores();
                return matched.containsKey("turbo") && !matched.containsKey("company");
            """
        );
        assertHitCount(resp, 6);
        assertOrderedSearchHits(resp, "304", "101", "103", "201", "301", "204");
    }

    /**
     * Moves hits for which {@code isTurboSource} returns {@code true} to the top,
     * preserving the original order inside both groups.
     * Hits are sorted by rank: 101, 103, 201, 301, 304, 204.
     */
    private SearchResponse matchedQueriesSearchRequest(
        boolean includeNamedQueriesScores, String isTurboSource
    ) throws Exception {
        var mergeScript = new MergeScriptExtBuilder.MergeScript(
            new Script(
                ScriptType.INLINE,
                "painless",
                """
                    boolean isTurbo(SearchHit hit) {
                    %s
                    }
                    SearchHit[] mixedHits = new SearchHit[hits.length];
                    int mixedIx = 0;
                    for (SearchHit hit : hits) {
                        if (isTurbo(hit)) {
                            mixedHits[mixedIx++] = hit;
                        }
                    }
                    for (SearchHit hit : hits) {
                        if (!isTurbo(hit)) {
                            mixedHits[mixedIx++] = hit;
                        }
                    }
                    return mixedHits;
                """.formatted(isTurboSource),
                Collections.emptyMap()
            ),
            List.of()
        );
        var searchQuery = SearchSourceBuilder.searchSource()
            .size(10)
            .includeNamedQueriesScores(includeNamedQueriesScores)
            .query(
                QueryBuilders.functionScoreQuery(
                    QueryBuilders.boolQuery()
                        .must(QueryBuilders.matchAllQuery())
                        .should(QueryBuilders.termQuery("is_turbo", true).queryName("turbo"))
                        .should(QueryBuilders.existsQuery("company_id").queryName("company")),
                    ScoreFunctionBuilders.scriptFunction(
                        new Script(
                            "return doc['rank'].size() > 0 ? doc['rank'].value : 0.0;"
                        )
                    )
                )
                    .boostMode(CombineFunction.REPLACE)
            )
            .ext(List.of(new MergeScriptExtBuilder(mergeScript)));
        return search(searchQuery);
    }

    private SearchResponse rescoreSearchRequest() throws Exception {
        return rescoreSearchRequest(10_000, true, 0, 10);
    }

    private SearchResponse rescoreSearchRequest(
        int windowSize, boolean pagination, int from, int size
    ) throws Exception {
        return rescoreSearchRequest(
            windowSize,
            pagination,
            from,
            size,
            new MergeScriptExtBuilder.MergeScript(
                new Script(
                    ScriptType.INLINE,
                    "painless",
                    """
                        SearchHit[] mixedHits = new SearchHit[hits.length];
                        ArrayDeque delayedCommonHits = new ArrayDeque();
                        ArrayDeque delayedTurboHits = new ArrayDeque();
                        int hitIx = 0;
                        int mixedIx = 0;
                        boolean isTurboExpected = false;
                        while (mixedIx < hits.length) {
                            ArrayDeque expectedHits = isTurboExpected ? delayedTurboHits : delayedCommonHits;
                            ArrayDeque otherHits = isTurboExpected ? delayedCommonHits : delayedTurboHits;
                            SearchHit nextHit = null;
                            if (!expectedHits.isEmpty()) {
                                nextHit = expectedHits.removeFirst();
                            } else {
                                // Pull hits until one of the expected kind is found,
                                // delaying hits of the other kind
                                while (hitIx < hits.length) {
                                    SearchHit hit = hits[hitIx++];
                                    boolean isTurbo = hit.field("is_turbo").booleanValueOrDefault(false) ||
                                        hit.field("rank").floatValueOrDefault(0.0F) > 2.0F;
                                    if (isTurbo == isTurboExpected) {
                                        nextHit = hit;
                                        break;
                                    }
                                    otherHits.addLast(hit);
                                }
                            }
                            if (nextHit == null) {
                                // No more hits of the expected kind, drain the rest
                                while (!otherHits.isEmpty()) {
                                    mixedHits[mixedIx++] = otherHits.removeFirst();
                                }
                            } else {
                                mixedHits[mixedIx++] = nextHit;
                                isTurboExpected = !isTurboExpected;
                            }
                        }
                        return mixedHits;
                    """,
                    Collections.emptyMap()
                ),
                List.of("is_turbo", "rank")
            )
        );
    }

    private SearchResponse rescoreSearchRequest(
        int windowSize,
        boolean pagination,
        int from,
        int size,
        MergeScriptExtBuilder.MergeScript mergeScript
    ) throws Exception {
        var searchQuery = SearchSourceBuilder.searchSource()
            .from(from)
            .size(size)
            .query(
                QueryBuilders.functionScoreQuery(
                    ScoreFunctionBuilders.scriptFunction(
                        new Script(
                            "return doc['rank'].size() > 0 ? doc['rank'].value : 0.0;"
                        )
                    )
                )
            )
            .ext(
                List.of(
                    new MergeScriptExtBuilder(mergeScript)
                        .windowSize(windowSize)
                        .pagination(pagination)
                )
            );
        return search(searchQuery);
    }

    private SearchResponse search(SearchSourceBuilder searchQuery) throws Exception {
        var searchRequest = new Request("GET", "/test/_search");
        var json = jsonBuilder();
        searchQuery.toXContent(json, ToXContent.EMPTY_PARAMS);
        searchRequest.setJsonEntity(json.toString());

        var response = getRestClient().performRequest(searchRequest);
        var parser = createParser(JsonXContent.jsonXContent, EntityUtils.toString(response.getEntity()));
        return SearchResponse.fromXContent(parser);
    }

    private void createIndex(int numShards) throws IOException {
        assertAcked(
            prepareCreate("test")
                .setSettings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, numShards))
                .setMapping(
                    jsonBuilder()
                        .startObject()
                          .startObject("properties")
                            .startObject("name")
                              .field("type", "text")
                              .field("analyzer", "whitespace")
                            .endObject()
                            .startObject("company_id")
                              .field("type", "integer")
                            .endObject()
                            .startObject("rank")
                              .field("type", "float")
                            .endObject()
                            .startObject("is_turbo")
                              .field("type", "boolean")
                            .endObject()
                          .endObject()
                        .endObject()
                )
        );
        ensureYellow();
    }

    private void populateIndex() throws IOException {
        client().prepareIndex("test")
            .setId("101")
            .setSource(
                "name", "the quick brown fox",
                "company_id", 1,
                "rank", 2.1,
                "is_turbo", false
            )
            .get();
        client().prepareIndex("test")
            .setId("103")
            .setSource(
                "name", "quick huge brown fox",
                "company_id", 1,
                "rank", 1.9,
                "is_turbo", false
            )
            .get();
        refresh();
        // Ensure products are placed on different shards
        assertHitCount(
            client().prepareSearch()
                .setQuery(
                    QueryBuilders.boolQuery()
                        .filter(QueryBuilders.termQuery("company_id", 1))
                )
                .setPreference("_shards:0")
                .get(),
        1
        );

        client().prepareIndex("test")
            .setId("201")
            .setSource(
                "name", "the quick lazy huge fox jumps over the tree",
                "company_id", 2,
                "rank", 1.6,
                "is_turbo", true
            )
            .get();
        client().prepareIndex("test")
            .setId("204")
            .setSource(
                "name", "the brother of the quick lazy fox",
                "company_id", 2,
                "is_turbo", true
            )
            .get();
        refresh();
        // Ensure products are placed on different shards
        assertHitCount(
            client().prepareSearch()
                .setQuery(
                    QueryBuilders.boolQuery()
                        .filter(QueryBuilders.termQuery("company_id", 2))
                )
                .setPreference("_shards:0")
                .get(),
            1
        );

        client().prepareIndex("test")
            .setId("301")
            .setSource(
                "name", "the quick lonely fox",
                "rank", 1.0
            )
            .get();
        client().prepareIndex("test")
            .setId("304")
            .setSource(
                "name", "another lonely fox",
                "rank", 0.8,
                "is_turbo", true
            )
            .get();
        refresh();
        // Ensure products are placed on different shards
        assertHitCount(
            client().prepareSearch()
                .setQuery(
                    QueryBuilders.boolQuery()
                        .filter(
                            QueryBuilders.boolQuery()
                                .mustNot(QueryBuilders.existsQuery("company_id"))
                        )
                )
                .setPreference("_shards:0")
                .get(),
            1
        );

        ensureYellow();
    }
}
