# doris-sql-runner

`doris-sql-runner` executes SQL files against Apache Doris / SelectDB and collects query profiles from FE HTTP APIs. It is useful for benchmark runs, query regression checks, and collecting profile files in batch.

## Features

- Execute one SQL file or recursively execute every file in a directory.
- Split SQL files by semicolon into multiple statements.
- Inject a trace marker into each statement so the tool can find the corresponding query profile.
- Enable Doris profile by default with `enable_profile=true`.
- Add custom Doris session variables from command line.
- Run statements serially or with configurable parallelism.
- Add sleep interval between query submissions.
- Save profile files under a configurable output directory.
- For directory mode, profile file names are based on SQL file names, for example `query.sql.profile`.

## Requirements

- JDK 17 or newer.
- Maven 3.8 or newer.
- Doris FE MySQL/JDBC port, usually `9030`.
- Doris FE HTTP port, usually `8030`.
- The Doris user must be allowed to execute the SQL and read profile/query info APIs.

## Build

Build a runnable shaded jar:

```bash
mvn clean package
```

The output jar is:

```bash
target/doris-sql-runner-1.0.0.jar
```

Run help:

```bash
java -jar target/doris-sql-runner-1.0.0.jar --help
```

## Run One SQL File

```bash
java -jar target/doris-sql-runner-1.0.0.jar \
  --sql-file ./queries/q1.sql \
  --host 127.0.0.1 \
  --port 9030 \
  --user root \
  --password "$DORIS_PASSWORD" \
  --database minimax \
  --fe-http-port 8030 \
  --output-dir ./profiles
```

If the SQL file contains multiple semicolon-separated statements, each statement is executed and tracked independently.

## Run A Directory

Execute every regular file under a directory recursively, sorted by path:

```bash
java -jar target/doris-sql-runner-1.0.0.jar \
  --sql-dir ./queries \
  --host 127.0.0.1 \
  --port 9030 \
  --user root \
  --password "$DORIS_PASSWORD" \
  --database minimax \
  --fe-http-port 8030 \
  --output-dir ./profiles
```

Profile output names use the source SQL file name:

- Single statement file: `query.sql.profile`
- Multi-statement file: `query.sql.001.profile`, `query.sql.002.profile`

## Parallel Execution

```bash
java -jar target/doris-sql-runner-1.0.0.jar \
  --sql-dir ./queries \
  --host 127.0.0.1 \
  --port 9030 \
  --user root \
  --password "$DORIS_PASSWORD" \
  --database minimax \
  --parallelism 4 \
  --output-dir ./profiles
```

`--parallelism 1` means serial execution.

## Sleep Between Queries

Add an interval between query submissions:

```bash
java -jar target/doris-sql-runner-1.0.0.jar \
  --sql-dir ./queries \
  --host 127.0.0.1 \
  --port 9030 \
  --user root \
  --password "$DORIS_PASSWORD" \
  --database minimax \
  --sleep-ms 1000 \
  --output-dir ./profiles
```

In parallel mode, the sleep interval delays task submission.

## Session Variables

The tool sets this by default:

```sql
set enable_profile=true;
```

Add more session variables with repeated `--session-var key=value`:

```bash
java -jar target/doris-sql-runner-1.0.0.jar \
  --sql-file ./queries/q1.sql \
  --host 127.0.0.1 \
  --port 9030 \
  --user root \
  --password "$DORIS_PASSWORD" \
  --database minimax \
  --session-var enable_nereids_planner=true \
  --session-var parallel_pipeline_task_num=8 \
  --output-dir ./profiles
```

If you pass `--session-var enable_profile=false`, it overrides the default and profile collection may not find a profile.

## SQL Parsing Rules

- Statements are split by semicolon.
- Block comments are removed, except optimizer hints such as `/*+ ... */`.
- Line comments starting with `--` are removed.
- A trace hint is injected into each statement so FE query info can be matched back to the SQL file.

## Common Parameters

- `--sql-file`: execute a single SQL file.
- `--sql-dir`: recursively execute SQL files in a directory.
- `--host`: Doris FE JDBC host.
- `--port`: Doris FE JDBC/MySQL port, default `9030`.
- `--user`: Doris username, default `root`.
- `--password`: Doris password.
- `--database`: Doris database, required.
- `--parallelism`: execution parallelism, default `1`.
- `--fe-http-port`: Doris FE HTTP port, default `8030`.
- `--output-dir`: directory for profile output, default current directory.
- `--session-var`: Doris session variable in `key=value` form. Can be repeated.
- `--sleep-ms`: sleep interval in milliseconds between query submissions, default `0`.

## Notes

- Do not put real passwords in committed scripts. Prefer environment variables.
- Make sure FE HTTP API is reachable from the machine running this tool.
- Profile generation is asynchronous; the tool waits briefly before fetching profiles.
