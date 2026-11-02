//! koi-ctl: drives a phone running the koiTerminal dev agent (devd).
//!
//! Every operation is a typed method of the dev API (see `koi-ctl methods`);
//! there is no shell. The connection is TLS 1.3 with this laptop's client
//! certificate, and devd's certificate is pinned at pairing.
#![allow(unsafe_code)] // termios/poll for the interactive console; isolated below.

mod conn;
mod store;

use std::io::{IsTerminal, Read, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{Map, Value, json};

use base64::Engine;
use conn::{Conn, pairing_code, sha256_hex};
use store::{Hosts, Identity};

const DEFAULT_PORT: u16 = 7391;
const DEVD_PACKAGE: &str = "io.github.franticplant.koiterminal.devd";
/// update.chunk requests kept in flight; 4 fills a ~3 MiB/s, ~40 ms link.
const UPDATE_IN_FLIGHT: usize = 4;

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
  push [--profile P] FILE...
                          upload files into a boot profile, resuming interrupted uploads
  top [--once]            live RAM/CPU of devd and the koiTerminal app
  vm [status|start|restart|stop|shutdown|reset|exits]
                          VM lifecycle, state and exit history
  console [--dump|--follow]
                          serial console: interactive (Ctrl-] q detaches), dump or follow
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
Scopes: read, read.content, operate, secrets, lifecycle, scenario, update,
        boot.push, boot.config, guest.console.";

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
        "vm" => vm_cmd(&ctx, &rest),
        "console" => console_cmd(&ctx, &rest),
        "app" => group(&ctx, "app", &rest, "info"),
        "installer" => group(&ctx, "installer", &rest, "status"),
        "image" => show(&ctx, "image.info"),
        "config" => show(&ctx, "config.get"),
        "settings" => settings_cmd(&ctx, &rest),
        "ports" => show(&ctx, "ports.list"),
        "wait" => wait(&ctx, &rest),
        "doctor" => doctor(&ctx),
        "update" => update(&ctx, &rest),
        "push" => push(&ctx, &rest),
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
            m.insert(k.to_string(), Value::String(read_value(v)?));
        } else {
            return Err(format!("argument {a:?} is not k=v or k:=JSON"));
        }
    }
    Ok(m)
}

/// `@path` reads a string from a file; everything else is literal. This makes
/// `profile.put name=k0 json=@k0.json` possible without shell quoting.
fn read_value(v: &str) -> Result<String, String> {
    if let Some(path) = v.strip_prefix('@') {
        std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))
    } else {
        Ok(v.to_string())
    }
}

fn print_json(v: &Value) {
    println!("{}", serde_json::to_string_pretty(v).unwrap_or_default());
}

