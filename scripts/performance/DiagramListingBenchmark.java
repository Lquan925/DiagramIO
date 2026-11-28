import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Standalone JDBC benchmark; creates and drops only its own uniquely named database. */
public class DiagramListingBenchmark {
    private static final String METADATA = "id,title,diagram_type,version,is_public,owner_id,updated_by,created_at,updated_at";
    private static final String ORDER = " order by updated_at desc,id desc";
    private static volatile long consumed;

    record Scenario(String name, String predicate, int offset) {}
    record Sample(double medianMs, double p95Ms, long bytes) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: java -cp <postgresql.jar> scripts/performance/DiagramListingBenchmark.java <jdbcUrl> <outputDirectory> [rows=100000] [repeats=30]. Credentials: BENCHMARK_DB_USER/PASSWORD environment variables.");
        }
        String url = args[0];
        Path output = Path.of(args[1]);
        int rows = args.length > 2 ? Integer.parseInt(args[2]) : 100000;
        int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 30;
        if (rows < 1000 || repeats < 5) throw new IllegalArgumentException("rows >= 1000, repeats >= 5 required");
        Files.createDirectories(output);
        String schema = "diagram_listing_benchmark_" + UUID.randomUUID().toString().replace("-", "");
        String user = System.getenv().getOrDefault("BENCHMARK_DB_USER", "benchmark");
        String password = System.getenv().getOrDefault("BENCHMARK_DB_PASSWORD", "");
        StringBuilder report = new StringBuilder();
        StringBuilder plans = new StringBuilder();
        StringBuilder csv = new StringBuilder("phase,scenario,sql_statements,median_ms,p95_ms,returned_text_bytes\n");
        report.append("Measured at UTC: ").append(Instant.now()).append('\n');
        report.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
        report.append("Java: ").append(System.getProperty("java.version")).append("; JVM visible processors: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        report.append("Rows: ").append(rows).append("; owner count: 100; heavy owner: first 20%; public: 10%; payload: 8192 ASCII characters in each node; page size: 20\n");
        report.append("Warmup: 10 full sequences per scenario/phase; samples: ").append(repeats).append("; one sequential JDBC connection; warm cache; times include fetching all result columns but exclude connection acquisition and HTTP.\n");
        // CREATE DATABASE permission is required. The database in the supplied URL is
        // used only to create/drop this disposable database, never to seed tables.
        try (Connection admin = DriverManager.getConnection(url, user, password)) {
            try (Statement create = admin.createStatement()) {
                create.execute("create database " + schema);
            }
            try (Connection connection = DriverManager.getConnection(databaseUrl(url, schema), user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                statement.execute("set search_path to " + schema + ",public");
                report.append("PostgreSQL: ").append(scalar(statement, "select version()")).append('\n');
                for (String setting : List.of("shared_buffers", "work_mem", "effective_cache_size", "max_parallel_workers_per_gather", "jit")) {
                    report.append(setting).append(": ").append(scalar(statement, "show " + setting)).append('\n');
                }
                for (String migration : List.of("V1__create_tables.sql", "V2__seed_sample_user.sql", "V3__create_refresh_tokens.sql")) {
                    statement.execute(Files.readString(Path.of("src/main/resources/db/migration", migration), StandardCharsets.UTF_8));
                }
                statement.execute("insert into users(id,username,email,password_hash) select md5('owner'||g)::uuid,'bench'||g,'bench'||g||'@invalid','unused' from generate_series(1,100) g");
                String seed = "with payload as (select string_agg(md5(k::text),'') txt from generate_series(1,256) k) "
                        + "insert into diagrams(id,title,diagram_type,data,version,is_public,owner_id,updated_by,created_at,updated_at) "
                        + "select md5(g::text)::uuid,case when g%101=0 then 'Needle Invoice '||g else 'Model '||g end,"
                        + "case when g%2=0 then 'UML' else 'ERD' end,jsonb_build_object('nodes',jsonb_build_array(jsonb_build_object('id',g,'payload',payload.txt)),'edges','[]'::jsonb),"
                        + "1,g%10=0,md5('owner'||case when g<=" + rows / 5 + " then 1 else 2+g%99 end)::uuid,null,"
                        + "timestamptz '2026-11-24 00:00:00+00'+(g/5)*interval '1 second',timestamptz '2026-11-24 00:00:00+00'+(g/5)*interval '1 second' "
                        + "from generate_series(1," + rows + ") g cross join payload";
                long seedStart = System.nanoTime();
                statement.execute(seed);
                statement.execute("vacuum (analyze) diagrams");
                report.append("Seed seconds: ").append((System.nanoTime() - seedStart) / 1_000_000_000d).append('\n');
                report.append("Baseline relation bytes: ").append(scalar(statement, "select pg_total_relation_size('diagrams')")).append('\n');
                report.append("Mean JSON text bytes: ").append(scalar(statement, "select avg(octet_length(data::text)) from diagrams")).append('\n');
                List<Scenario> scenarios = List.of(
                        new Scenario("owner", "owner_id=md5('owner1')::uuid", 0),
                        new Scenario("owner_type", "owner_id=md5('owner1')::uuid and diagram_type='UML'", 0),
                        new Scenario("owner_title", "owner_id=md5('owner1')::uuid and upper(title) like '%NEEDLE%' escape '\\'", 0),
                        new Scenario("owner_type_title", "owner_id=md5('owner1')::uuid and diagram_type='UML' and upper(title) like '%NEEDLE%' escape '\\'", 0),
                        new Scenario("public", "is_public=true", 0),
                        new Scenario("owner_deep_page", "owner_id=md5('owner1')::uuid", rows / 10));
                Map<String, List<String>> baselineResults = new LinkedHashMap<>();
                for (String phase : List.of("old_actual", "old_with_sort", "optimized")) {
                    if (phase.equals("optimized")) {
                        long indexStart = System.nanoTime();
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V4__optimize_diagram_listing.sql"), StandardCharsets.UTF_8));
                        report.append("V4 migration seconds: ").append((System.nanoTime() - indexStart) / 1_000_000_000d).append('\n');
                        statement.execute("vacuum (analyze) diagrams");
                        report.append("Optimized relation bytes: ").append(scalar(statement, "select pg_total_relation_size('diagrams')")).append('\n');
                        try (ResultSet indexes = statement.executeQuery("select indexname,indexdef from pg_indexes where schemaname='" + schema + "' and tablename='diagrams' order by indexname")) {
                            while (indexes.next()) report.append(indexes.getString(1)).append(": ").append(indexes.getString(2)).append('\n');
                        }
                    }
                    for (Scenario scenario : scenarios) {
                        long total = Long.parseLong(scalar(statement, "select count(id) from diagrams where " + scenario.predicate()));
                        List<String> sql = sequence(phase, scenario, total);
                        List<String> comparable = selectedIds(statement, scenario);
                        if (phase.equals("old_actual")) baselineResults.put(scenario.name(), comparable);
                        else if (!baselineResults.get(scenario.name()).equals(comparable)) throw new AssertionError("Result mismatch: " + scenario.name());
                        for (int i = 0; i < 10; i++) execute(statement, sql);
                        Sample sample = measure(statement, sql, repeats);
                        csv.append(String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%d%n", phase, scenario.name(), sql.size(), sample.medianMs(), sample.p95Ms(), sample.bytes()));
                        plans.append("\n=== ").append(phase).append(" / ").append(scenario.name()).append(" ===\n");
                        for (String query : sql) {
                            plans.append(query).append(";\n");
                            try (ResultSet rs = statement.executeQuery("explain (analyze,buffers,settings,format text) " + query)) {
                                while (rs.next()) plans.append(rs.getString(1)).append('\n');
                            }
                        }
                        System.out.printf(Locale.ROOT, "%s %s median %.3f ms p95 %.3f ms%n", phase, scenario.name(), sample.medianMs(), sample.p95Ms());
                    }
                }
                report.append("Validated: ordered metadata IDs and exact total counts match before/after for every scenario.\n");
            } finally {
                try (Statement drop = admin.createStatement()) {
                    // This also removes pg_trgm from the disposable public schema.
                    drop.execute("drop database " + schema + " with (force)");
                }
            }
        } finally {
            Files.writeString(output.resolve("environment.txt"), report, StandardCharsets.UTF_8);
            Files.writeString(output.resolve("results.csv"), csv, StandardCharsets.UTF_8);
            Files.writeString(output.resolve("explain-plans.txt"), plans, StandardCharsets.UTF_8);
        }
    }

    private static String databaseUrl(String url, String database) {
        int queryStart = url.indexOf('?');
        String address = queryStart < 0 ? url : url.substring(0, queryStart);
        String query = queryStart < 0 ? "" : url.substring(queryStart);
        int lastSlash = address.lastIndexOf('/');
        if (lastSlash <= address.indexOf("://") + 2) {
            throw new IllegalArgumentException("JDBC URL must include /database, for example jdbc:postgresql://localhost:5432/postgres");
        }
        return address.substring(0, lastSlash + 1) + database + query;
    }

    private static List<String> sequence(String phase, Scenario scenario, long total) {
        String where = " from diagrams where " + scenario.predicate();
        String page = " offset " + scenario.offset() + " rows fetch first 20 rows only";
        String count = "select count(id)" + where;
        if (phase.equals("optimized")) return List.of("select " + METADATA + where + ORDER + page, count);
        String order = phase.equals("old_with_sort") ? ORDER : "";
        // Spring can infer a partial page's total. The measured 100k-row workload
        // has full pages for every scenario, so both legacy Pages issue COUNT.
        List<String> queries = new ArrayList<>();
        queries.add("select " + METADATA + ",data" + where + order + page);
        long returned = Math.max(0, Math.min(20, total - scenario.offset()));
        if (returned == 20 || (scenario.offset() > 0 && returned == 0)) queries.add(count);
        queries.add("select " + METADATA + ",data" + where + " offset 0 rows fetch first 1 rows only");
        if (total > 0) queries.add(count);
        return queries;
    }

    private static List<String> selectedIds(Statement statement, Scenario scenario) throws Exception {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("select id from diagrams where " + scenario.predicate() + ORDER + " offset " + scenario.offset() + " rows fetch first 20 rows only")) {
            while (rs.next()) values.add(rs.getString(1));
        }
        values.add("count=" + scalar(statement, "select count(id) from diagrams where " + scenario.predicate()));
        return values;
    }

    private static Sample measure(Statement statement, List<String> sql, int repeats) throws Exception {
        double[] times = new double[repeats];
        long bytes = 0;
        for (int i = 0; i < repeats; i++) {
            long start = System.nanoTime();
            bytes = execute(statement, sql);
            times[i] = (System.nanoTime() - start) / 1_000_000d;
        }
        Arrays.sort(times);
        return new Sample(times[times.length / 2], times[Math.max(0, (int) Math.ceil(times.length * .95) - 1)], bytes);
    }

    private static long execute(Statement statement, List<String> sql) throws Exception {
        long bytes = 0;
        for (String query : sql) {
            try (ResultSet rs = statement.executeQuery(query)) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    for (int c = 1; c <= columns; c++) {
                        String value = rs.getString(c);
                        if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
                    }
                }
            }
        }
        consumed = bytes;
        return bytes;
    }

    private static String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException(sql);
            return rs.getString(1);
        }
    }
}
