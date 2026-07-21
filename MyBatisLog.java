import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MybatisLog {

    record ColumnValue(String name, String value) {}

    record SqlEvent(String tableName, String sqlType, String mapperMethod, List<ColumnValue> columnValues) {}

    record ParseResult(String processName, LinkedHashMap<String, List<SqlEvent>> tableEvents) {}

    private static final Pattern PROCESS_NAME_PATTERN = Pattern.compile("START API\\s*-\\s*(\\S+)");
    private static final Pattern PARAM_TOKEN_PATTERN = Pattern.compile("(null|.*?\\([\\w.$\\[\\]]+\\))(?:,\\s*|$)");
    private static final Pattern SQL_TYPE_PATTERN = Pattern.compile("^(\\w+)");
    private static final Pattern UPDATE_TABLE_PATTERN = Pattern.compile("(?i)^update\\s+(\\S+)");
    private static final Pattern SET_KEYWORD_PATTERN = Pattern.compile("(?i)\\bset\\b");
    private static final Pattern WHERE_KEYWORD_PATTERN = Pattern.compile("(?i)\\bwhere\\b");
    private static final Pattern INSERT_PATTERN =
            Pattern.compile("(?i)^insert\\s+into\\s+(\\S+)\\s*\\(([^)]*)\\)\\s*values\\s*\\(([^)]*)\\)");
    private static final Pattern DELETE_TABLE_PATTERN = Pattern.compile("(?i)^delete\\s+from\\s+(\\S+)");
    private static final Pattern SELECT_PATTERN = Pattern.compile("(?i)^select\\s+(.*?)\\s+from\\s+(\\S+)");
    private static final Pattern DISTINCT_PREFIX_PATTERN = Pattern.compile("(?i)^distinct\\s+");

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java Main <input.log>");
            System.exit(1);
            return;
        }
        List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        ParseResult result = parseLog(lines);
        printOutput(result);
    }

    static ParseResult parseLog(List<String> lines) {
        String processName = null;
        LinkedHashMap<String, List<SqlEvent>> tableEvents = new LinkedHashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (processName == null && line.contains("START API")) {
                processName = extractProcessName(line);
                continue;
            }

            if (line.contains("Preparing:")) {
                int arrowIdx = line.indexOf("==>");
                String beforeArrow = arrowIdx >= 0 ? line.substring(0, arrowIdx) : line;
                String mapperMethod = extractMapperMethod(beforeArrow);

                int prepIdx = line.indexOf("Preparing:");
                String sql = line.substring(prepIdx + "Preparing:".length()).trim();
                if (sql.endsWith(";")) {
                    sql = sql.substring(0, sql.length() - 1).trim();
                }

                List<String> params = List.of();
                if (i + 1 < lines.size() && lines.get(i + 1).contains("Parameters:")) {
                    String paramsLine = lines.get(i + 1);
                    int paramIdx = paramsLine.indexOf("Parameters:");
                    String paramsText = paramsLine.substring(paramIdx + "Parameters:".length()).trim();
                    params = parseParameters(paramsText);
                    i++;
                }

                SqlEvent event = buildSqlEvent(mapperMethod, sql, params);
                if (event != null) {
                    tableEvents.computeIfAbsent(event.tableName(), k -> new ArrayList<>()).add(event);
                }
                continue;
            }
        }

        if (processName == null) {
            processName = "";
        }
        return new ParseResult(processName, tableEvents);
    }

    static String extractProcessName(String line) {
        Matcher m = PROCESS_NAME_PATTERN.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    static String extractMapperMethod(String beforeArrow) {
        String trimmed = beforeArrow.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] tokens = trimmed.split("\\s+");
        String lastToken = tokens[tokens.length - 1];
        String[] parts = lastToken.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return lastToken;
    }

    static List<String> parseParameters(String paramsText) {
        List<String> values = new ArrayList<>();
        if (paramsText.isBlank()) {
            return values;
        }
        Matcher m = PARAM_TOKEN_PATTERN.matcher(paramsText);
        while (m.find()) {
            String element = m.group(1);
            if (element.equals("null")) {
                values.add("null");
            } else {
                int idx = element.lastIndexOf('(');
                values.add(idx >= 0 ? element.substring(0, idx) : element);
            }
        }
        return values;
    }

    static SqlEvent buildSqlEvent(String mapperMethod, String sql, List<String> params) {
        String type = detectSqlType(sql);
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "UPDATE" -> parseUpdate(mapperMethod, sql, params);
            case "INSERT" -> parseInsert(mapperMethod, sql, params);
            case "DELETE" -> parseDelete(mapperMethod, sql, params);
            case "SELECT" -> parseSelect(mapperMethod, sql, params);
            default -> null;
        };
    }

    static String detectSqlType(String sql) {
        Matcher m = SQL_TYPE_PATTERN.matcher(sql.trim());
        if (!m.find()) {
            return null;
        }
        String keyword = m.group(1).toUpperCase();
        return switch (keyword) {
            case "UPDATE", "INSERT", "DELETE", "SELECT" -> keyword;
            default -> null;
        };
    }

    static SqlEvent parseUpdate(String mapperMethod, String sql, List<String> params) {
        Matcher tableMatcher = UPDATE_TABLE_PATTERN.matcher(sql);
        if (!tableMatcher.find()) {
            return null;
        }
        String table = tableMatcher.group(1);

        Matcher setMatcher = SET_KEYWORD_PATTERN.matcher(sql);
        if (!setMatcher.find(tableMatcher.end())) {
            return null;
        }
        int setStart = setMatcher.end();

        Matcher whereMatcher = WHERE_KEYWORD_PATTERN.matcher(sql);
        int setEnd = whereMatcher.find(setStart) ? whereMatcher.start() : sql.length();

        String setClause = sql.substring(setStart, setEnd);
        List<String> assignments = splitTopLevel(setClause, ',');

        int paramCursor = countChar(sql.substring(0, setStart), '?');

        List<ColumnValue> columnValues = new ArrayList<>();
        for (String token : assignments) {
            int eqIdx = token.indexOf('=');
            if (eqIdx < 0) {
                continue;
            }
            String colName = token.substring(0, eqIdx).trim();
            int qCount = countChar(token, '?');
            String value;
            if (qCount == 0) {
                value = "";
            } else {
                int start = Math.min(paramCursor, params.size());
                int end = Math.min(paramCursor + qCount, params.size());
                value = String.join(", ", params.subList(start, end));
            }
            paramCursor += qCount;
            columnValues.add(new ColumnValue(colName, value));
        }

        return new SqlEvent(table, "UPDATE", mapperMethod, columnValues);
    }

    static SqlEvent parseInsert(String mapperMethod, String sql, List<String> params) {
        Matcher m = INSERT_PATTERN.matcher(sql);
        if (!m.find()) {
            return null;
        }
        String table = m.group(1);
        String columnsRaw = m.group(2);

        List<String> columns = splitTopLevel(columnsRaw, ',').stream().map(String::trim).toList();

        int valuesStart = m.start(3);
        int paramCursor = countChar(sql.substring(0, valuesStart), '?');

        List<ColumnValue> columnValues = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            int idx = paramCursor + i;
            String value = idx < params.size() ? params.get(idx) : "";
            columnValues.add(new ColumnValue(columns.get(i), value));
        }

        return new SqlEvent(table, "INSERT", mapperMethod, columnValues);
    }

    static SqlEvent parseDelete(String mapperMethod, String sql, List<String> params) {
        Matcher m = DELETE_TABLE_PATTERN.matcher(sql);
        if (!m.find()) {
            return null;
        }
        String table = m.group(1);
        return new SqlEvent(table, "DELETE", mapperMethod, List.of());
    }

    static SqlEvent parseSelect(String mapperMethod, String sql, List<String> params) {
        Matcher m = SELECT_PATTERN.matcher(sql);
        if (!m.find()) {
            return null;
        }
        String columnsRaw = DISTINCT_PREFIX_PATTERN.matcher(m.group(1).trim()).replaceFirst("");
        String table = m.group(2);

        List<ColumnValue> columnValues;
        if (columnsRaw.equals("*")) {
            columnValues = List.of(new ColumnValue("*", ""));
        } else {
            columnValues = splitTopLevel(columnsRaw, ',').stream()
                    .map(String::trim)
                    .map(c -> new ColumnValue(c, ""))
                    .toList();
        }

        return new SqlEvent(table, "SELECT", mapperMethod, columnValues);
    }

    static List<String> splitTopLevel(String s, char delimiter) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == delimiter && depth == 0) {
                result.add(s.substring(start, i));
                start = i + 1;
            }
        }
        result.add(s.substring(start));
        return result;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    static void printOutput(ParseResult result) {
        var out = System.out;
        out.println(result.processName());
        for (var entry : result.tableEvents().entrySet()) {
            String table = entry.getKey();
            String prevType = null;
            for (SqlEvent event : entry.getValue()) {
                if (!event.sqlType().equals(prevType)) {
                    out.println(table + "\t" + event.sqlType() + "\t" + event.mapperMethod());
                    prevType = event.sqlType();
                }
                if (event.sqlType().equals("DELETE")) {
                    out.println("delete");
                } else {
                    for (ColumnValue cv : event.columnValues()) {
                        out.println(cv.name() + "\t" + cv.value());
                    }
                }
            }
        }
    }
}