fn pair(ctx: &mut Ctx, args: &[String]) -> Result<ExitCode, String> {
    let mut name = client_name();
    let mut scopes: Vec<String> = [
        "read",
        "read.content",
        "operate",
        "secrets",
        "lifecycle",
        "scenario",
        "update",
        "boot.push",
        "boot.config",
        "guest.console",
    ]
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
        "vm" => ["status", "start", "restart", "stop", "shutdown", "reset", "exits"].as_slice(),
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

/// A one-line progress bar. The host test covers the formatting; the caller
/// decides how often to draw it.
struct Progress {
    label: String,
    total: u64,
    started: Instant,
    last_draw: Instant,
    next_pct: u64,
    tty: bool,
}

impl Progress {
    fn new(label: &str, total: u64) -> Progress {
        let label: String = label.chars().take(20).collect();
        Progress {
            label,
            total,
            started: Instant::now(),
            last_draw: Instant::now() - Duration::from_secs(1),
            next_pct: 0,
            tty: std::io::stdout().is_terminal(),
        }
    }

    fn update(&mut self, done: u64) {
        if self.tty {
            if self.last_draw.elapsed() < Duration::from_millis(100) {
                return;
            }
            self.draw(done, false);
        } else {
            let pct = done.saturating_mul(100).checked_div(self.total).unwrap_or(100);
            if pct >= self.next_pct {
                self.draw(done, false);
                self.next_pct = (pct / 10 + 1) * 10;
            }
        }
    }

    fn finish(&mut self, done: u64) {
        self.draw(done, true);
    }

    fn draw(&mut self, done: u64, final_line: bool) {
        self.last_draw = Instant::now();
        let line = format_progress(&self.label, done, self.total, self.started.elapsed());
        if self.tty {
            print!("\r{line}\x1b[K");
            if final_line {
                println!();
            }
            let _ = std::io::Write::flush(&mut std::io::stdout());
        } else {
            println!("{line}");
        }
    }
}

fn format_progress(label: &str, done: u64, total: u64, elapsed: Duration) -> String {
    let pct = done.saturating_mul(100).checked_div(total).unwrap_or(100);
    let width = 20usize;
    let filled = ((pct as usize) * width / 100).min(width);
    let bar = format!("{}{}", "#".repeat(filled), "-".repeat(width - filled));
    let secs = elapsed.as_secs_f64();
    let rate = if secs > 0.0 { done as f64 / secs } else { 0.0 };
    let eta = if rate > 0.0 {
        let left = total.saturating_sub(done) as f64 / rate;
        format!("{}s", left.ceil() as u64)
    } else {
        "?".into()
    };
    let rate_text = if rate >= 10.0 * 1024.0 * 1024.0 {
        format!("{:.0} MiB/s", rate / 1024.0 / 1024.0)
    } else {
        format!("{:.1} MiB/s", rate / 1024.0 / 1024.0)
    };
    format!(
        "{:<20} [{}] {:3}%  {:.1}/{:.1} MiB  {}  ETA {}",
        label,
        bar,
        pct,
        done as f64 / 1024.0 / 1024.0,
        total as f64 / 1024.0 / 1024.0,
        rate_text,
        eta
    )
}

fn push_key(addr: &str, profile: &str, name: &str, sha256: &str) -> String {
    format!("{addr}|{profile}|{name}|{sha256}")
}

fn push_book_path() -> Result<PathBuf, String> {
    Ok(store::dir()?.join("pushes.json"))
}

fn load_book(path: &Path) -> Result<Map<String, Value>, String> {
    match std::fs::read_to_string(path) {
        Ok(s) => match serde_json::from_str::<Value>(&s) {
            Ok(Value::Object(m)) => Ok(m),
            Ok(_) => Err(format!("{}: not a JSON object", path.display())),
            Err(e) => Err(format!("{}: {e}", path.display())),
        },
        Err(_) => Ok(Map::new()),
    }
}

fn save_book(path: &Path, book: &Map<String, Value>) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    let text = serde_json::to_string_pretty(&Value::Object(book.clone())).unwrap_or_default();
    std::fs::write(path, text).map_err(|e| format!("{}: {e}", path.display()))
}

fn push(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    let mut profile = "default".to_string();
    let mut files = Vec::new();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--profile" => {
                profile = args.get(i + 1).ok_or("push: --profile needs a value")?.clone();
                i += 1;
            }
            a if a.starts_with('-') => return Err(format!("push: unknown flag {a}")),
            a => files.push(a.to_string()),
        }
        i += 1;
    }
    if files.is_empty() {
        return Err("push needs at least one file".into());
    }
    for file in files {
        push_one(ctx, &profile, &file)?;
    }
    Ok(ExitCode::SUCCESS)
}

