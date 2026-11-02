//! koi-ctl: drives a phone running the koiTerminal dev agent (devd).
//!
//! Every operation is a typed method of the dev API (see `koi-ctl methods`);
//! there is no shell. The connection is TLS 1.3 with this laptop's client
//! certificate, and devd's certificate is pinned at pairing.

mod conn;
mod store;

use std::process::ExitCode;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{Map, Value, json};

use conn::{Conn, pairing_code, sha256_hex};
use store::{Hosts, Identity};

const DEFAULT_PORT: u16 = 7391;
const DEVD_PACKAGE: &str = "io.github.franticplant.koiterminal.devd";

const USAGE: &str = "\
usage: koi-ctl [-a HOST:PORT] COMMAND [ARGS]

  pair [--name NAME] [--scopes a,b,..] [--repin]
                          pair with devd (approve on the phone; compare the code)
  hello                   who is there, and is this laptop paired
  status                  devd, packages, and the koiTerminal app at a glance
  methods [--all]         the methods this pairing may call (--all: every method)
  call METHOD [k=v | k:=JSON]...
                          call any method; prints the result as JSON
  logs [devd|app] [-f] [--level L] [--grep TEXT]
                          read devd's journal or the app's dev log; -f follows it
  update APK...           upload, verify and install builds (app and/or devd)
  top [--once]            live RAM/CPU of devd and the koiTerminal app
  vm [status|start|stop|shutdown|reset]
                          VM lifecycle and state
  app [info|start|restart|exits]
                          app process state and lifecycle
  installer [status|install|upgrade|cancel|retry]
                          guest-image install state and actions
  image                   installed image metadata
  config                  the installed vm_config.json
  settings [show|set k=v ...]
                          read or change allowlisted VM settings
  ports                   ports reported by the guest
  wait METHOD FIELD VALUE [--timeout SEC] [k=v ...]
                          poll until a JSON field equals VALUE, e.g.
                          wait vm.status vm.name running
  doctor                  one pass over devd, packages, app, VM and installer
  forget                  drop the pinned devd certificate for the address

The address comes from -a, else $KOI_ADDR, else the last one paired with.
Scopes: read, read.content, operate, secrets, lifecycle, scenario, update.";

fn main() -> ExitCode {
    match run(std::env::args().skip(1).collect()) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("koi-ctl: {e}");
            ExitCode::FAILURE
        }
    }
}

struct Ctx {
    addr: String,
    hosts: Hosts,
}

impl Ctx {
    fn identity(&self) -> Result<Identity, String> {
        store::identity(&client_name())
    }

    fn connect(&self) -> Result<Conn, String> {
        let pin = self.hosts.pin(&self.addr).ok_or_else(|| {
            format!("not paired with {}; run: koi-ctl -a {} pair", self.addr, self.addr)
        })?;
        Conn::open(&self.addr, &self.identity()?, Some(pin)).map_err(|e| e.to_string())
    }
}

fn client_name() -> String {
    let host = std::fs::read_to_string("/proc/sys/kernel/hostname")
        .map(|s| s.trim().to_string())
        .ok()
        .filter(|h| !h.is_empty())
        .unwrap_or_else(|| "laptop".into());
    let user = std::env::var("USER").unwrap_or_else(|_| "user".into());
    format!("koi-ctl {user}@{host}")
}

