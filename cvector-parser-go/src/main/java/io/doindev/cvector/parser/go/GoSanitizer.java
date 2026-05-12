package io.doindev.cvector.parser.go;

public final class GoSanitizer {

    private GoSanitizer() {}

    /** Strip //, /* * / comments and blank "..." / `...` / '...' literal contents. Preserves offsets + newlines. */
    public static String stripCommentsAndStrings(String source) {
        if (source == null || source.isEmpty()) return source == null ? "" : source;
        char[] arr = source.toCharArray();
        StringBuilder out = new StringBuilder(arr.length);
        int i = 0;
        while (i < arr.length) {
            char c = arr[i];
            if (c == '/' && i + 1 < arr.length && arr[i + 1] == '/') {
                while (i < arr.length && arr[i] != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < arr.length && arr[i + 1] == '*') {
                out.append("  ");
                i += 2;
                while (i + 1 < arr.length && !(arr[i] == '*' && arr[i + 1] == '/')) {
                    out.append(arr[i] == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i + 1 < arr.length) { out.append("  "); i += 2; }
            } else if (c == '`') {
                // raw string literal — may span multiple lines
                out.append('`');
                i++;
                while (i < arr.length && arr[i] != '`') {
                    out.append(arr[i] == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < arr.length) { out.append('`'); i++; }
            } else if (c == '"') {
                out.append('"');
                i++;
                while (i < arr.length && arr[i] != '"') {
                    if (arr[i] == '\\' && i + 1 < arr.length) {
                        out.append(' ').append(' ');
                        i += 2;
                        continue;
                    }
                    if (arr[i] == '\n') break;
                    out.append(' ');
                    i++;
                }
                if (i < arr.length && arr[i] == '"') { out.append('"'); i++; }
            } else if (c == '\'') {
                out.append('\'');
                i++;
                while (i < arr.length && arr[i] != '\'') {
                    if (arr[i] == '\\' && i + 1 < arr.length) {
                        out.append(' ').append(' ');
                        i += 2;
                        continue;
                    }
                    if (arr[i] == '\n') break;
                    out.append(' ');
                    i++;
                }
                if (i < arr.length && arr[i] == '\'') { out.append('\''); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
