package net.vheerden.archi.mcp.logging;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/**
 * Logger factory that creates {@link EclipseLogger} instances.
 *
 * <p>Caches loggers by name in a thread-safe map to ensure one logger per name.</p>
 */
public class EclipseLoggerFactory implements ILoggerFactory {

    private final ConcurrentMap<String, EclipseLogger> loggerMap = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        return loggerMap.computeIfAbsent(name, EclipseLogger::new);
    }
}
