fn main() -> std::process::ExitCode {
    // Native only, before any thread: a stale inherited preload would stop
    // every child. (The guest cannot see Android paths, so it keeps its own.)
    #[cfg(target_os = "android")]
    nixpp::preload::drop_missing();
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    if let Err(error) = nixpp::signal_stack::reserve() {
        eprintln!("nixpp: cannot reserve guest signal stack: {error}");
        return std::process::ExitCode::FAILURE;
    }
    nixpp::cli::run()
}
