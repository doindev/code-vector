package io.doindev.cvector.core;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

public interface Parser {

    String name();

    Set<String> supportedExtensions();

    default boolean accepts(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return supportedExtensions().contains(name.substring(dot + 1).toLowerCase());
    }

    default void prepare(ProjectContext ctx) {}

    void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink);

    default void finish() {}
}
