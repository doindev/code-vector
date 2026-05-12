package io.doindev.cvector.parser.sql;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.NodeKey;
import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.ProjectContext;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class SqlParserAdapter implements Parser {

    private static final Logger log = LoggerFactory.getLogger(SqlParserAdapter.class);

    @Override
    public String name() { return "sql"; }

    @Override
    public Set<String> supportedExtensions() { return Set.of("sql"); }

    @Override
    public void parse(Path file, ProjectContext ctx, Consumer<GraphEvent> sink) {
        String relPath = ctx.rootPath().relativize(file).toString().replace('\\', '/');
        NodeKey fileKey = new NodeKey(ctx.projectId(), "File", relPath);
        Map<String, Object> fileProps = new HashMap<>();
        fileProps.put("path", relPath);
        fileProps.put("language", "sql");
        sink.accept(new GraphEvent.NodeUpsert(fileKey, fileProps));

        String sql;
        try {
            sql = Files.readString(file);
        } catch (IOException e) {
            log.warn("failed to read {}: {}", file, e.getMessage());
            return;
        }
        if (sql.isBlank()) return;

        Statements parsed;
        try {
            parsed = CCJSqlParserUtil.parseStatements(sql);
        } catch (Exception e) {
            log.debug("failed to parse {}: {}", file, e.getMessage());
            return;
        }
        if (parsed == null || parsed.getStatements() == null) return;

        for (Statement s : parsed.getStatements()) {
            if (s instanceof CreateTable ct) {
                emitCreateTable(ct, ctx, fileKey, sink);
            } else if (s instanceof Select sel) {
                emitTableUsage(sel, "READS_TABLE", ctx, fileKey, sink);
            } else if (s instanceof Insert ins) {
                emitTableUsage(ins, "WRITES_TABLE", ctx, fileKey, sink);
            } else if (s instanceof Update upd) {
                emitTableUsage(upd, "WRITES_TABLE", ctx, fileKey, sink);
            } else if (s instanceof Delete del) {
                emitTableUsage(del, "WRITES_TABLE", ctx, fileKey, sink);
            }
        }
    }

    private void emitCreateTable(CreateTable ct, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        Table t = ct.getTable();
        if (t == null) return;
        String tableName = normalize(t.getName());
        NodeKey tableKey = new NodeKey(ctx.projectId(), "Table", tableName);
        Map<String, Object> props = new HashMap<>();
        props.put("name", tableName);
        props.put("fqName", tableName);
        props.put("fileId", fileKey.id());
        sink.accept(new GraphEvent.NodeUpsert(tableKey, props));
        sink.accept(new GraphEvent.EdgeUpsert(fileKey, "CONTAINS", tableKey, Map.of()));

        List<ColumnDefinition> cols = ct.getColumnDefinitions();
        if (cols == null) return;
        for (ColumnDefinition c : cols) {
            String colName = normalize(c.getColumnName());
            String type = c.getColDataType() != null ? c.getColDataType().getDataType() : "unknown";
            String fq = tableName + "." + colName;
            NodeKey colKey = new NodeKey(ctx.projectId(), "Column", fq);
            Map<String, Object> cp = new HashMap<>();
            cp.put("name", colName);
            cp.put("fqName", fq);
            cp.put("type", type);
            cp.put("tableId", tableKey.id());
            cp.put("fileId", fileKey.id());
            sink.accept(new GraphEvent.NodeUpsert(colKey, cp));
            sink.accept(new GraphEvent.EdgeUpsert(tableKey, "CONTAINS", colKey, Map.of()));
        }
    }

    private void emitTableUsage(Statement s, String edgeType, ProjectContext ctx, NodeKey fileKey, Consumer<GraphEvent> sink) {
        List<String> names;
        try {
            names = new TablesNamesFinder().getTableList(s);
        } catch (Exception e) {
            return;
        }
        if (names == null) return;
        for (String raw : names) {
            String name = normalize(raw);
            NodeKey tableKey = new NodeKey(ctx.projectId(), "Table", name);
            Map<String, Object> stubProps = new HashMap<>();
            stubProps.put("name", name);
            stubProps.put("fqName", name);
            sink.accept(new GraphEvent.NodeUpsert(tableKey, stubProps));
            sink.accept(new GraphEvent.EdgeUpsert(fileKey, edgeType, tableKey, Map.of()));
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String trimmed = raw.replace("`", "").replace("\"", "").trim();
        return trimmed.toLowerCase();
    }
}
