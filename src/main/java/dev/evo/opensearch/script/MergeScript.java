package dev.evo.opensearch.script;

import org.opensearch.index.fielddata.ScriptDocValues;
import org.opensearch.script.DynamicMap;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptFactory;
import org.opensearch.search.SearchHit;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class MergeScript {
    public static final String[] PARAMETERS = {"hits"};

    public interface Factory extends ScriptFactory {
        MergeScript newInstance(Map<String, Object> params);
    }

    public static final ScriptContext<Factory> CONTEXT = new ScriptContext<>("merge", Factory.class);

    private final Map<String, Object> params;

    public MergeScript(Map<String, Object> params) {
        this.params = params;
    }

    /** Provides access to script parameters */
    public Map<String, Object> getParams() {
        return params;
    }

    public abstract SearchHit[] execute(SearchHit[] hits);
}