fn run(mut args: Vec<String>) -> Result<ExitCode, String> {
    let mut addr = std::env::var("KOI_ADDR").ok();
    if args.first().map(String::as_str) == Some("-a") {
        if args.len() < 2 {
            return Err("-a needs HOST:PORT".into());
        }
        addr = Some(args.remove(1));
        args.remove(0);
    }
    let Some(cmd) = args.first().cloned() else {
        println!("{USAGE}");
        return Ok(ExitCode::from(2));
    };
    let rest = args[1..].to_vec();
    if cmd == "help" || cmd == "-h" || cmd == "--help" {
        println!("{USAGE}");
        return Ok(ExitCode::SUCCESS);
    }
    let hosts = Hosts::load()?;
    let addr = addr
        .or_else(|| hosts.default.clone())
        .ok_or("no address: pass -a HOST:PORT or set KOI_ADDR")?;
    let addr = if addr.contains(':') { addr } else { format!("{addr}:{DEFAULT_PORT}") };
    let mut ctx = Ctx { addr, hosts };

    match cmd.as_str() {
        "pair" => pair(&mut ctx, &rest),
        "hello" => {
            let pin = ctx.hosts.pin(&ctx.addr);
            let mut c = Conn::open(&ctx.addr, &ctx.identity()?, pin).map_err(|e| e.to_string())?;
            print_json(&c.call("devd.hello", json!({})).map_err(|e| e.to_string())?);
            Ok(ExitCode::SUCCESS)
        }
        "status" => status(&ctx),
        "methods" => methods(&ctx, rest.iter().any(|a| a == "--all")),
        "call" => {
            let method = rest.first().ok_or("call needs a METHOD")?;
            let params = parse_params(&rest[1..])?;
            let mut c = ctx.connect()?;
            match c.call(method, Value::Object(params)) {
                Ok(v) => {
                    print_json(&v);
                    Ok(ExitCode::SUCCESS)
                }
                Err(e) => {
                    eprintln!("error: {e}");
                    Ok(ExitCode::FAILURE)
                }
            }
        }
        "logs" => logs(&ctx, &rest),
        "vm" => group(&ctx, "vm", &rest, "status"),
        "app" => group(&ctx, "app", &rest, "info"),
        "installer" => group(&ctx, "installer", &rest, "status"),
        "image" => show(&ctx, "image.info"),
        "config" => show(&ctx, "config.get"),
        "settings" => settings_cmd(&ctx, &rest),
        "ports" => show(&ctx, "ports.list"),
        "wait" => wait(&ctx, &rest),
        "doctor" => doctor(&ctx),
        "update" => update(&ctx, &rest),
        "top" => top(&ctx, rest.iter().any(|a| a == "--once")),
        "forget" => {
            ctx.hosts.pins.remove(&ctx.addr);
            if ctx.hosts.default.as_deref() == Some(&ctx.addr) {
                ctx.hosts.default = None;
            }
            ctx.hosts.save()?;
            println!(
                "forgot {} (the phone still lists the pairing until you revoke it there)",
                ctx.addr
            );
            Ok(ExitCode::SUCCESS)
        }
        other => Err(format!("unknown command {other}\n\n{USAGE}")),
    }
}

/// `k=v` is a string (devd converts it to the declared type); `k:=JSON` is
/// raw JSON (lists, numbers, booleans).
fn parse_params(args: &[String]) -> Result<Map<String, Value>, String> {
    let mut m = Map::new();
    for a in args {
        if let Some((k, v)) = a.split_once(":=") {
            let v: Value = serde_json::from_str(v).map_err(|e| format!("{k}: bad JSON: {e}"))?;
            m.insert(k.to_string(), v);
        } else if let Some((k, v)) = a.split_once('=') {
            m.insert(k.to_string(), Value::String(v.to_string()));
        } else {
            return Err(format!("argument {a:?} is not k=v or k:=JSON"));
        }
    }
    Ok(m)
}

fn print_json(v: &Value) {
    println!("{}", serde_json::to_string_pretty(v).unwrap_or_default());
}

fn pair(ctx: &mut Ctx, args: &[String]) -> Result<ExitCode, String> {
    let mut name = client_name();
    let mut scopes: Vec<String> =
        ["read", "read.content", "operate", "secrets", "lifecycle", "scenario", "update"]
            .map(String::from)
            .to_vec();
    let mut repin = false;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--name" => {
                name = args.get(i + 1).ok_or("--name needs a value")?.clone();
                i += 1;
            }
            "--scopes" => {
                scopes = args
                    .get(i + 1)
                    .ok_or("--scopes needs a value")?
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .collect();
                i += 1;
            }
            "--repin" => repin = true,
            a => return Err(format!("pair: unknown argument {a}")),
        }
        i += 1;
    }
    let id = ctx.identity()?;
    let pin = if repin { None } else { ctx.hosts.pin(&ctx.addr) };
    let mut c = Conn::open(&ctx.addr, &id, pin).map_err(|e| e.to_string())?;
    let code = pairing_code(&c.server_cert, &id.cert_der);
    let server = sha256_hex(&c.server_cert);
    println!("devd at {} has certificate {}", ctx.addr, &server[..32]);
    println!();
    println!("    pairing code: {code}");
    println!();
    println!("Approve on the phone only if devd shows the same code. Waiting (2 minutes)...");
    let r = c
        .call("pair.request", json!({ "name": name, "scopes": scopes }))
        .map_err(|e| e.to_string())?;
    ctx.hosts.pins.insert(
        ctx.addr.clone(),
        json!({ "serverCertSha256": server, "name": r["name"], "pairedMs": now_ms() }),
    );
    ctx.hosts.default = Some(ctx.addr.clone());
    ctx.hosts.save()?;
    println!(
        "paired as {} with scopes {} until {}",
        r["name"],
        r["scopes"],
        fmt_time(r["expiresMs"].as_u64().unwrap_or(0))
    );
    Ok(ExitCode::SUCCESS)
}

