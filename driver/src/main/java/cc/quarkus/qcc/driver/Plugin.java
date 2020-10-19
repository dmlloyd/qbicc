package cc.quarkus.qcc.driver;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;

import cc.quarkus.qcc.graph.BasicBlockBuilder;
import cc.quarkus.qcc.graph.DelegatingGraphFactory;
import cc.quarkus.qcc.interpreter.JavaVM;

/**
 * A plugin to the QCC process.
 */
public interface Plugin {
    Plugin[] NO_PLUGINS = new Plugin[0];

    /**
     * Get the name of this plugin.  Used in cases where a plugin is to be explicitly disabled by configuration.
     *
     * @return the plugin name (must not be {@code null})
     */
    String getName();

    /**
     * Get the graph factory plugins for this plugin.  The returned list may be empty but may not be {@code null}.
     *
     * @return the list of graph factory plugins (must not be {@code null})
     *
     * @see DelegatingGraphFactory
     */
    default List<BasicBlockBuilder.Factory> getBasicBlockBuilderFactoryPlugins() {
        return List.of();
    }

    /**
     * Get the VM configuration plugins for this plugin.  The returned list may be empty but may not be {@code null}.
     *
     * @return the list of VM configuration plugins (must not be {@code null})
     */
    default List<Consumer<JavaVM.Builder>> getJavaVMConfigurationPlugins() {
        return List.of();
    }

    /**
     * Find all the plugins in the given list of class loaders, except for any plugins disabled by name in the given
     * set.  The plugins will be returned in search order, without duplicates.  Note that the same class being defined
     * by two class loaders will not be considered a duplicate.
     *
     * @param searchLoaders the list of class loaders to search (must not be {@code null})
     * @param disabledPlugins the set of disabled plugins (must not be {@code null})
     * @return the list of all matching plugins (not {@code null})
     */
    static List<Plugin> findAllPlugins(List<ClassLoader> searchLoaders, Set<String> disabledPlugins) {
        LinkedHashMap<Class<?>, Plugin> plugins = new LinkedHashMap<>();
        for (ClassLoader searchLoader : searchLoaders) {
            ServiceLoader<Plugin> serviceLoader = ServiceLoader.load(Plugin.class, searchLoader);
            Iterator<ServiceLoader.Provider<Plugin>> iterator = serviceLoader.stream().iterator();
            for (;;) try {
                if (! iterator.hasNext()) {
                    break;
                }
                ServiceLoader.Provider<Plugin> plugin = iterator.next();
                if (! plugins.containsKey(plugin.type())) {
                    plugins.put(plugin.type(), plugin.get());
                }
            } catch (ServiceConfigurationError ignored) {}
        }
        return List.of(plugins.values().toArray(NO_PLUGINS));
    }
}