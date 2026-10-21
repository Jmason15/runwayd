# runwayd

Personal finance desktop app (formerly Paydar, and before that TreasureTracker): pick a future date, see every account's balance and when each debt is paid off. Java 21, JavaFX 21, Maven, two modules (`core`, `desktop`). See `README.md` for the code map and `docs/how-it-works.md` for the rules behind each tab.

## Build and run

The JDK and Maven live in `~/tools` (not on the PATH by default):

```bash
export JAVA_HOME=$(ls -d ~/tools/jdk-21*/Contents/Home)
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
mvn install                  # build + all tests
mvn -pl desktop javafx:run   # open the app
./package-mac.sh             # build dist/runwayd-1.0.0.dmg (Apple-chip Macs)
```

## Rules for working on it

- **Readable, well-commented code is the point.** Plain-English Javadoc on every class and non-obvious method, matching the existing style. Prefer small records and clear names over cleverness.
- **Agree the design before building** anything sizeable; propose the structure first.
- **`core` has no JavaFX.** All money math and rules live in `core`; `desktop` only displays and calls `FinanceService`, which hands each call to the feature class that owns it (`accounts/`, `bills/`, `debt/`, `budget/`, `forecast/`). New logic goes in the feature class, with a thin method on `FinanceService`.
- **Every rule gets a unit test** in the same package under `core/src/test`. Run `mvn install` before calling something done.
- **Data files load forever.** Records are read by Jackson; a new field must default sensibly when missing (handle `null` in the compact constructor), and a renamed field needs `@JsonAlias`. Helper methods on records must not start with `get`/`is`, or Jackson writes them to the file.
- **Never touch the real data directly.** It lives in `~/runwayd/budgets/*.json`. Shared budgets live in a synced folder listed under `sharedBudgets` in `~/runwayd/settings.json`; never write to those either (the other computer is using them). Checks and experiments run on copies (e.g. in a scratch folder); the real files change only through the app.
- **Personal data never goes in the repo** (see `.gitignore`).
- **Releases** go out with `./publish.sh x.y.z "notes"` (signed, notarized, GitHub Release); installed copies update themselves. The repo is public: never commit real names, amounts or the user's email (commits use the GitHub noreply address).
- After changing code the user will look at, rebuild and restart the app (`pkill -f javafx:run`, then `mvn -pl desktop javafx:run` in the background).