fn settings_cmd(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    match args.first().map(String::as_str) {
        None | Some("show") => show(ctx, "settings.get"),
        Some("set") => {
            let params = parse_params(&args[1..])?;
            let mut c = ctx.connect()?;
            match c.call("settings.set", Value::Object(params)) {
                Ok(v) => {
                    print_json(&v);
                    Ok(ExitCode::SUCCESS)
                }
                Err(e) => {
                    eprintln!("error: {e}");
                    Ok(ExitCode::FAILURE)
                }
            }
        }
        Some(other) => Err(format!("settings: unknown action {other} (try: show, set)")),
    }
}

fn show(ctx: &Ctx, method: &str) -> Result<ExitCode, String> {
    let mut c = ctx.connect()?;
    match c.call(method, json!({})) {
        Ok(v) => {
            print_json(&v);
            Ok(ExitCode::SUCCESS)
        }
        Err(e) => {
            eprintln!("error: {e}");
            Ok(ExitCode::FAILURE)
        }
    }
}

fn group(ctx: &Ctx, prefix: &str, args: &[String], default: &str) -> Result<ExitCode, String> {
    if args.len() > 1 {
        return Err(format!("{prefix}: unexpected argument {:?}", args[1]));
    }
    let action = args.first().map(String::as_str).unwrap_or(default);
    let allowed = match prefix {
        "vm" => ["status", "start", "stop", "shutdown", "reset"].as_slice(),
        "app" => ["info", "start", "restart", "exits"].as_slice(),
        "installer" => ["status", "install", "upgrade", "cancel", "retry"].as_slice(),
        _ => [].as_slice(),
    };
    if !allowed.contains(&action) {
        return Err(format!("{prefix}: unknown action {action} (try: {})", allowed.join(", ")));
    }
    show(ctx, &format!("{prefix}.{action}"))
}

fn field<'a>(v: &'a Value, path: &str) -> Option<&'a Value> {
    let mut cur = v;
    for part in path.split('.') {
        cur = cur.get(part)?;
    }
    Some(cur)
}