fn push_one(ctx: &Ctx, profile: &str, path: &str) -> Result<(), String> {
    let source = Path::new(path);
    let meta = std::fs::metadata(source).map_err(|e| format!("{path}: {e}"))?;
    if !meta.is_file() {
        return Err(format!("{path}: not a regular file"));
    }
    let name = source
        .file_name()
        .and_then(|s| s.to_str())
        .ok_or_else(|| format!("{path}: no file name"))?
        .to_string();
    let size = meta.len();
    let sha = sha256_file(source)?;
    let book_path = push_book_path()?;
    let mut book = load_book(&book_path)?;
    let key = push_key(&ctx.addr, profile, &name, &sha);

    let mut c = ctx.connect()?;
    let mut upload_id: Option<String> = None;
    let mut received = 0u64;
    if let Some(id) = book.get(&key).and_then(|v| v["uploadId"].as_str()) {
        match c.call("push.status", json!({ "id": id })) {
            Ok(s) => {
                let r = s["received"].as_u64().unwrap_or(0);
                if s["size"].as_u64() == Some(size)
                    && s["name"].as_str() == Some(&name)
                    && r <= size
                {
                    received = r;
                    upload_id = Some(id.to_string());
                    if r > 0 {
                        eprintln!("{name}: resuming at {:.1} MiB", r as f64 / 1024.0 / 1024.0);
                    }
                } else {
                    let _ = c.call("push.abort", json!({ "id": id }));
                }
            }
            Err(e) if e.code == "not_found" => {}
            Err(e) => return Err(format!("{name}: cannot check the previous upload: {e}")),
        }
    }

    let chunk_bytes;
    if upload_id.is_none() {
        let begin = c
            .call(
                "push.begin",
                json!({ "profile": profile, "name": name, "size": size, "sha256": sha }),
            )
            .map_err(|e| format!("{name}: begin failed: {e}"))?;
        let id = begin["id"].as_str().ok_or("push.begin returned no id")?.to_string();
        chunk_bytes = begin["chunkBytes"].as_u64().unwrap_or(1024 * 1024) as usize;
        book.insert(key.clone(), json!({ "uploadId": id, "updatedMs": now_ms() }));
        save_book(&book_path, &book)?;
        upload_id = Some(id);
    } else {
        chunk_bytes = 1024 * 1024;
    }
    let id = upload_id.expect("upload id set above");
    let already_complete = received == size;
    let mut progress = Progress::new(&name, size);
    if !already_complete {
        progress.update(received);
    }

    let mut file = std::fs::File::open(source).map_err(|e| format!("{path}: {e}"))?;
    let mut buf = vec![0u8; chunk_bytes];
    let mut failures = 0;
    while received < size {
        use std::io::{Seek, SeekFrom};
        file.seek(SeekFrom::Start(received)).map_err(|e| format!("{path}: {e}"))?;
        let want = chunk_bytes.min((size - received) as usize);
        file.read_exact(&mut buf[..want]).map_err(|e| format!("{path}: {e}"))?;
        let data = base64::engine::general_purpose::STANDARD.encode(&buf[..want]);
        match c.call("push.chunk", json!({ "id": id, "offset": received, "data": data })) {
            Ok(v) => {
                received = v["received"].as_u64().unwrap_or(received + want as u64);
                failures = 0;
                progress.update(received);
            }
            Err(e) if e.code == "local" => {
                failures += 1;
                if failures > 5 {
                    return Err(format!("{name}: connection lost while uploading: {e}"));
                }
                eprintln!("{name}: connection lost; reconnecting and resuming");
                std::thread::sleep(Duration::from_millis(800));
                c = ctx.connect()?;
                let s = c
                    .call("push.status", json!({ "id": id }))
                    .map_err(|e| format!("{name}: cannot resume: {e}"))?;
                received = s["received"].as_u64().unwrap_or(received);
                progress.update(received);
            }
            Err(e) => return Err(format!("{name}: chunk at {received} rejected: {e}")),
        }
    }

    let mut commit_failures = 0;
    let done = loop {
        match c.call("push.commit", json!({ "id": id })) {
            Ok(v) => break v,
            Err(e) if e.code == "local" && commit_failures < 5 => {
                commit_failures += 1;
                std::thread::sleep(Duration::from_millis(800));
                c = ctx.connect()?;
            }
            Err(e) => return Err(format!("{name}: commit failed: {e}")),
        }
    };
    if !already_complete {
        progress.finish(size);
    }
    // Keep the upload id: a rerun of the same file should confirm with the app and finish, not
    // upload it again. A different file hash gets a different key.
    book.insert(key.clone(), json!({ "uploadId": id, "committed": true, "updatedMs": now_ms() }));
    save_book(&book_path, &book)?;
    println!(
        "{name}: committed {} ({} bytes, sha256 {})",
        done["path"].as_str().unwrap_or("?"),
        done["size"].as_u64().unwrap_or(size),
        done["sha256"].as_str().unwrap_or(&sha)
    );
    Ok(())
}

