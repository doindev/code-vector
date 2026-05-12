package io.doindev.cvector.parser.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class JavaParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(JavaParserAdapter.class);

    private volatile ParserConfiguration parserConfig;
    /**
     * One {@link JavaParser} per thread. The library isn't thread-safe for concurrent {@code parse}
     * calls on a single instance (it holds error/comment-collection state internally), so when the
     * scan walks files in parallel each worker gets its own. Configured once in {@link #prepare}
     * and lazily initialised the first time each thread parses.
     */
    private final ThreadLocal<JavaParser> parser = ThreadLocal.withInitial(() -> new JavaParser(parserConfig));

    @Override
    public String name() { return "java"; }

    @Override
    public Set<String> supportedExtensions() { return Set.of("java"); }

    @Override
    public void prepare(ProjectContext ctx) {
        // Symbol resolution is no longer performed during parse — see CvectorJavaVisitor for the
        // post-pass placeholder/rewire strategy. This skips both setup cost (walking for source roots)
        // and the per-call resolve() reflection that previously dominated scan time.
        this.parserConfig = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        try {
            ParseResult<CompilationUnit> result = parser.get().parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.debug("parse failed for {}: {}", file, result.getProblems());
                return;
            }
            CompilationUnit cu = result.getResult().get();
            NodeKey fileKey = new NodeKey(ctx.projectId(), "File", ctx.rootPath().relativize(file).toString().replace('\\', '/'));
            Map<String, Object> fileProps = new HashMap<>();
            fileProps.put("path", ctx.rootPath().relativize(file).toString().replace('\\', '/'));
            fileProps.put("language", "java");
            fileProps.put("lineCount", countLines(file));
            sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

            new CvectorJavaVisitor(ctx, fileKey, sink).visit(cu, null);
        } catch (IOException e) {
            log.warn("failed to read {}: {}", file, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("failed to parse {}: {}", file, e.getMessage());
        }
    }

    private static long countLines(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.count();
        } catch (IOException e) {
            return 0L;
        }
    }
}