fn wait(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    if args.len() < 3 {
        return Err("wait needs METHOD FIELD VALUE".into());
    }
    let method = &args[0];
    let path = &args[1];
    let expected = &args[2];
    let mut timeout = Duration::from_secs(60);
    let mut params = Map::new();
    let mut i = 3;
    while i < args.len() {
        if args[i] == "--timeout" {
            let secs = args
                .get(i + 1)
                .ok_or("--timeout needs a value")?
                .parse::<u64>()
                .map_err(|e| format!("--timeout: {e}"))?;
            timeout = Duration::from_secs(secs.clamp(1, 3600));
            i += 2;
        } else if let Some((k, v)) = args[i].split_once('=') {
            params.insert(k.into(), Value::String(v.into()));
            i += 1;
        } else {
            return Err(format!("wait: unknown argument {}", args[i]));
        }
    }
    let mut c = ctx.connect()?;
    let deadline = Instant::now() + timeout;
    loop {
        let v = c.call(method, Value::Object(params.clone())).map_err(|e| e.to_string())?;
        let got = field(&v, path)
            .map(|v| v.as_str().map(str::to_string).unwrap_or_else(|| v.to_string()))
            .unwrap_or_default();
        if got == *expected {
            println!("{method} {path} = {got}");
            return Ok(ExitCode::SUCCESS);
        }
        if Instant::now() >= deadline {
            return Err(format!(
                "{method} {path} is {got:?}, expected {expected:?} after {}s",
                timeout.as_secs()
            ));
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

fn doctor(ctx: &Ctx) -> Result<ExitCode, String> {
    let mut c = ctx.connect()?;
    let checks = [
        ("devd.info", json!({})),
        ("packages.list", json!({})),
        ("app.info", json!({})),
        ("vm.status", json!({})),
        ("installer.status", json!({})),
    ];
    let mut failures = 0;
    for (method, params) in checks {
        match c.call(method, params) {
            Ok(v) => {
                println!("ok   {method}");
                if method == "vm.status" || method == "installer.status" {
                    print_json(&v);
                    let name = if method == "vm.status" {
                        v["vm"]["name"].as_str()
                    } else {
                        v["name"].as_str()
                    };
                    if name == Some("error") {
                        failures += 1;
                        println!("     ^ state is error");
                    }
                }
                if method == "packages.list" {
                    for p in v.as_array().into_iter().flatten() {
                        if p["installed"].as_bool() == Some(true)
                            && p["pinnedSigner"].as_bool() != Some(true)
                        {
                            failures += 1;
                            println!(
                                "     ^ {} is not signed by the pinned certificate",
                                p["package"].as_str().unwrap_or("?")
                            );
                        }
                    }
                }
                if method == "app.info" && v["devTools"].as_bool() != Some(true) {
                    failures += 1;
                    println!("     ^ app is not the dev flavor");
                }
            }
            Err(e) => {
                failures += 1;
                println!("FAIL $method: {e}");
            }
        }
    }
    if failures == 0 {
        println!("\nall checks answered");
        Ok(ExitCode::SUCCESS)
    } else {
        println!("\n{failures} check(s) failed");
        Ok(ExitCode::FAILURE)
    }
}

fn status(ctx: &Ctx) -> Result<ExitCode, String> {
    let mut c = ctx.connect()?;
    let info = c.call("devd.info", json!({})).map_err(|e| e.to_string())?;
    let pkgs = c.call("packages.list", json!({})).map_err(|e| e.to_string())?;
    println!(
        "devd {} at {} · listening on {} · off at {}",
        info["version"].as_str().unwrap_or("?"),
        ctx.addr,
        info["listener"]["addresses"],
        info["listener"]["offAtMs"].as_u64().map(fmt_time).unwrap_or_else(|| "-".into())
    );
    println!(
        "this client: {} · scopes {} · until {}",
        info["caller"]["name"],
        info["caller"]["scopes"],
        info["caller"]["expiresMs"].as_u64().map(fmt_time).unwrap_or_default()
    );
    for p in pkgs.as_array().into_iter().flatten() {
        if p["installed"].as_bool() == Some(true) {
            println!(
                "{} {} ({}) · pinned signer: {} · installer: {} · updated {}",
                p["package"].as_str().unwrap_or("?"),
                p["versionName"].as_str().unwrap_or("?"),
                p["versionCode"],
                p["pinnedSigner"],
                p["installer"].as_str().unwrap_or("-"),
                fmt_time(p["lastUpdateMs"].as_u64().unwrap_or(0))
            );
        } else {
            println!("{}: not installed", p["package"].as_str().unwrap_or("?"));
        }
    }
    if info["canInstall"].as_bool() != Some(true) {
        println!("note: devd may not install apps yet (allow it on the phone)");
    }
    match c.call("app.info", json!({})) {
        Ok(a) => println!(
            "koi: pid {} · up {}s · {} ({}) · dev tools {}",
            a["pid"],
            a["uptimeMs"].as_u64().unwrap_or(0) / 1000,
            a["versionName"].as_str().unwrap_or("?"),
            a["versionCode"],
            a["devTools"]
        ),
        Err(e) => println!("koi: {e}"),
    }
    if let Ok(s) = c.call("settings.get", json!({})) {
        println!(
            "settings: {} MiB · network {} · proxy {} · keep-awake {}",
            s["memoryMiB"], s["network"], s["socks5Delegate"], s["keepAwake"]
        );
    }
    Ok(ExitCode::SUCCESS)
}

fn methods(ctx: &Ctx, all: bool) -> Result<ExitCode, String> {
    let mut c = ctx.connect()?;
    let r = c.call("methods", json!({})).map_err(|e| e.to_string())?;
    if r["koiAvailable"].as_bool() != Some(true) {
        println!("(koiTerminal's methods are not listed: its dev build is not reachable)");
    }
    for m in r["methods"].as_array().into_iter().flatten() {
        let allowed = m["allowed"].as_bool() == Some(true);
        if !all && !allowed {
            continue;
        }
        let params: Vec<String> = m["params"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|p| {
                let mut s = format!(
                    "{}:{}",
                    p["name"].as_str().unwrap_or("?"),
                    p["type"].as_str().unwrap_or("?")
                );
                if p["required"].as_bool() == Some(true) {
                    s.push('!');
                }
                if p["secret"].as_bool() == Some(true) {
                    s.push_str("(secret)");
                }
                s
            })
            .collect();
        println!(
            "{}{:<24} {:<13} {}{}",
            if allowed { " " } else { "-" },
            m["name"].as_str().unwrap_or("?"),
            m["scope"].as_str().unwrap_or("?"),
            m["description"].as_str().unwrap_or(""),
            if params.is_empty() {
                String::new()
            } else {
                format!("\n{:40}{}", "", params.join(" "))
            }
        );
    }
    Ok(ExitCode::SUCCESS)
}

fn logs(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    let mut follow = false;
    let mut source = "devd";
    let mut extra = Map::new();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-f" | "--follow" => follow = true,
            "app" | "devd" => source = args[i].as_str(),
            "--level" => {
                extra
                    .insert("level".into(), json!(args.get(i + 1).ok_or("--level needs a value")?));
                i += 1;
            }
            "--grep" => {
                extra.insert("grep".into(), json!(args.get(i + 1).ok_or("--grep needs a value")?));
                i += 1;
            }
            a => return Err(format!("logs: unknown argument {a}")),
        }
        i += 1;
    }
    let level_filter = extra.get("level").and_then(Value::as_str).map(str::to_string);
    let grep_filter = extra.get("grep").and_then(Value::as_str).map(str::to_string);
    let mut c = ctx.connect()?;
    let mut after = 0u64;
    loop {
        let mut params = extra.clone();
        params.insert("after".into(), json!(after));
        params.insert("max".into(), json!(1000));
        let method;
        if source == "app" {
            method = "app.logs";
            // app.logs is a smaller ring and has no level/grep parameters.
            params.remove("level");
            params.remove("grep");
            params.insert("max".into(), json!(500));
        } else {
            method = "devd.logs";
            params.insert("kind".into(), json!("log"));
        }
        let r = c.call(method, Value::Object(params)).map_err(|e| e.to_string())?;
        if r["dropped"].as_u64().unwrap_or(0) > 0 {
            println!("[{} entries dropped by the journal]", r["dropped"]);
        }
        for e in r["entries"].as_array().into_iter().flatten() {
            if source == "app" {
                if let Some(level) = &level_filter {
                    let order = ["debug", "info", "warn", "error"];
                    let want = order.iter().position(|v| v == level).unwrap_or(0);
                    let got =
                        order.iter().position(|v| Some(*v) == e["level"].as_str()).unwrap_or(0);
                    if got < want {
                        continue;
                    }
                }
                if let Some(grep) = &grep_filter {
                    let hay = format!(
                        "{} {} {}",
                        e["component"].as_str().unwrap_or(""),
                        e["message"].as_str().unwrap_or(""),
                        e["level"].as_str().unwrap_or("")
                    );
                    if !hay.to_lowercase().contains(&grep.to_lowercase()) {
                        continue;
                    }
                }
            }
            println!(
                "{} {:<5} {:<10} {}",
                fmt_time_ms(e["unixMs"].as_u64().unwrap_or(0)),
                e["level"].as_str().unwrap_or(""),
                e["component"].as_str().unwrap_or(""),
                e["message"].as_str().unwrap_or("")
            );
        }
        after = r["nextSeq"].as_u64().unwrap_or(after);
        if !follow {
            return Ok(ExitCode::SUCCESS);
        }
        std::thread::sleep(Duration::from_millis(700));
    }
}

fn find_install<'a>(list: &'a Value, install_id: Option<i64>, upload: &str) -> Option<&'a Value> {
    list.as_array().into_iter().flatten().find(|r| {
        if let Some(id) = install_id {
            r["installId"].as_i64() == Some(id)
        } else {
            r["uploadId"].as_str() == Some(upload)
        }
    })
}