fn sha256_file(path: &Path) -> Result<String, String> {
    let mut file = std::fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut ctx = ring::digest::Context::new(&ring::digest::SHA256);
    let mut buf = [0u8; 128 * 1024];
    loop {
        let n = file.read(&mut buf).map_err(|e| format!("{}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        ctx.update(&buf[..n]);
    }
    Ok(ctx.finish().as_ref().iter().map(|b| format!("{b:02x}")).collect())
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
        // devd advertises its decoded chunk cap; base64 grows by 4/3, so stay
        // well under the API's string limit whatever it says.
        let chunk =
            begin["chunkBytes"].as_u64().unwrap_or(128 * 1024).clamp(4096, 1024 * 1024) as usize;
        let label = std::path::Path::new(path).file_name().and_then(|s| s.to_str()).unwrap_or(path);
        let mut progress = Progress::new(label, bytes.len() as u64);
        // Keep a few chunks in flight: waiting for each reply before sending
        // the next leaves the link idle for a round trip per chunk. devd
        // handles a connection's requests in order, so offsets still arrive
        // in sequence; the first error ends the upload.
        let (mut sent, mut acked, mut in_flight) = (0usize, 0usize, 0usize);
        while acked < bytes.len() {
            while sent < bytes.len() && in_flight < UPDATE_IN_FLIGHT {
                let end = (sent + chunk).min(bytes.len());
                c.send(
                    "update.chunk",
                    json!({
                        "uploadId": upload,
                        "offset": sent,
                        "data": base64::engine::general_purpose::STANDARD.encode(&bytes[sent..end]),
                    }),
                )
                .map_err(|e| e.to_string())?;
                sent = end;
                in_flight += 1;
            }
            let r = c.recv().map_err(|e| e.to_string())?;
            in_flight -= 1;
            acked = r["received"]
                .as_u64()
                .map(|n| n as usize)
                .unwrap_or((acked + chunk).min(bytes.len()));
            progress.update(acked as u64);
        }
        progress.finish(bytes.len() as u64);
        eprintln!("{path}: verifying and installing");
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

fn vm_cmd(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    let action = args.first().map(String::as_str).unwrap_or("status");
    if !matches!(action, "status" | "start" | "restart" | "stop" | "shutdown" | "reset" | "exits") {
        return Err(format!(
            "vm: unknown action {action} (try: status, start, restart, stop, shutdown, reset, exits)"
        ));
    }
    if action == "start" || action == "restart" {
        let mut profile: Option<String> = None;
        let mut attach = false;
        let mut i = 1;
        while i < args.len() {
            match args[i].as_str() {
                "--profile" => {
                    profile =
                        Some(args.get(i + 1).ok_or("vm start: --profile needs a value")?.clone());
                    i += 2;
                }
                "--console" => {
                    attach = true;
                    i += 1;
                }
                a => return Err(format!("vm {action}: unknown argument {a}")),
            }
        }
        let mut params = Map::new();
        if let Some(p) = profile {
            params.insert("profile".into(), Value::String(p));
        }
        let mut c = ctx.connect()?;
        let v =
            c.call(&format!("vm.{action}"), Value::Object(params)).map_err(|e| e.to_string())?;
        print_json(&v);
        if attach {
            return console_cmd(ctx, &[]);
        }
        return Ok(ExitCode::SUCCESS);
    }
    if args.len() > 1 {
        return Err(format!("vm: unexpected argument {:?}", args[1]));
    }
    show(ctx, &format!("vm.{action}"))
}

fn console_cmd(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    let mut dump = false;
    let mut follow = false;
    for a in args {
        match a.as_str() {
            "--dump" => dump = true,
            "--follow" => follow = true,
            other => return Err(format!("console: unknown argument {other}")),
        }
    }
    if dump && follow {
        return Err("console: --dump and --follow are different modes".into());
    }
    if dump {
        return console_dump(ctx);
    }
    if follow {
        let stop = Arc::new(AtomicBool::new(false));
        let code = console_reader(ctx.addr.clone(), stop)?;
        return Ok(ExitCode::from(code.clamp(0, 255) as u8));
    }
    console_interactive(ctx)
}

fn console_dump(ctx: &Ctx) -> Result<ExitCode, String> {
    use base64::Engine;
    let mut c = ctx.connect()?;
    let r = c
        .call("console.read", json!({ "max": 1024 * 1024, "waitMs": 0 }))
        .map_err(|e| e.to_string())?;
    let data = base64::engine::general_purpose::STANDARD
        .decode(r["data"].as_str().unwrap_or(""))
        .map_err(|e| format!("console.read returned bad base64: {e}"))?;
    std::io::stdout().write_all(&data).map_err(|e| format!("stdout: {e}"))?;
    if !data.ends_with(b"\n") {
        println!();
    }
    Ok(ExitCode::SUCCESS)
}

static SAVED_TERMIOS: AtomicPtr<libc::termios> = AtomicPtr::new(std::ptr::null_mut());

extern "C" fn restore_on_signal(sig: libc::c_int) {
    let p = SAVED_TERMIOS.load(Ordering::SeqCst);
    if !p.is_null() {
        unsafe {
            libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, p);
        }
    }
    unsafe { libc::_exit(128 + sig) }
}

struct RawTerminal {
    saved: libc::termios,
}

impl RawTerminal {
    fn enable() -> Result<RawTerminal, String> {
        if !std::io::stdin().is_terminal() {
            return Err("console needs a terminal; use --dump or --follow".into());
        }
        let mut saved: libc::termios = unsafe { std::mem::zeroed() };
        if unsafe { libc::tcgetattr(libc::STDIN_FILENO, &mut saved) } != 0 {
            return Err("cannot read the terminal settings".into());
        }
        let mut raw = saved;
        unsafe { libc::cfmakeraw(&mut raw) };
        // Keep output post-processing so local messages and LF look right.
        raw.c_oflag |= libc::OPOST;
        if unsafe { libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &raw) } != 0 {
            return Err("cannot put the terminal in raw mode".into());
        }
        let leaked = Box::into_raw(Box::new(saved));
        SAVED_TERMIOS.store(leaked, Ordering::SeqCst);
        unsafe {
            libc::signal(libc::SIGTERM, restore_on_signal as *const () as libc::sighandler_t);
            libc::signal(libc::SIGHUP, restore_on_signal as *const () as libc::sighandler_t);
            libc::signal(libc::SIGQUIT, restore_on_signal as *const () as libc::sighandler_t);
        }
        Ok(RawTerminal { saved })
    }
}

impl Drop for RawTerminal {
    fn drop(&mut self) {
        SAVED_TERMIOS.store(std::ptr::null_mut(), Ordering::SeqCst);
        unsafe {
            libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &self.saved);
        }
    }
}

