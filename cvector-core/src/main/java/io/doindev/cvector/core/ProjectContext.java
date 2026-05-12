package io.doindev.cvector.core;

import java.nio.file.Path;

public record ProjectContext(String projectId, String projectName, Path rootPath) {}