fn update(ctx: &Ctx, apks: &[String]) -> Result<ExitCode, String> {
    use base64::Engine;
    if apks.is_empty() {
        return Err("update needs at least one APK".into());
    }
    for path in apks {
        let bytes = std::fs::read(path).map_err(|e| format!("{path}: {e}"))?;
        let sha = sha256_hex(&bytes);
        let mut c = ctx.connect()?;
        c.set_read_timeout(Duration::from_secs(30));
        let t0 = Instant::now();
        let begin = c
            .call("update.begin", json!({ "size": bytes.len(), "sha256": sha }))
            .map_err(|e| e.to_string())?;
        let upload = begin["uploadId"].as_str().ok_or("no uploadId")?.to_string();
        // Base64 grows by 4/3. devd advertises its decoded cap; keep the
        // encoded string comfortably under the API's 256 KiB string limit.
        let chunk = 128 * 1024;
        let mut off = 0usize;
        while off < bytes.len() {
            let end = (off + chunk).min(bytes.len());
            c.call(
                "update.chunk",
                json!({
                    "uploadId": upload,
                    "offset": off,
                    "data": base64::engine::general_purpose::STANDARD.encode(&bytes[off..end]),
                }),
            )
            .map_err(|e| e.to_string())?;
            off = end;
        }
        eprintln!(
            "{path}: uploaded {} KiB in {:.1}s; verifying and installing",
            bytes.len() / 1024,
            t0.elapsed().as_secs_f64()
        );
        let commit = c.call("update.commit", json!({ "uploadId": upload }));
        let mut install_id = None;
        let mut self_update = false;
        match commit {
            Ok(rec) => {
                install_id = rec["installId"].as_i64();
                self_update = rec["package"].as_str() == Some(DEVD_PACKAGE);
            }
            Err(e) if e.code == "local" => {
                // The commit may have reached devd before the socket died (a
                // devd self-update does exactly that). Reconnect and look the
                // upload up; never replay the commit.
                eprintln!("{path}: connection lost during commit; reconciling by upload id");
            }
            Err(e) => return Err(format!("{path}: {e}")),
        }
        drop(c);
        let deadline = Instant::now() + Duration::from_secs(300);
        let mut told = false;
        loop {
            std::thread::sleep(Duration::from_millis(800));
            if Instant::now() > deadline {
                let what = install_id
                    .map(|id| format!("install {id}"))
                    .unwrap_or_else(|| "the commit".into());
                return Err(format!(
                    "{path}: {what} did not finish in 5 minutes (outcome unknown)"
                ));
            }
            // A devd self-update ends devd mid-way; reconnect until it is back.
            let Ok(mut c) = ctx.connect() else {
                continue;
            };
            c.set_read_timeout(Duration::from_secs(10));
            let Ok(list) = c.call("update.status", json!({})) else {
                continue;
            };
            let Some(r) = find_install(&list, install_id, &upload) else {
                continue;
            };
            if install_id.is_none() {
                install_id = r["installId"].as_i64();
                self_update = r["package"].as_str() == Some(DEVD_PACKAGE);
            }
            match r["state"].as_str() {
                Some("success") => {
                    println!(
                        "{path}: installed {} {} ({}) in {:.1}s",
                        r["package"].as_str().unwrap_or("?"),
                        r["versionName"].as_str().unwrap_or("?"),
                        r["versionCode"],
                        t0.elapsed().as_secs_f64()
                    );
                    break;
                }
                Some("failure") => {
                    return Err(format!(
                        "{path}: install failed: {}",
                        r["message"].as_str().unwrap_or("")
                    ));
                }
                Some("pendingUserAction") if !told => {
                    eprintln!(
                        "{path}: Android wants a tap on the phone (first install by devd); waiting"
                    );
                    told = true;
                }
                _ if self_update && !told => {
                    eprintln!("{path}: devd is replacing itself; waiting for it to come back");
                    told = true;
                }
                _ => {}
            }
        }
    }
    Ok(ExitCode::SUCCESS)
}