fn vm_alive(status: &Value) -> bool {
    matches!(status["vm"]["name"].as_str(), Some("starting" | "running" | "stopping" | "rebooting"))
}

fn console_reader(addr: String, stop: Arc<AtomicBool>) -> Result<i32, String> {
    use base64::Engine;
    let mut c = Ctx { addr: addr.clone(), hosts: Hosts::load()? }.connect()?;
    let mut cursor = 0u64;
    let mut reconnect = 0;
    loop {
        if stop.load(Ordering::SeqCst) {
            return Ok(0);
        }
        let r = match c
            .call("console.read", json!({ "after": cursor, "max": 64 * 1024, "waitMs": 1000 }))
        {
            Ok(v) => v,
            Err(e) if e.code == "local" && reconnect < 5 => {
                reconnect += 1;
                std::thread::sleep(Duration::from_millis(500));
                c = Ctx { addr: addr.clone(), hosts: Hosts::load()? }.connect()?;
                continue;
            }
            Err(e) => return Err(e.to_string()),
        };
        reconnect = 0;
        if r["gap"].as_bool() == Some(true) {
            let start = r["start"].as_u64().unwrap_or(cursor);
            let dropped = start.saturating_sub(cursor);
            print!("\r\n[... {dropped} bytes dropped ...]\r\n");
        }
        let data = base64::engine::general_purpose::STANDARD
            .decode(r["data"].as_str().unwrap_or(""))
            .map_err(|e| format!("console.read returned bad base64: {e}"))?;
        if !data.is_empty() {
            std::io::stdout()
                .write_all(&data)
                .and_then(|_| std::io::stdout().flush())
                .map_err(|e| format!("stdout: {e}"))?;
        }
        cursor = r["cursor"].as_u64().unwrap_or(cursor);
        let status = match c.call("vm.status", json!({})) {
            Ok(v) => v,
            Err(e) if e.code == "local" => continue,
            Err(e) => return Err(e.to_string()),
        };
        if !vm_alive(&status) {
            let vm = status["vm"]["name"].as_str().unwrap_or("unknown");
            let last = &status["lastExit"];
            if last.is_object() {
                let name = last["name"].as_str().unwrap_or("UNKNOWN");
                let reason = last["reason"].as_i64().unwrap_or(-1);
                let uptime = last["uptimeMs"].as_u64().unwrap_or(0) / 1000;
                println!("\r\n[vm {vm}: {name} (reason {reason}), up {uptime}s]");
                return Ok(if name == "SHUTDOWN" { 0 } else { 1 });
            }
            println!("\r\n[vm {vm}]");
            return Ok(if vm == "stopped" { 0 } else { 1 });
        }
    }
}

