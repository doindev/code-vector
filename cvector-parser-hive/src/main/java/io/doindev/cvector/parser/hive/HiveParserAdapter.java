package io.doindev.cvector.parser.hive;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import io.doindev.cvector.parser.hive.internal.HiveLexer;
import io.doindev.cvector.parser.hive.internal.HiveParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class HiveParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(HiveParserAdapter.class);
    private static final Set<String> EXTENSIONS = Set.of("hql", "hive");

    @Override public String name() { return "hive"; }
    @Override public Set<String> supportedExtensions() { return EXTENSIONS; }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String source;
        try { source = Files.readString(file); }
        catch (IOException e) { log.warn("could not read {}: {}", file, e.getMessage()); return; }
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');

        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fp = new HashMap<>();
        fp.put("path", relPath);
        fp.put("language", "hive");
        fp.put("lineCount", source.lines().count());
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fp));

        HiveLexer lexer = new HiveLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(SilentErrorListener.INSTANCE);
        HiveParser parser = new HiveParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(SilentErrorListener.INSTANCE);
        try { parser.statement(); }
        catch (RuntimeException e) { log.warn("Hive parse failed for {}: {}", file, e.getMessage()); }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        static final SilentErrorListener INSTANCE = new SilentErrorListener();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            log.debug("Hive syntax error at {}:{} — {}", line, col, msg);
        }
    }
}
