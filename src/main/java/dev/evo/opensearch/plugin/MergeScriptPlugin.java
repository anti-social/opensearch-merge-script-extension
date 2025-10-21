package dev.evo.opensearch.plugin;

import dev.evo.opensearch.MergeScriptExtBuilder;
import dev.evo.opensearch.MergeScriptFilter;
import dev.evo.opensearch.script.MergeScript;
import org.opensearch.action.support.ActionFilter;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class MergeScriptPlugin extends Plugin
    implements ActionPlugin, SearchPlugin, ScriptPlugin
{
    private final Settings settings;
    private ScriptService scriptService;

    public MergeScriptPlugin(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Collection<Object> createComponents(
            Client client,
            ClusterService clusterService,
            ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService,
            ScriptService scriptService,
            NamedXContentRegistry xContentRegistry,
            Environment environment,
            NodeEnvironment nodeEnvironment,
            NamedWriteableRegistry namedWriteableRegistry,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<RepositoriesService> repositoriesServiceSupplier) {
        System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        System.out.println(">>> createComponents");
        for (var l : scriptService.getScriptLanguages().languageContexts.keySet()) {
            System.out.println(l);
        }
        System.out.println(scriptService.isLangSupported("painless"));
        System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        new Exception().printStackTrace();
        this.scriptService = scriptService;
        return Collections.emptyList();
    }

    @Override
    public List<ScriptContext<?>> getContexts() {
        return Collections.singletonList(MergeScript.CONTEXT);
    }

    @Override
    public List<ActionFilter> getActionFilters() {
        return Collections.singletonList(
            new MergeScriptFilter(settings, scriptService)
        );
    }

    @Override
    public List<SearchExtSpec<?>> getSearchExts() {
        return Collections.singletonList(
            new SearchExtSpec<>(
                MergeScriptExtBuilder.NAME,
                MergeScriptExtBuilder::new,
                MergeScriptExtBuilder::fromXContent
            )
        );
    }
}
