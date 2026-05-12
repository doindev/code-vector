package io.doindev.cvector.cli.output;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TableRenderer {

    private TableRenderer() {}

    public static void render(PrintStream out, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            out.println("(no results)");
            return;
        }
        Set<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) cols.addAll(r.keySet());
        List<String> headers = new ArrayList<>(cols);

        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) widths[i] = headers.get(i).length();
        for (Map<String, Object> r : rows) {
            for (int i = 0; i < headers.size(); i++) {
                widths[i] = Math.max(widths[i], stringify(r.get(headers.get(i))).length());
            }
        }

        StringBuilder line = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            line.append(pad(headers.get(i), widths[i])).append(i == headers.size() - 1 ? "" : "  ");
        }
        out.println(line);

        line.setLength(0);
        for (int i = 0; i < headers.size(); i++) {
            line.append("-".repeat(widths[i])).append(i == headers.size() - 1 ? "" : "  ");
        }
        out.println(line);

        for (Map<String, Object> r : rows) {
            line.setLength(0);
            for (int i = 0; i < headers.size(); i++) {
                line.append(pad(stringify(r.get(headers.get(i))), widths[i])).append(i == headers.size() - 1 ? "" : "  ");
            }
            out.println(line);
        }
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) return s;
        return s + " ".repeat(w - s.length());
    }

    private static String stringify(Object o) {
        return o == null ? "" : Objects.toString(o);
    }
}