fn top(ctx: &Ctx, once: bool) -> Result<ExitCode, String> {
    let mut c = ctx.connect()?;
    loop {
        let m = c.call("metrics.current", json!({})).map_err(|e| e.to_string())?;
        if !once {
            print!("\x1b[2J\x1b[H");
        }
        println!(
            "{}  koi app: {}",
            fmt_time_ms(m["unixMs"].as_u64().unwrap_or(0)),
            if m["koiConnected"].as_bool() == Some(true) {
                "connected"
            } else {
                "(not bound: call app.start)"
            },
        );
        println!(
            "\n{:<12} {:>7} {:>10} {:>10} {:>7} {:>8} {:>5}",
            "PROCESS", "PID", "RSS", "PSS", "CPU%", "THREADS", "FDS"
        );
        for p in m["processes"].as_array().into_iter().flatten() {
            let kb = |k: &str| p[k].as_u64().map(|v| format!("{} KiB", v)).unwrap_or_default();
            println!(
                "{:<12} {:>7} {:>10} {:>10} {:>7} {:>8} {:>5}",
                p["name"].as_str().unwrap_or("?"),
                p["pid"],
                kb("rssKb"),
                kb("pssKb"),
                p["cpuPct"].as_f64().map(|v| format!("{v:.1}")).unwrap_or_else(|| "-".into()),
                p["threads"],
                p["fds"]
            );
        }
        if once {
            return Ok(ExitCode::SUCCESS);
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// UTC "YYYY-MM-DD HH:MM", without a date library.
fn fmt_time(ms: u64) -> String {
    let (y, mo, d, h, mi, _, _) = civil(ms);
    format!("{y:04}-{mo:02}-{d:02} {h:02}:{mi:02}Z")
}

/// UTC "HH:MM:SS.mmm".
fn fmt_time_ms(ms: u64) -> String {
    let (_, _, _, h, mi, s, milli) = civil(ms);
    format!("{h:02}:{mi:02}:{s:02}.{milli:03}")
}

fn civil(ms: u64) -> (i64, u32, u32, u32, u32, u32, u32) {
    let secs = (ms / 1000) as i64;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // Howard Hinnant's days-to-civil.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d, (rem / 3600) as u32, (rem % 3600 / 60) as u32, (rem % 60) as u32, (ms % 1000) as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn params() {
        let m = parse_params(&[
            "id=3".into(),
            "keys:=[\"ctrl+c\",\"enter\"]".into(),
            "text=a=b".into(),
        ])
        .unwrap();
        assert_eq!(m["id"], json!("3"));
        assert_eq!(m["keys"], json!(["ctrl+c", "enter"]));
        assert_eq!(m["text"], json!("a=b"));
        assert!(parse_params(&["nope".into()]).is_err());
    }

    #[test]
    fn finds_an_install_by_id_or_upload() {
        let list = json!([
            { "installId": 7, "uploadId": "a", "state": "installing" },
            { "installId": 8, "uploadId": "b", "state": "success" }
        ]);
        assert_eq!(find_install(&list, Some(8), "a").unwrap()["uploadId"], "b");
        assert_eq!(find_install(&list, None, "a").unwrap()["installId"], 7);
        assert!(find_install(&list, Some(9), "a").is_none());
        assert!(find_install(&list, None, "missing").is_none());
    }

    #[test]
    fn field_paths() {
        let v = json!({ "vm": { "name": "running" }, "n": 1 });
        assert_eq!(field(&v, "vm.name"), Some(&json!("running")));
        assert_eq!(field(&v, "n"), Some(&json!(1)));
        assert_eq!(field(&v, "vm.missing"), None);
        assert_eq!(field(&v, "missing"), None);
    }

    #[test]
    fn times() {
        assert_eq!(fmt_time(0), "1970-01-01 00:00Z");
        assert_eq!(fmt_time_ms(1_234), "00:00:01.234");
    }
}
