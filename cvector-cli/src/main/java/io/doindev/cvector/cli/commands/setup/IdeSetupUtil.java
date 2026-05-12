package io.doindev.cvector.cli.commands.setup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.doindev.cvector.cli.CvectorRuntime;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IdeSetupUtil {

    private IdeSetupUtil() {}

    public static Path detectCvectorJar() {
        Path fromClasspath = fromJavaClassPath();
        if (fromClasspath != null && Files.exists(fromClasspath)) return fromClasspath;
        Path fromCommand = fromSunJavaCommand();
        if (fromCommand != null && Files.exists(fromCommand)) return fromCommand;
        Path fromProcess = fromProcessArguments();
        if (fromProcess != null && Files.exists(fromProcess)) return fromProcess;
        Path fromUrl = fromCodeSourceUrl();
        if (fromUrl != null && Files.exists(fromUrl)) return fromUrl;
        throw new IllegalStateException(
                "could not resolve cvector jar path. Use --jar /path/to/cvector.jar to override.");
    }

    private static Path fromJavaClassPath() {
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.isBlank()) return null;
        if (cp.contains(java.io.File.pathSeparator)) return null;
        if (!cp.endsWith(".jar")) return null;
        return Paths.get(cp).toAbsolutePath();
    }

    private static Path fromSunJavaCommand() {
        String cmd = System.getProperty("sun.java.command");
        if (cmd == null || cmd.isBlank()) return null;
        int space = cmd.indexOf(' ');
        String first = space < 0 ? cmd : cmd.substring(0, space);
        if (!first.endsWith(".jar")) return null;
        return Paths.get(first).toAbsolutePath();
    }

    private static Path fromCodeSourceUrl() {
        URL loc = IdeSetupUtil.class.getProtectionDomain().getCodeSource().getLocation();
        if (loc == null) return null;
        String u = loc.toString();
        int bang = u.indexOf('!');
        String path = bang > 0 ? u.substring(0, bang) : u;
        path = path.replaceFirst("^jar:nested:", "");
        path = path.replaceFirst("^jar:", "");
        path = path.replaceFirst("^file:/+", "");
        if (path.length() >= 2 && path.charAt(1) != ':' && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            path = "/" + path;
        }
        try {
            return Paths.get(path);
        } catch (RuntimeException e) {
            try {
                return Paths.get(loc.toURI());
            } catch (URISyntaxException ex) {
                return null;
            }
        }
    }

    private static Path fromProcessArguments() {
        try {
            String[] args = ProcessHandle.current().info().arguments().orElse(new String[0]);
            for (int i = 0; i < args.length - 1; i++) {
                if ("-jar".equals(args[i])) {
                    return Paths.get(args[i + 1]);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    public static int writeMcpServerEntry(Path configPath, String serverName, Path projectRoot, Path jarPath, CvectorRuntime runtime) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, Object> root;
        if (Files.exists(configPath)) {
            root = mapper.readValue(configPath.toFile(), new TypeReference<>() {});
        } else {
            root = new LinkedHashMap<>();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> mcpServers = (Map<String, Object>) root.getOrDefault("mcpServers", new LinkedHashMap<>());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("command", javaExecutable());
        entry.put("args", List.of("-jar", jarPath.toAbsolutePath().toString(), "serve"));
        entry.put("cwd", projectRoot.toAbsolutePath().toString());
        mcpServers.put(serverName, entry);

        root.put("mcpServers", mcpServers);

        Files.createDirectories(configPath.getParent());
        mapper.writeValue(configPath.toFile(), root);
        return 0;
    }

    public static Path claudeDesktopConfigPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null) appData = System.getProperty("user.home") + "/AppData/Roaming";
            return Paths.get(appData, "Claude", "claude_desktop_config.json");
        }
        if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Claude", "claude_desktop_config.json");
        }
        return Paths.get(System.getProperty("user.home"), ".config", "Claude", "claude_desktop_config.json");
    }

    public static Path windsurfConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".codeium", "windsurf", "mcp_config.json");
    }

    public static Path cursorConfigPath(Path projectRoot) {
        return projectRoot.resolve(".cursor").resolve("mcp.json");
    }

    public static Path antigravityConfigPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null) appData = System.getProperty("user.home") + "/AppData/Roaming";
            return Paths.get(appData, "Antigravity", "mcp_config.json");
        }
        if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Antigravity", "mcp_config.json");
        }
        return Paths.get(System.getProperty("user.home"), ".config", "antigravity", "mcp_config.json");
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path candidate = Paths.get(home, "bin", "java");
        if (Files.exists(candidate)) return candidate.toString();
        Path winCandidate = Paths.get(home, "bin", "java.exe");
        if (Files.exists(winCandidate)) return winCandidate.toString();
        return "java";
    }
}
