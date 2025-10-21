package dev.evo.opensearch.script;

import org.opensearch.painless.spi.PainlessExtension;
import org.opensearch.painless.spi.Allowlist;
import org.opensearch.painless.spi.AllowlistLoader;
import org.opensearch.script.ScriptContext;

import java.util.List;
import java.util.Map;

public class MergeScriptPainlessExtension implements PainlessExtension {
    private static final Allowlist ALLOWLIST =
        AllowlistLoader.loadFromResourceFiles(MergeScriptPainlessExtension.class, "merge.script.allowlist.txt");

    @Override
    public Map<ScriptContext<?>, List<Allowlist>> getContextAllowlists() {
        return Map.of(MergeScript.CONTEXT, List.of(ALLOWLIST));
    }
}