fn console_interactive(ctx: &Ctx) -> Result<ExitCode, String> {
    use base64::Engine;
    let raw = RawTerminal::enable()?;
    let stop = Arc::new(AtomicBool::new(false));
    let stop_reader = stop.clone();
    let addr = ctx.addr.clone();
    let reader = std::thread::spawn(move || console_reader(addr, stop_reader));

    let mut writer = ctx.connect()?;
    let mut pending = Vec::new();
    let mut last_send = Instant::now();
    let mut escape = false;
    let mut code = 0i32;
    loop {
        if reader.is_finished() {
            break;
        }
        let mut pfd = libc::pollfd { fd: libc::STDIN_FILENO, events: libc::POLLIN, revents: 0 };
        let n = unsafe { libc::poll(&mut pfd, 1, 20) };
        if n > 0 && (pfd.revents & libc::POLLIN) != 0 {
            let mut buf = [0u8; 256];
            let got = unsafe {
                libc::read(libc::STDIN_FILENO, buf.as_mut_ptr() as *mut libc::c_void, buf.len())
            };
            if got <= 0 {
                stop.store(true, Ordering::SeqCst);
                break;
            }
            for &b in &buf[..got as usize] {
                if escape {
                    if b == b'q' {
                        stop.store(true, Ordering::SeqCst);
                        escape = false;
                        break;
                    }
                    pending.push(0x1d);
                    escape = false;
                }
                if b == 0x1d {
                    escape = true;
                } else {
                    pending.push(b);
                }
            }
        }
        if !pending.is_empty()
            && (pending.len() >= 4096 || last_send.elapsed() >= Duration::from_millis(20))
        {
            let data = base64::engine::general_purpose::STANDARD.encode(&pending);
            match writer.call("console.write", json!({ "data": data, "base64": true })) {
                Ok(_) => pending.clear(),
                Err(e) if e.code == "local" => {
                    writer = ctx.connect()?;
                }
                Err(e) => {
                    eprintln!("console.write: {e}");
                    pending.clear();
                }
            }
            last_send = Instant::now();
        }
        if stop.load(Ordering::SeqCst) {
            break;
        }
    }
    if !pending.is_empty() {
        let data = base64::engine::general_purpose::STANDARD.encode(&pending);
        let _ = writer.call("console.write", json!({ "data": data, "base64": true }));
    }
    stop.store(true, Ordering::SeqCst);
    if let Ok(result) = reader.join() {
        match result {
            Ok(c) => code = c,
            Err(e) => eprintln!("console: {e}"),
        }
    }
    drop(raw);
    println!("\r\ndetached");
    Ok(ExitCode::from(code.clamp(0, 255) as u8))
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
    fn progress_line() {
        let line = format_progress("vmlinuz-virt", 21_400_000, 41_000_000, Duration::from_secs(6));
        assert!(line.contains("52%"), "{line}");
        assert!(line.contains("20.4/39.1 MiB"), "{line}");
        assert!(line.contains("MiB/s"), "{line}");
        assert!(line.contains("ETA "), "{line}");
        let full = format_progress("x", 10, 10, Duration::from_secs(1));
        assert!(full.contains("100%"), "{full}");
        assert!(full.contains("ETA 0s"), "{full}");
    }

    #[test]
    fn push_book_round_trips() {
        let dir = std::env::temp_dir().join(format!("koi-push-{}", now_ms()));
        let path = dir.join("pushes.json");
        let key = push_key("p8:7390", "k0", "vmlinuz", "abc");
        let mut book = Map::new();
        book.insert(key.clone(), json!({ "uploadId": "upload-1" }));
        save_book(&path, &book).unwrap();
        let back = load_book(&path).unwrap();
        assert_eq!(back[&key]["uploadId"], "upload-1");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn at_file_values() {
        let path = std::env::temp_dir().join(format!("koi-arg-{}.json", now_ms()));
        std::fs::write(&path, "{\"base\":\"none\"}").unwrap();
        let m = parse_params(&[format!("json=@{}", path.display())]).unwrap();
        assert_eq!(m["json"], json!("{\"base\":\"none\"}"));
        std::fs::remove_file(&path).ok();
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
